package com.bancoxyz.batch.listener;

import com.bancoxyz.common.Constantes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** El medidor cuenta lo de cada ejecucion por separado y no pierde chunks bajo concurrencia. */
class MedidorDeRendimientoListenerTest {

    private final AtomicLong siguienteId = new AtomicLong(1);
    private final JobExecution job = jobExecution();

    private static JobExecution jobExecution() {
        JobExecution job = new JobExecution(1L);
        job.setJobInstance(new JobInstance(1L, "jobDePrueba"));
        return job;
    }

    /**
     * Crea una StepExecution con id, como la deja el JobRepository en una corrida real. El id
     * importa: es la clave con la que el medidor separa lo que mide cada ejecucion.
     */
    private StepExecution paso(String nombre) {
        StepExecution paso = job.createStepExecution(nombre);
        paso.setId(siguienteId.getAndIncrement());
        return paso;
    }

    private ChunkContext chunkContext(StepExecution paso) {
        return new ChunkContext(new StepContext(paso));
    }

    @Test
    @DisplayName("Cuenta los chunks de cada hilo sin perder ninguno bajo concurrencia")
    void cuentaLosChunksDeCadaHiloSinPerderNinguno() throws Exception {
        MedidorDeRendimientoListener medidor =
                new MedidorDeRendimientoListener("pasoDePrueba", 5, 3);
        StepExecution paso = paso(Constantes.STEP_PROCESAR_TRANSACCIONES);
        medidor.beforeStep(paso);

        int hilos = 3;
        int chunksPorHilo = 200;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch listos = new CountDownLatch(hilos);
        try {
            for (int h = 0; h < hilos; h++) {
                pool.submit(() -> {
                    for (int i = 0; i < chunksPorHilo; i++) {
                        medidor.afterChunk(chunkContext(paso));
                    }
                    listos.countDown();
                });
            }
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(medidor.hilosQueTrabajaron(paso.getId())).isEqualTo(hilos);

        paso.setReadCount(hilos * chunksPorHilo * 5L);
        medidor.afterStep(paso);

        // Si el contador no fuera concurrente, aqui faltarian incrementos perdidos en carreras.
        assertThat(paso.getExecutionContext()
                .getInt(MedidorDeRendimientoListener.CTX_CHUNKS))
                .isEqualTo(hilos * chunksPorHilo);
    }

    @Test
    @DisplayName("Varias particiones a la vez sobre el mismo listener no se pisan sus contadores")
    void separaLoQueMideCadaParticion() {
        // Este es el escenario que introduce el particionado y que el modelo anterior —un
        // contador en campos de instancia, reiniciado en beforeStep— rompia en silencio: el
        // Step trabajador es UN objeto que se ejecuta N veces a la vez, con este mismo listener
        // compartido, de modo que el beforeStep de una particion borraba lo que llevaba otra.
        MedidorDeRendimientoListener medidor =
                new MedidorDeRendimientoListener(Constantes.STEP_PROCESAR_TRANSACCIONES, 5, 1);

        StepExecution particion0 = paso(Constantes.STEP_PROCESAR_TRANSACCIONES + "Worker:particion0");
        StepExecution particion1 = paso(Constantes.STEP_PROCESAR_TRANSACCIONES + "Worker:particion1");

        medidor.beforeStep(particion0);
        for (int i = 0; i < 5; i++) {
            medidor.afterChunk(chunkContext(particion0));
        }

        // La particion 1 arranca cuando la 0 ya lleva cinco chunks contados.
        medidor.beforeStep(particion1);
        for (int i = 0; i < 7; i++) {
            medidor.afterChunk(chunkContext(particion1));
        }

        particion0.setReadCount(25);
        particion1.setReadCount(35);
        medidor.afterStep(particion0);
        medidor.afterStep(particion1);

        assertThat(particion0.getExecutionContext()
                .getInt(MedidorDeRendimientoListener.CTX_CHUNKS)).isEqualTo(5);
        assertThat(particion1.getExecutionContext()
                .getInt(MedidorDeRendimientoListener.CTX_CHUNKS)).isEqualTo(7);
    }

    @Test
    @DisplayName("Las metricas quedan en el ExecutionContext, que Spring Batch persiste")
    void publicaLasMetricasEnElContextoDelPaso() {
        MedidorDeRendimientoListener medidor =
                new MedidorDeRendimientoListener("pasoDePrueba", 5, 3);
        StepExecution paso = paso(Constantes.STEP_PROCESAR_TRANSACCIONES);
        medidor.beforeStep(paso);
        medidor.beforeChunk(chunkContext(paso));
        medidor.afterChunk(chunkContext(paso));
        paso.setReadCount(5);

        medidor.afterStep(paso);

        assertThat(paso.getExecutionContext()
                .getInt(MedidorDeRendimientoListener.CTX_TAMANO_CHUNK)).isEqualTo(5);
        assertThat(paso.getExecutionContext()
                .getString(MedidorDeRendimientoListener.CTX_REPARTO_POR_HILO))
                .contains(Thread.currentThread().getName() + "=1");
        assertThat(paso.getExecutionContext()
                .getString(MedidorDeRendimientoListener.CTX_ITEMS_POR_SEGUNDO)).isNotBlank();
        // La marca que el decisor de calidad usa para saber que este Step leyo del archivo.
        assertThat(paso.getExecutionContext()
                .getString(Constantes.CTX_PASO_DE_MIGRACION)).isEqualTo("pasoDePrueba");
    }

    @Test
    @DisplayName("El medidor no altera el resultado del Step: medir no puede cambiar lo medido")
    void noAlteraElResultadoDelPaso() {
        MedidorDeRendimientoListener medidor =
                new MedidorDeRendimientoListener("pasoDePrueba", 5, 3);
        StepExecution paso = paso(Constantes.STEP_PROCESAR_TRANSACCIONES);
        medidor.beforeStep(paso);
        medidor.afterChunkError(chunkContext(paso));

        assertThat(medidor.afterStep(paso)).isEqualTo(paso.getExitStatus());
    }
}
