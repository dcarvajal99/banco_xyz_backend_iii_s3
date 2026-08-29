package com.bancoxyz.config;

import com.bancoxyz.common.Constantes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Resolucion de argumentos, validaciones previas y codigo de salida del lanzador. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LanzadorDeJobsTest {

    private static final Path ENTRADA = Path.of("src", "test", "resources", "data", "prueba");

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job jobTransacciones;

    @Mock
    private JobExecution ejecucion;

    /**
     * El JobExplorer solo se usa para avisar de una re-ejecucion. Devolviendo {@code null}
     * como instancia, el lanzador toma el camino de "corrida nueva", que es el que ejercitan
     * estas pruebas; la reanudacion real se prueba en PoliticasDeFinalizacionIT.
     */
    @Mock
    private JobExplorer jobExplorer;

    private LanzadorDeJobs lanzador() {
        return new LanzadorDeJobs(jobLauncher, jobExplorer,
                Map.of(Constantes.JOB_TRANSACCIONES_DIARIAS, jobTransacciones), new PropiedadesBatch());
    }

    @Test
    @DisplayName("El alias 'transacciones' lanza el Job correcto con sus parametros")
    void lanzaElJobPorAlias(@TempDir Path salida) throws Exception {
        when(jobLauncher.run(eq(jobTransacciones), any())).thenReturn(ejecucion);
        when(ejecucion.getStatus()).thenReturn(BatchStatus.COMPLETED);

        LanzadorDeJobs lanzador = lanzador();
        lanzador.run(new DefaultApplicationArguments(
                "--job=transacciones", "--entrada=" + ENTRADA, "--salida=" + salida));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(jobTransacciones), captor.capture());
        JobParameters parametros = captor.getValue();
        assertThat(parametros.getString(Constantes.PARAM_ENTRADA)).isEqualTo(ENTRADA.toString());
        assertThat(parametros.getString(Constantes.PARAM_SALIDA)).isEqualTo(salida.toString());
        assertThat(parametros.getString(Constantes.PARAM_EJECUCION)).isNotBlank();
        assertThat(lanzador.getExitCode()).isZero();
        assertThat(Files.isDirectory(salida)).isTrue();
    }

    @Test
    @DisplayName("El parametro --dataset se resuelve dentro de la carpeta data/")
    void resuelveElDatasetPorNombre(@TempDir Path salida) throws Exception {
        LanzadorDeJobs lanzador = lanzador();
        lanzador.run(new DefaultApplicationArguments(
                "--job=transacciones", "--dataset=inexistente", "--salida=" + salida));

        // data/inexistente no existe: el lanzador aborta antes de tocar el JobLauncher.
        assertThat(lanzador.getExitCode()).isEqualTo(2);
        verifyNoInteractions(jobLauncher);
    }

    @Test
    @DisplayName("Un alias desconocido termina con codigo 2 sin ejecutar nada")
    void rechazaAliasDesconocido(@TempDir Path salida) throws Exception {
        LanzadorDeJobs lanzador = lanzador();
        lanzador.run(new DefaultApplicationArguments("--job=inventado", "--salida=" + salida));

        assertThat(lanzador.getExitCode()).isEqualTo(2);
        verifyNoInteractions(jobLauncher);
    }

    @Test
    @DisplayName("Si el Job no esta registrado en el contexto termina con codigo 2")
    void rechazaJobNoRegistrado(@TempDir Path salida) throws Exception {
        LanzadorDeJobs lanzador = new LanzadorDeJobs(jobLauncher, jobExplorer, Map.of(), new PropiedadesBatch());
        lanzador.run(new DefaultApplicationArguments(
                "--job=transacciones", "--entrada=" + ENTRADA, "--salida=" + salida));

        assertThat(lanzador.getExitCode()).isEqualTo(2);
        verifyNoInteractions(jobLauncher);
    }

    @Test
    @DisplayName("Un Job fallido se refleja en el codigo de salida 1")
    void propagaElFalloComoCodigoDeSalida(@TempDir Path salida) throws Exception {
        when(jobLauncher.run(eq(jobTransacciones), any())).thenReturn(ejecucion);
        when(ejecucion.getStatus()).thenReturn(BatchStatus.FAILED);

        LanzadorDeJobs lanzador = lanzador();
        lanzador.run(new DefaultApplicationArguments(
                "--job=transacciones", "--entrada=" + ENTRADA, "--salida=" + salida));

        assertThat(lanzador.getExitCode()).isEqualTo(1);
    }
}
