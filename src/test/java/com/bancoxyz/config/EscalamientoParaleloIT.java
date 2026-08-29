package com.bancoxyz.config;

import com.bancoxyz.batch.listener.MedidorDeRendimientoListener;
import com.bancoxyz.batch.listener.ResumenDeParticionesListener;
import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.CuentaInteres;
import com.bancoxyz.entity.Transaccion;
import com.bancoxyz.repository.CuentaInteresRepository;
import com.bancoxyz.repository.TransaccionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidencia del escalado por particiones: el archivo se reparte de verdad, cada particion
 * procesa su tramo y el resultado no depende de como se haya repartido.
 *
 * <p>Las dos ultimas comprobaciones son las que importan para un banco. Paralelizar es facil;
 * lo dificil es garantizar que ninguna fila se pierda ni se duplique en los limites entre
 * particiones, y que la deduplicacion siga viendo el archivo completo aunque cada particion
 * solo vea su tramo.</p>
 */
@SpringBootTest
// Se prueba con la configuracion que trae el proyecto por defecto —particionado en 8— y no con
// la del perfil general de pruebas, para que sea esta prueba la que garantice el requisito.
@TestPropertySource(properties = {
        "banco.batch.estrategia=PARTICIONADO",
        "banco.batch.particiones=8",
        "banco.batch.tamano-chunk=5"
})
class EscalamientoParaleloIT {

    private static final int PARTICIONES = 8;
    /** 300 filas limpias: suficientes para que el reparto entre particiones sea observable. */
    private static final Path VOLUMEN = Path.of("src", "test", "resources", "data", "volumen");
    /** 200 filas donde la primera y la ultima son identicas: caen en particiones distintas. */
    private static final Path DUPLICADO_EXTREMOS =
            Path.of("src", "test", "resources", "data", "duplicado_extremos");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("reporteTransaccionesDiariasJob")
    private Job jobTransacciones;

    @Autowired
    @Qualifier("calculoInteresesMensualesJob")
    private Job jobIntereses;

    @Autowired
    private TransaccionRepository transacciones;

    @Autowired
    private CuentaInteresRepository cuentas;

    private JobParameters parametros(Path entrada, Path salida) {
        return new JobParametersBuilder()
                .addString(Constantes.PARAM_ENTRADA, entrada.toString())
                .addString(Constantes.PARAM_SALIDA, salida.toString())
                .addString(Constantes.PARAM_EJECUCION, LocalDateTime.now() + "-" + UUID.randomUUID())
                .toJobParameters();
    }

    /** El Step gestor: conserva el nombre de siempre y sus contadores son la suma del reparto. */
    private static StepExecution gestor(JobExecution ejecucion, String nombre) {
        return ejecucion.getStepExecutions().stream()
                .filter(paso -> paso.getStepName().equals(nombre))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se ejecuto el Step gestor " + nombre));
    }

    /** Las ejecuciones de las particiones de un gestor. */
    private static List<StepExecution> particiones(JobExecution ejecucion, String gestor) {
        String prefijo = gestor + Constantes.SUFIJO_WORKER + Constantes.SUFIJO_PARTICION;
        return ejecucion.getStepExecutions().stream()
                .filter(paso -> paso.getStepName().startsWith(prefijo))
                .sorted(java.util.Comparator.comparing(StepExecution::getStepName))
                .toList();
    }

    @Test
    @DisplayName("El archivo se reparte en el numero de particiones configurado y todas terminan bien")
    void repartelArchivoEntreLasParticionesConfiguradas(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones, parametros(VOLUMEN, salida));
        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution manager = gestor(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES);
        List<StepExecution> partes = particiones(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES);

        assertThat(partes).hasSize(PARTICIONES);
        assertThat(partes).allSatisfy(parte ->
                assertThat(parte.getStatus()).isEqualTo(BatchStatus.COMPLETED));
        assertThat(manager.getExecutionContext()
                .getInt(ResumenDeParticionesListener.CTX_PARTICIONES)).isEqualTo(PARTICIONES);

