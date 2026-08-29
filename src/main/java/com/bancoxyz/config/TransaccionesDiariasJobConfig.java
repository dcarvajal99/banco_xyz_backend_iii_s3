package com.bancoxyz.config;

import com.bancoxyz.batch.listener.RegistroRechazadoSkipListener;
import com.bancoxyz.batch.processor.TransaccionItemProcessor;
import com.bancoxyz.batch.partition.ParticionadorPorRangoDeLineas;
import com.bancoxyz.batch.reader.LectoresCsv;
import com.bancoxyz.batch.tasklet.ResumenDiarioTasklet;
import com.bancoxyz.common.Constantes;
import com.bancoxyz.dto.TransaccionCsv;
import com.bancoxyz.entity.Transaccion;
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
 * Job 1 — Reporte de transacciones diarias.
 *
 * <p>Reemplaza el proceso COBOL que cada noche listaba los movimientos del dia. El flujo
 * es: migrar {@code transacciones.csv} detectando anomalias, compilar el resumen por dia
 * y exportar la bitacora de lo que quedo fuera.</p>
 *
 * <p>Desde la semana 2 el paso de migracion corre en paralelo (tres hilos, chunks de cinco)
 * y el flujo pasa por el decisor de calidad antes de publicar el reporte diario. El armado
 * completo del flujo esta en {@link ArmadorDeJobs}.</p>
 *
 * <pre>
 *   limpieza -> procesarTransaccionesStep -> exportarRechazadosStep -> [decisor]
 *   (tasklet)        (chunk, 3 hilos)              (tasklet)              |
 *                                                      generarResumenDiarioStep (tasklet)
 * </pre>
 */
@Configuration
public class TransaccionesDiariasJobConfig {

    private final ConstructorDePasos constructor;

    public TransaccionesDiariasJobConfig(ConstructorDePasos constructor) {
        this.constructor = constructor;
    }

    /**
     * El Job es la unidad que el JobLauncher ejecuta y que el JobRepository registra: cada
     * corrida queda como una JobExecution con sus parametros y su estado.
     */
    @Bean
    public Job reporteTransaccionesDiariasJob(ArmadorDeJobs armador,
                                              Step procesarTransaccionesStep,
                                              Step generarResumenDiarioStep) {
        return armador.jobDeMigracion(Constantes.JOB_TRANSACCIONES_DIARIAS,
                procesarTransaccionesStep, generarResumenDiarioStep);
    }

    @Bean
    public Step procesarTransaccionesStep(FlatFileItemReader<TransaccionCsv> lectorTransacciones,
                                          TransaccionItemProcessor procesadorTransacciones,
                                          JpaItemWriter<Transaccion> escritorTransacciones,
                                          RegistroRechazadoSkipListener<TransaccionCsv, Transaccion> oyenteRechazoTransacciones,
                                          Partitioner particionadorTransacciones) {
        return constructor.pasoDeMigracion(Constantes.STEP_PROCESAR_TRANSACCIONES,
                lectorTransacciones, procesadorTransacciones, escritorTransacciones, oyenteRechazoTransacciones,
                particionadorTransacciones);
    }

    @Bean
    public Step generarResumenDiarioStep(ResumenDiarioTasklet resumenDiarioTasklet) {
        return constructor.pasoDeTarea(Constantes.STEP_RESUMEN_DIARIO, resumenDiarioTasklet);
    }

    /**
     * El lector es {@code @StepScope} porque sus dos entradas solo existen cuando hay una
     * ejecucion concreta: la carpeta llega como parametro del Job, y el rango de filas llega
     * como contexto de la particion. Cuando la estrategia no es particionada esas claves no
     * existen, llegan como {@code null} y el lector recorre el archivo completo.
     */
    @Bean
    @StepScope
    public FlatFileItemReader<TransaccionCsv> lectorTransacciones(
            @Value("#{jobParameters['" + Constantes.PARAM_ENTRADA + "']}") String carpetaEntrada,
            @Value("#{stepExecutionContext['" + Constantes.PARTICION_INICIO + "']}") Long inicio,
            @Value("#{stepExecutionContext['" + Constantes.PARTICION_FIN + "']}") Long fin) {
        return LectoresCsv.deTransacciones(Path.of(carpetaEntrada, Constantes.ARCHIVO_TRANSACCIONES),
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
    public Partitioner particionadorTransacciones(
            @Value("#{jobParameters['" + Constantes.PARAM_ENTRADA + "']}") String carpetaEntrada) {
        return new ParticionadorPorRangoDeLineas(
                Path.of(carpetaEntrada, Constantes.ARCHIVO_TRANSACCIONES), Constantes.STEP_PROCESAR_TRANSACCIONES, 1);
    }

    @Bean
    public JpaItemWriter<Transaccion> escritorTransacciones(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<Transaccion>()
                .entityManagerFactory(entityManagerFactory)
                // persist() en vez de merge(): las filas migradas siempre son nuevas.
                .usePersist(true)
                .build();
    }

    @Bean
    @StepScope
    public RegistroRechazadoSkipListener<TransaccionCsv, Transaccion> oyenteRechazoTransacciones(
            RegistroRechazadoService bitacora,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return new RegistroRechazadoSkipListener<>(Constantes.JOB_TRANSACCIONES_DIARIAS,
                Constantes.ARCHIVO_TRANSACCIONES, bitacora, jobExecutionId);
    }
}
