package com.bancoxyz.config;

import com.bancoxyz.batch.listener.RegistroRechazadoSkipListener;
import com.bancoxyz.batch.processor.MovimientoAnualItemProcessor;
import com.bancoxyz.batch.partition.ParticionadorPorRangoDeLineas;
import com.bancoxyz.batch.reader.LectoresCsv;
import com.bancoxyz.batch.tasklet.EstadosCuentaAnualesTasklet;
import com.bancoxyz.common.Constantes;
import com.bancoxyz.dto.MovimientoAnualCsv;
import com.bancoxyz.entity.MovimientoAnual;
import com.bancoxyz.service.RegistroRechazadoService;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Job 3 — Generacion de estados de cuenta anuales.
 *
 * <p>Reemplaza la compilacion manual que el area de auditoria armaba a fin de ano con
 * shell scripts. Migra {@code cuentas_anuales.csv} normalizando el signo de los montos y
 * completando descripciones, y luego compila el estado por cuenta y ano.</p>
 *
 * <p>Desde la semana 2 el paso de migracion corre en paralelo (tres hilos, chunks de cinco)
 * y el flujo pasa por el decisor de calidad antes de compilar el estado de cuenta que ve la
 * auditoria. Ver {@link ArmadorDeJobs}.</p>
 *
 * <pre>
 *   limpieza -> procesarMovimientosAnualesStep -> exportarRechazadosStep -> [decisor]
 *   (tasklet)         (chunk, 3 hilos)                  (tasklet)              |
 *                                                       compilarEstadosCuentaStep (tasklet)
 * </pre>
 */
@Configuration
public class EstadosCuentaAnualesJobConfig {

    private final ConstructorDePasos constructor;

    public EstadosCuentaAnualesJobConfig(ConstructorDePasos constructor) {
        this.constructor = constructor;
    }

    @Bean
    public Job estadosCuentaAnualesJob(ArmadorDeJobs armador,
                                       Step procesarMovimientosAnualesStep,
                                       Step compilarEstadosCuentaStep) {
        return armador.jobDeMigracion(Constantes.JOB_ESTADOS_CUENTA_ANUALES,
                procesarMovimientosAnualesStep, compilarEstadosCuentaStep);
    }

    @Bean
    public Step procesarMovimientosAnualesStep(
            FlatFileItemReader<MovimientoAnualCsv> lectorMovimientosAnuales,
            MovimientoAnualItemProcessor procesadorMovimientosAnuales,
            JpaItemWriter<MovimientoAnual> escritorMovimientosAnuales,
            RegistroRechazadoSkipListener<MovimientoAnualCsv, MovimientoAnual> oyenteRechazoMovimientos,
                                               Partitioner particionadorMovimientosAnuales) {
        return constructor.pasoDeMigracion(Constantes.STEP_PROCESAR_MOVIMIENTOS,
                lectorMovimientosAnuales, procesadorMovimientosAnuales,
                escritorMovimientosAnuales, oyenteRechazoMovimientos, particionadorMovimientosAnuales);
    }

    @Bean
    public Step compilarEstadosCuentaStep(EstadosCuentaAnualesTasklet estadosCuentaAnualesTasklet) {
        return constructor.pasoDeTarea(Constantes.STEP_ESTADOS_CUENTA, estadosCuentaAnualesTasklet);
    }

    /**
     * El lector es {@code @StepScope} porque sus dos entradas solo existen cuando hay una
     * ejecucion concreta: la carpeta llega como parametro del Job, y el rango de filas llega
     * como contexto de la particion. Cuando la estrategia no es particionada esas claves no
     * existen, llegan como {@code null} y el lector recorre el archivo completo.
     */
    @Bean
    @StepScope
    public FlatFileItemReader<MovimientoAnualCsv> lectorMovimientosAnuales(
            @Value("#{jobParameters['" + Constantes.PARAM_ENTRADA + "']}") String carpetaEntrada,
            @Value("#{stepExecutionContext['" + Constantes.PARTICION_INICIO + "']}") Long inicio,
            @Value("#{stepExecutionContext['" + Constantes.PARTICION_FIN + "']}") Long fin) {
        return LectoresCsv.deMovimientosAnuales(Path.of(carpetaEntrada, Constantes.ARCHIVO_CUENTAS_ANUALES),
                LectoresCsv.rangoDeParticion(inicio, fin));
    }

    /**
     * Reparte el archivo entre las particiones.
     *
     * <p>Es {@code @StepScope} y no {@code @JobScope} aunque solo dependa de un parametro del
     * Job. El ambito {@code job} de Spring Batch se apoya en un contexto ligado al hilo, y el
     * Step gestor no siempre corre en el hilo principal: en el cierre nocturno completo lo
     * ejecuta un hilo del {@code split}, donde ese contexto no existe y el bean revienta con
     * {@code ScopeNotActiveException}. El ambito {@code step} si esta activo alli, porque lo
     * establece el propio Step al empezar, sea cual sea el hilo que lo ejecute.</p>
     */
    @Bean
    @StepScope
    public Partitioner particionadorMovimientosAnuales(
            @Value("#{jobParameters['" + Constantes.PARAM_ENTRADA + "']}") String carpetaEntrada) {
        return new ParticionadorPorRangoDeLineas(
                Path.of(carpetaEntrada, Constantes.ARCHIVO_CUENTAS_ANUALES), Constantes.STEP_PROCESAR_MOVIMIENTOS, 1);
    }

    @Bean
    public JpaItemWriter<MovimientoAnual> escritorMovimientosAnuales(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<MovimientoAnual>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(true)
                .build();
    }

    @Bean
    @StepScope
    public RegistroRechazadoSkipListener<MovimientoAnualCsv, MovimientoAnual> oyenteRechazoMovimientos(
            RegistroRechazadoService bitacora,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return new RegistroRechazadoSkipListener<>(Constantes.JOB_ESTADOS_CUENTA_ANUALES,
                Constantes.ARCHIVO_CUENTAS_ANUALES, bitacora, jobExecutionId);
    }
}