        // Cada particion corrio en un hilo del pool de particiones, no en el principal.
        assertThat(partes).allSatisfy(parte ->
                assertThat(parte.getExecutionContext()
                        .getString(MedidorDeRendimientoListener.CTX_REPARTO_POR_HILO))
                        .contains("batch-particion-"));
    }

    @Test
    @DisplayName("Ninguna fila se pierde ni se duplica en los limites entre particiones")
    void noSePierdeNiSeDuplicaNingunaFilaEnLosLimites(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones, parametros(VOLUMEN, salida));

        StepExecution manager = gestor(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES);
        List<StepExecution> partes = particiones(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES);

        // Es la comprobacion central del particionado por rangos: los tramos tienen que cubrir
        // el archivo exactamente una vez. Un error de un indice en el reparto se manifestaria
        // aqui como una fila de menos o de mas, y en produccion como un movimiento perdido o
        // cobrado dos veces.
        long leidasPorLasParticiones = partes.stream()
                .mapToLong(p -> p.getReadCount() + p.getReadSkipCount()).sum();
        assertThat(leidasPorLasParticiones).isEqualTo(300);
        assertThat(manager.getReadCount() + manager.getReadSkipCount()).isEqualTo(300);

        // Y los identificadores migrados son los 300 del archivo, sin repeticiones.
        List<Transaccion> migradas = transacciones
                .findByJobExecutionId(ejecucion.getId(), PageRequest.of(0, 500)).getContent();
        assertThat(migradas).hasSize(300);
        assertThat(migradas).extracting(Transaccion::getIdOrigen).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Se respeta el tamano de chunk dentro de cada particion")
    void respetaElTamanoDeChunkDentroDeCadaParticion(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones, parametros(VOLUMEN, salida));

        List<StepExecution> partes = particiones(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES);
        long chunksTotales = partes.stream()
                .mapToLong(p -> p.getExecutionContext()
                        .getInt(MedidorDeRendimientoListener.CTX_CHUNKS)).sum();

        assertThat(partes).allSatisfy(parte -> assertThat(parte.getExecutionContext()
                .getInt(MedidorDeRendimientoListener.CTX_TAMANO_CHUNK)).isEqualTo(5));
        // 300 filas de a 5 son 60 chunks con datos. Cada particion cierra ademas su propio
        // chunk vacio al agotar su tramo, asi que el total nunca baja de 60 ni sube mas alla
        // de esos cierres.
        assertThat(chunksTotales).isBetween(60L, 60L + PARTICIONES);
        // Dentro de una particion se procesa con un solo hilo: el paralelismo lo aporta el
        // gestor al correr varias particiones a la vez, no varios hilos dentro de cada una.
        assertThat(partes).allSatisfy(parte -> assertThat(parte.getExecutionContext()
                .getInt(MedidorDeRendimientoListener.CTX_HILOS_USADOS)).isEqualTo(1));
    }

    @Test
    @DisplayName("La deduplicacion ve el archivo completo aunque las copias caigan en particiones distintas")
    void deduplicaEntreParticiones(@TempDir Path salida) throws Exception {
        // La primera y la ultima fila de intereses.csv son identicas. Con cuatro particiones
        // caen en la primera y en la ultima, de modo que ninguna particion ve las dos. Si el
        // estado de deduplicacion viviera dentro de cada particion —como ocurria cuando era un
        // campo del ItemProcessor @StepScope— las dos se darian por buenas y la cuenta 9000 se
        // liquidaria dos veces. Es un error contable, no un detalle de rendimiento.
        JobExecution ejecucion = jobLauncher.run(jobIntereses,
                parametros(DUPLICADO_EXTREMOS, salida));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution manager = gestor(ejecucion, Constantes.STEP_PROCESAR_INTERESES);
        assertThat(manager.getReadCount() + manager.getReadSkipCount()).isEqualTo(200);
        // Exactamente una de las dos copias se filtro.
        assertThat(manager.getFilterCount()).isEqualTo(1);

        List<CuentaInteres> liquidadas = cuentas
                .findByJobExecutionId(ejecucion.getId(), PageRequest.of(0, 500)).getContent();
        assertThat(liquidadas).hasSize(199);
        assertThat(liquidadas)
                .filteredOn(cuenta -> cuenta.getCuentaId() == 9000L)
                .hasSize(1);
    }

    @Test
    @DisplayName("Dos corridas del mismo archivo migran exactamente los mismos datos")
    void elResultadoNoDependeDelReparto(@TempDir Path salida) throws Exception {
        JobExecution primera = jobLauncher.run(jobTransacciones, parametros(VOLUMEN, salida));
        List<Transaccion> resultadoPrimera = transacciones
                .findByJobExecutionId(primera.getId(), PageRequest.of(0, 500)).getContent();

        JobExecution segunda = jobLauncher.run(jobTransacciones, parametros(VOLUMEN, salida));
        List<Transaccion> resultadoSegunda = transacciones
                .findByJobExecutionId(segunda.getId(), PageRequest.of(0, 500)).getContent();

        assertThat(primera.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(segunda.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // El orden de insercion puede variar entre corridas —lo decide el planificador de
        // hilos— pero el conjunto migrado no. Se comparan las claves de negocio y la suma de
        // los montos, sin importar el orden: es la garantia de reproducibilidad que el banco
        // necesita para poder reprocesar un archivo y obtener lo mismo.
        assertThat(resultadoSegunda)
                .extracting(Transaccion::getIdOrigen)
                .containsExactlyInAnyOrderElementsOf(
                        resultadoPrimera.stream().map(Transaccion::getIdOrigen).toList());
        assertThat(sumaDeMontos(resultadoSegunda)).isEqualByComparingTo(sumaDeMontos(resultadoPrimera));
        assertThat(resultadoSegunda).hasSameSizeAs(resultadoPrimera).hasSize(300);
    }

    @Test
    @DisplayName("El lanzamiento de Jobs sigue siendo sincrono pese a los pools de hilos del contexto")
    void elLanzamientoDeJobsEsSincrono(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones, parametros(VOLUMEN, salida));

        // Si Spring Boot llegara a entregarle uno de los ThreadPoolTaskExecutor al JobLauncher,
        // run() devolveria con el Job todavia en STARTING y el lanzador por linea de comandos
        // informaria "finalizado" antes de que la migracion hubiera terminado.
        assertThat(ejecucion.getStatus().isRunning()).isFalse();
        assertThat(ejecucion.getEndTime()).isNotNull();
        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("Los valores por defecto del proyecto son los de la estrategia particionada")
    void losValoresPorDefectoSonLosDeLaEstrategiaParticionada() {
        PropiedadesBatch porDefecto = new PropiedadesBatch();

        assertThat(porDefecto.getEstrategia()).isEqualTo(EstrategiaDeEscalado.PARTICIONADO);
        assertThat(porDefecto.getParticiones()).isEqualTo(8);
        // Sin fijar hilos, siguen automaticamente al numero de particiones: es lo que evita
        // que subir "particiones" deje tramos esperando turno sin que nada lo indique.
        assertThat(porDefecto.getHilosDeParticiones()).isEqualTo(porDefecto.getParticiones());
    }

    private static BigDecimal sumaDeMontos(List<Transaccion> filas) {
        return filas.stream().map(Transaccion::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
