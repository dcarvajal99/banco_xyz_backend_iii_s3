package com.bancoxyz.config;

import com.bancoxyz.common.Constantes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidencia del {@code RetryPolicy}: ante una caida transitoria de la base de datos el
 * chunk se revierte, se reintenta y el Job termina igualmente en {@code COMPLETED}, sin
 * perder ningun registro.
 *
 * <p>El fallo se provoca con la propiedad {@code banco.batch.simular-fallo-transitorio},
 * que en produccion viene apagada.</p>
 */
@SpringBootTest(properties = "banco.batch.simular-fallo-transitorio=true")
class ReintentoAnteFalloTransitorioIT {

    private static final Path ENTRADA = Path.of("src", "test", "resources", "data", "prueba");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("reporteTransaccionesDiariasJob")
    private Job jobTransacciones;

    @Test
    @DisplayName("Un fallo transitorio de base de datos se reintenta y el Job termina COMPLETED")
    void reintentaYCompleta(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones, new JobParametersBuilder()
                .addString(Constantes.PARAM_ENTRADA, ENTRADA.toString())
                .addString(Constantes.PARAM_SALIDA, salida.toString())
                .addString(Constantes.PARAM_EJECUCION, LocalDateTime.now() + "-" + UUID.randomUUID())
                .toJobParameters());

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution procesar = ejecucion.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(Constantes.STEP_PROCESAR_TRANSACCIONES))
                .findFirst().orElseThrow();

        // Hubo al menos una vuelta atras por el fallo simulado...
        assertThat(procesar.getRollbackCount()).isPositive();
        // ...y aun asi se escribieron las cinco transacciones validas.
        assertThat(procesar.getWriteCount()).isEqualTo(5);
        assertThat(procesar.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }
}
