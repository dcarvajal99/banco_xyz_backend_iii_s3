package com.bancoxyz.config;

import com.bancoxyz.batch.decider.DecisorCalidadDeDatos;
import com.bancoxyz.batch.listener.ResumenJobListener;
import com.bancoxyz.batch.processor.RegistroDeDuplicados;
import com.bancoxyz.common.Constantes;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Job 4 — Cierre nocturno completo: los tres procesos a la vez.
 *
 * <p>Es el Job donde se ven los <b>dos niveles de escalado</b> trabajando juntos, que es el
 * objetivo de la semana:</p>
 *
 * <ul>
 *   <li><b>Paralelismo entre archivos</b> ({@code split}): los tres archivos legacy son
 *       independientes, asi que no hay razon para procesarlos en serie. El tiempo total pasa
 *       a ser el del proceso mas lento y no la suma de los tres.</li>
 *   <li><b>Paralelismo dentro de cada archivo</b> ({@code taskExecutor} en el Step): cada uno
 *       de esos tres flujos reparte ademas sus chunks entre tres hilos.</li>
 * </ul>
 *
 * <p>En conjunto hay hasta nueve chunks procesandose a la vez, cada uno con su transaccion.
 * Ese numero es el que dimensiona el pool de conexiones de HikariCP en
 * {@code application.properties}: si el pool fuera mas chico que la concurrencia real, los
 * hilos se quedarian esperando conexion y el paralelismo, en vez de acelerar, produciria
 * timeouts.</p>
 *
 * <pre>
 *   limpieza --+-- flujoTransacciones (procesar, 3 hilos) --+
 *              +-- flujoIntereses     (procesar, 3 hilos) --+-- exportar rechazos -- [decisor]
 *              +-- flujoEstadosCuenta (procesar, 3 hilos) --+                            |
 *                                                                                        |
 *        ACEPTABLE / DEGRADADA --&gt; flujoPublicacion (los tres reportes)  ----------------+
 *        INACEPTABLE           --&gt; cuarentena de corte --&gt; FAILED
 * </pre>
 *
 * <p>La exportacion de rechazos y el decisor quedan despues de la union: al compartir todos
 * los flujos la misma JobExecution, un unico archivo consolida lo que quedo fuera en los tres
 * y el decisor juzga la calidad de la noche completa, no la de un archivo suelto.</p>
 */
@Configuration
public class MigracionCompletaJobConfig {

    private static final String EXITO = ExitStatus.COMPLETED.getExitCode();
    private static final String CUALQUIER_OTRO = "*";

    @Bean
    public Job migracionCompletaJob(JobRepository jobRepository,
                                    ResumenJobListener resumenJobListener,
                                    RegistroDeDuplicados registroDeDuplicados,
                                    DecisorCalidadDeDatos decisor,
                                    @Qualifier("ejecutorDeFlujos") ThreadPoolTaskExecutor ejecutorDeFlujos,
                                    @Qualifier(Constantes.STEP_LIMPIEZA) Step limpiezaDeReintentoStep,
                                    @Qualifier(Constantes.STEP_EXPORTAR_RECHAZADOS) Step exportarRechazadosStep,
                                    @Qualifier(Constantes.STEP_CUARENTENA_AVISO) Step cuarentenaAvisoStep,
                                    @Qualifier(Constantes.STEP_CUARENTENA_CORTE) Step cuarentenaCorteStep,
                                    Step procesarTransaccionesStep,
                                    Step generarResumenDiarioStep,
                                    Step procesarInteresesStep,
                                    Step generarResumenInteresesStep,
                                    Step procesarMovimientosAnualesStep,
                                    Step compilarEstadosCuentaStep) {

        Flow flujoTransacciones = new FlowBuilder<Flow>("flujoTransacciones")
                .start(procesarTransaccionesStep)
                .build();

        Flow flujoIntereses = new FlowBuilder<Flow>("flujoIntereses")
                .start(procesarInteresesStep)
                .build();

        Flow flujoEstadosCuenta = new FlowBuilder<Flow>("flujoEstadosCuenta")
                .start(procesarMovimientosAnualesStep)
                .build();

        // Los tres archivos, a la vez. Con ThreadPoolTaskExecutor y no SimpleAsyncTaskExecutor:
        // el pool acotado es lo que impide que la concurrencia crezca sin control.
        Flow flujoMigracionParalela = new FlowBuilder<Flow>("flujoMigracionParalela")
                .start(flujoTransacciones)
                .split(ejecutorDeFlujos)
                .add(flujoIntereses, flujoEstadosCuenta)
                .build();

        Flow flujoCierre = new FlowBuilder<Flow>("flujoCierreNocturno")
                .start(limpiezaDeReintentoStep)
                .next(flujoMigracionParalela)
                .next(exportarRechazadosStep)
                .build();

        // Los reportes se compilan sobre lo ya persistido; van en serie porque los tres
        // consultan la misma base y paralelizarlos solo moveria la contencion a PostgreSQL.
        Flow flujoPublicacion = new FlowBuilder<Flow>("flujoPublicacion")
                .start(generarResumenDiarioStep)
                .next(generarResumenInteresesStep)
                .next(compilarEstadosCuentaStep)
                .build();

        return new JobBuilder(Constantes.JOB_MIGRACION_COMPLETA, jobRepository)
                .listener(resumenJobListener)
                .listener(registroDeDuplicados)
                .start(flujoCierre)
                .next(decisor)
                .on(Constantes.CALIDAD_ACEPTABLE).to(flujoPublicacion)
                .from(decisor).on(Constantes.CALIDAD_DEGRADADA).to(cuarentenaAvisoStep)
                .from(decisor).on(Constantes.CALIDAD_INACEPTABLE).to(cuarentenaCorteStep)
                .from(cuarentenaAvisoStep).on(EXITO).to(flujoPublicacion)
                .from(cuarentenaAvisoStep).on(CUALQUIER_OTRO).fail()
                .from(cuarentenaCorteStep).on(CUALQUIER_OTRO).fail()
                .from(flujoPublicacion).on(EXITO).end()
                .from(flujoPublicacion).on(CUALQUIER_OTRO).fail()
                .end()
                .build();
    }
}
