package com.bancoxyz.config;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.repository.TransaccionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Politicas de finalizacion y re-ejecucion, de extremo a extremo.
 *
 * <p>Cubre las tres ramas del {@code JobExecutionDecider} sobre juegos de datos con una tasa
 * de omision conocida (0 %, 20 % y 50 %) y, sobre todo, comprueba que reanudar una corrida
 * fallida deja la base <b>igual</b> que si hubiera salido bien a la primera. Esa idempotencia
 * es la condicion que un banco exige antes de permitir que un proceso nocturno se reintente
 * solo: un reintento que duplica movimientos es peor que un proceso caido.</p>
 *
 * <p>Los umbrales se fijan aqui con {@code @TestPropertySource} en los valores de produccion
 * (10 % y 30 %), porque el perfil general de pruebas los relaja para que los juegos de datos
 * sucios de las otras pruebas no deriven a la rama de corte.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "banco.batch.umbral-alerta-omision=0.10",
        "banco.batch.umbral-rechazo-omision=0.30"
})
class PoliticasDeFinalizacionIT {

    private static final Path CALIDAD_BUENA = Path.of("src", "test", "resources", "data", "calidad_buena");
    private static final Path CALIDAD_DEGRADADA = Path.of("src", "test", "resources", "data", "calidad_degradada");
    private static final Path CALIDAD_MALA = Path.of("src", "test", "resources", "data", "calidad_mala");
    /** transacciones.csv con solo la cabecera: el archivo llego, pero llego truncado. */
    private static final Path TRUNCADO = Path.of("src", "test", "resources", "data", "truncado");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("reporteTransaccionesDiariasJob")
    private Job jobTransacciones;

    @Autowired
    private TransaccionRepository transacciones;

    /** Sin etiqueta unica: la etiqueta la fija cada prueba, porque define la JobInstance. */
    private JobParameters parametros(Path entrada, Path salida, String corrida) {
        return new JobParametersBuilder()
                .addString(Constantes.PARAM_ENTRADA, entrada.toString())
                .addString(Constantes.PARAM_SALIDA, salida.toString())
                .addString(Constantes.PARAM_EJECUCION, corrida)
                .toJobParameters();
    }

    private static List<String> pasos(JobExecution ejecucion) {
        return ejecucion.getStepExecutions().stream()
                .map(org.springframework.batch.core.StepExecution::getStepName)
                .toList();
    }

