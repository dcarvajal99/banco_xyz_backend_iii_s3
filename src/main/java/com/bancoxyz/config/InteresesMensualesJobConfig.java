package com.bancoxyz.config;

import com.bancoxyz.batch.listener.RegistroRechazadoSkipListener;
import com.bancoxyz.batch.processor.InteresItemProcessor;
import com.bancoxyz.batch.partition.ParticionadorPorRangoDeLineas;
import com.bancoxyz.batch.reader.LectoresCsv;
import com.bancoxyz.batch.tasklet.ResumenInteresesTasklet;
import com.bancoxyz.common.Constantes;
import com.bancoxyz.dto.InteresCsv;
import com.bancoxyz.entity.CuentaInteres;
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
 * Job 2 — Calculo de intereses mensuales.
 *
 * <p>Reemplaza el shell script que aplicaba las tasas sobre el maestro de cuentas. Migra
 * {@code intereses.csv}, aplica la tasa que corresponde a cada producto, deja actualizado
 * el saldo final en la base de datos y exporta el detalle liquidado.</p>
 *
 * <p>Desde la semana 2 el paso de migracion corre en paralelo (tres hilos, chunks de cinco)
 * y el flujo pasa por el decisor de calidad antes de liquidar. Ver {@link ArmadorDeJobs}.</p>
 *
 * <pre>
 *   limpieza -> procesarInteresesStep -> exportarRechazadosStep -> [decisor]
 *   (tasklet)      (chunk, 3 hilos)            (tasklet)              |
 *                                                  generarResumenInteresesStep (tasklet)
 * </pre>
 */
@Configuration
public class InteresesMensualesJobConfig {

    private final ConstructorDePasos constructor;

    public InteresesMensualesJobConfig(ConstructorDePasos constructor) {
        this.constructor = constructor;
    }

    @Bean
    public Job calculoInteresesMensualesJob(ArmadorDeJobs armador,
                                            Step procesarInteresesStep,
                                            Step generarResumenInteresesStep) {
        return armador.jobDeMigracion(Constantes.JOB_INTERESES_MENSUALES,
                procesarInteresesStep, generarResumenInteresesStep);
    }

    @Bean
    public Step procesarInteresesStep(FlatFileItemReader<InteresCsv> lectorIntereses,
                                      InteresItemProcessor procesadorIntereses,
                                      JpaItemWriter<CuentaInteres> escritorIntereses,
                                      RegistroRechazadoSkipListener<InteresCsv, CuentaInteres> oyenteRechazoIntereses,
                                      Partitioner particionadorIntereses) {
        return constructor.pasoDeMigracion(Constantes.STEP_PROCESAR_INTERESES,
                lectorIntereses, procesadorIntereses, escritorIntereses, oyenteRechazoIntereses,
                particionadorIntereses);
    }

    @Bean
    public Step generarResumenInteresesStep(ResumenInteresesTasklet resumenInteresesTasklet) {
        return constructor.pasoDeTarea(Constantes.STEP_RESUMEN_INTERESES, resumenInteresesTasklet);
    }

    /**
     * El lector es {@code @StepScope} porque sus dos entradas solo existen cuando hay una
     * ejecucion concreta: la carpeta llega como parametro del Job, y el rango de filas llega
     * como contexto de la particion. Cuando la estrategia no es particionada esas claves no
     * existen, llegan como {@code null} y el lector recorre el archivo completo.
     */
    @Bean
    @StepScope
    public FlatFileItemReader<InteresCsv> lectorIntereses(
            @Value("#{jobParameters['" + Constantes.PARAM_ENTRADA + "']}") String carpetaEntrada,
            @Value("#{stepExecutionContext['" + Constantes.PARTICION_INICIO + "']}") Long inicio,
            @Value("#{stepExecutionContext['" + Constantes.PARTICION_FIN + "']}") Long fin) {
        return LectoresCsv.deIntereses(Path.of(carpetaEntrada, Constantes.ARCHIVO_INTERESES),
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
    public Partitioner particionadorIntereses(
            @Value("#{jobParameters['" + Constantes.PARAM_ENTRADA + "']}") String carpetaEntrada) {
        return new ParticionadorPorRangoDeLineas(
                Path.of(carpetaEntrada, Constantes.ARCHIVO_INTERESES), Constantes.STEP_PROCESAR_INTERESES, 1);
    }

    @Bean
    public JpaItemWriter<CuentaInteres> escritorIntereses(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<CuentaInteres>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(true)
                .build();
    }

    @Bean
    @StepScope
    public RegistroRechazadoSkipListener<InteresCsv, CuentaInteres> oyenteRechazoIntereses(
            RegistroRechazadoService bitacora,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return new RegistroRechazadoSkipListener<>(Constantes.JOB_INTERESES_MENSUALES,
                Constantes.ARCHIVO_INTERESES, bitacora, jobExecutionId);
    }
}