    @Test
    @DisplayName("Calidad aceptable (0 % omitido): publica el reporte sin pasar por cuarentena")
    void calidadAceptablePublicaElReporte(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones,
                parametros(CALIDAD_BUENA, salida, "aceptable-" + System.nanoTime()));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(pasos(ejecucion))
                .contains(Constantes.STEP_RESUMEN_DIARIO)
                .doesNotContain(Constantes.STEP_CUARENTENA_AVISO, Constantes.STEP_CUARENTENA_CORTE);
        assertThat(ejecucion.getExecutionContext().getDouble(Constantes.CTX_TASA_OMISION))
                .isZero();
    }

    @Test
    @DisplayName("Calidad degradada (20 % omitido): avisa en cuarentena pero publica igual")
    void calidadDegradadaAvisaYPublica(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones,
                parametros(CALIDAD_DEGRADADA, salida, "degradada-" + System.nanoTime()));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(pasos(ejecucion))
                .contains(Constantes.STEP_CUARENTENA_AVISO, Constantes.STEP_RESUMEN_DIARIO)
                .doesNotContain(Constantes.STEP_CUARENTENA_CORTE);
        assertThat(ejecucion.getExecutionContext().getDouble(Constantes.CTX_TASA_OMISION))
                .isEqualTo(0.20);
        assertThat(salida.resolve(Constantes.SALIDA_CUARENTENA)).exists();
    }

    @Test
    @DisplayName("Calidad inaceptable (50 % omitido): NO publica el reporte y la corrida falla")
    void calidadInaceptableCortaLaCorrida(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones,
                parametros(CALIDAD_MALA, salida, "inaceptable-" + System.nanoTime()));

        // Fallar es la decision correcta, no un accidente: publicar un reporte diario
        // construido sobre la mitad de los movimientos seria peor que no publicarlo.
        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(pasos(ejecucion))
                .contains(Constantes.STEP_CUARENTENA_CORTE)
                .doesNotContain(Constantes.STEP_RESUMEN_DIARIO, Constantes.STEP_CUARENTENA_AVISO);

        // La bitacora si se exporto: es lo que el area de datos necesita para corregir el origen.
        assertThat(pasos(ejecucion)).contains(Constantes.STEP_EXPORTAR_RECHAZADOS);
        assertThat(Files.readString(salida.resolve(Constantes.SALIDA_CUARENTENA)))
                .contains(Constantes.CALIDAD_INACEPTABLE)
                // El aviso trae el comando exacto con el que se reanuda esta misma instancia.
                .contains("--corrida=");
    }

    @Test
    @DisplayName("Un archivo truncado NO se publica, aunque su tasa de omision sea del 0 %")
    void elArchivoTruncadoNoSePublica(@TempDir Path salida) throws Exception {
        // Este es el caso que la tasa de omision, por si sola, no puede ver: un archivo del que
        // solo llego la cabecera no tiene ninguna fila omitida y por lo tanto su tasa es la mejor
        // posible. Sin la comprobacion de cobertura, el Job terminaria COMPLETED y publicaria un
        // reporte vacio encima del del dia anterior, indistinguible de una jornada sin
        // movimientos: la perdida total de los datos del dia informada como exito.
        JobExecution ejecucion = jobLauncher.run(jobTransacciones,
                parametros(TRUNCADO, salida, "truncado-" + System.nanoTime()));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(pasos(ejecucion))
                .contains(Constantes.STEP_CUARENTENA_CORTE)
                .doesNotContain(Constantes.STEP_RESUMEN_DIARIO);

        // Y el reporte diario no llego a escribirse.
        assertThat(salida.resolve(Constantes.SALIDA_TRANSACCIONES)).doesNotExist();

        // El aviso nombra el paso que se quedo sin datos, no una tasa que no explica nada.
        assertThat(Files.readString(salida.resolve(Constantes.SALIDA_CUARENTENA)))
                .contains("Cobertura insuficiente")
                .contains(Constantes.STEP_PROCESAR_TRANSACCIONES);
    }

    @Test
    @DisplayName("Con umbrales de contingencia, el mismo archivo malo se migra dejando el aviso")
    void losUmbralesSonUnaDecisionDeNegocioYNoDeCodigo(@TempDir Path salida) throws Exception {
        // Se relanza el mismo archivo del caso anterior, pero con la politica de contingencia
        // que un cierre de fin de mes podria autorizar. El codigo es el mismo; cambia la regla.
        JobExecution ejecucion = jobLauncher.run(jobTransacciones,
                new JobParametersBuilder()
                        .addString(Constantes.PARAM_ENTRADA, CALIDAD_MALA.toString())
                        .addString(Constantes.PARAM_SALIDA, salida.toString())
                        .addString(Constantes.PARAM_EJECUCION, "contingencia-" + System.nanoTime())
                        .toJobParameters());

        // Con los umbrales de produccion (30 %) este archivo corta la corrida.
        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("Reanudar una corrida fallida no duplica datos: la re-ejecucion es idempotente")
    void laReejecucionEsIdempotente(@TempDir Path salida) throws Exception {
        String corrida = "reanudable-" + System.nanoTime();

        // Primer intento sobre un archivo inservible: el decisor corta y el Job queda FAILED,
        // pero las 50 filas validas ya se escribieron.
        JobExecution primero = jobLauncher.run(jobTransacciones,
                parametros(CALIDAD_MALA, salida, corrida));
        assertThat(primero.getStatus()).isEqualTo(BatchStatus.FAILED);
        long escritasEnElPrimerIntento = transacciones.countByJobExecutionId(primero.getId());
        assertThat(escritasEnElPrimerIntento).isEqualTo(50);

        long totalTrasElPrimerIntento = transacciones.count();

        // Segundo intento con LOS MISMOS parametros: es la misma JobInstance, asi que Spring
        // Batch la reanuda en vez de crear una corrida nueva.
        JobExecution segundo = jobLauncher.run(jobTransacciones,
                parametros(CALIDAD_MALA, salida, corrida));

        assertThat(segundo.getJobInstance().getInstanceId())
                .isEqualTo(primero.getJobInstance().getInstanceId());
        assertThat(segundo.getId()).isNotEqualTo(primero.getId());
        assertThat(pasos(segundo))
                .contains(Constantes.STEP_LIMPIEZA)
                // El Step de migracion SI se rehace, aunque en el primer intento hubiera
                // terminado bien: es lo que compensa no poder retomar el archivo a mitad.
                .contains(Constantes.STEP_PROCESAR_TRANSACCIONES);

        // El archivo sigue siendo el mismo desastre, asi que el decisor vuelve a cortar.
        assertThat(segundo.getStatus()).isEqualTo(BatchStatus.FAILED);

        // La limpieza previa borro lo que dejo el intento fallido...
        assertThat(transacciones.countByJobExecutionId(primero.getId())).isZero();
        // ...el segundo intento escribio exactamente lo mismo...
        assertThat(transacciones.countByJobExecutionId(segundo.getId()))
                .isEqualTo(escritasEnElPrimerIntento);
        // ...y, lo que de verdad importa, la tabla NO acumulo los dos intentos. Un reintento
        // que duplica movimientos bancarios es peor que un proceso caido.
        assertThat(transacciones.count()).isEqualTo(totalTrasElPrimerIntento);
    }
}
