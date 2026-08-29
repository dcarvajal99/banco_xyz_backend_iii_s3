package com.bancoxyz.config;

import com.bancoxyz.batch.decider.DecisorCalidadDeDatos;
import com.bancoxyz.batch.listener.ResumenJobListener;
import com.bancoxyz.common.Constantes;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Arma el flujo de los Jobs de migracion, incluida la politica de finalizacion.
 *
 * <p>Los tres Jobs de negocio tienen la misma forma y solo cambian en dos piezas: el Step que
 * migra el archivo y el Step que publica el reporte. Todo lo demas —la limpieza previa, la
 * exportacion de la bitacora, el decisor y sus tres ramas— es identico, y tenerlo escrito una
 * sola vez evita que las politicas de finalizacion se vayan separando entre Jobs con el
 * tiempo.</p>
 *
 * <h2>El flujo</h2>
 * <pre>
 *   limpieza -&gt; migrar -&gt; exportar rechazos -&gt; [decisor de calidad]
 *                                                  |
 *          CALIDAD_ACEPTABLE  ---------------------+--&gt; publicar reporte --&gt; COMPLETED
 *          CALIDAD_DEGRADADA  --&gt; cuarentena aviso ---&gt; publicar reporte --&gt; COMPLETED
 *          CALIDAD_INACEPTABLE --&gt; cuarentena corte -------------------------&gt; FAILED
 * </pre>
 *
 * <p>Dos decisiones de diseno que conviene justificar:</p>
 *
 * <ul>
 *   <li><b>La bitacora se exporta antes de decidir.</b> Es la evidencia de que se descarto, y
 *       se necesita sobre todo cuando la calidad fue mala. Producirla despues del decisor
 *       significaria no tenerla justo en el caso en que hace falta.</li>
 *   <li><b>Ninguna transicion usa {@code on("*")} para terminar bien.</b> El comodin tambien
 *       casa con {@code FAILED}, de modo que un {@code on("*").end()} convertiria un Step
 *       caido en un Job {@code COMPLETED}. Cada nodo declara explicitamente
 *       {@code on("COMPLETED")} para seguir y {@code on("*")} para fallar.</li>
 * </ul>
 */
@Component
public class ArmadorDeJobs {

    private static final String EXITO = ExitStatus.COMPLETED.getExitCode();
    private static final String CUALQUIER_OTRO = "*";

    private final JobRepository jobRepository;
    private final ResumenJobListener resumenJobListener;
    private final DecisorCalidadDeDatos decisor;
    private final Step limpiezaDeReintentoStep;
    private final Step exportarRechazadosStep;
    private final Step cuarentenaAvisoStep;
    private final Step cuarentenaCorteStep;

    public ArmadorDeJobs(JobRepository jobRepository,
                         ResumenJobListener resumenJobListener,
                         DecisorCalidadDeDatos decisor,
                         @Qualifier(Constantes.STEP_LIMPIEZA) Step limpiezaDeReintentoStep,
                         @Qualifier(Constantes.STEP_EXPORTAR_RECHAZADOS) Step exportarRechazadosStep,
                         @Qualifier(Constantes.STEP_CUARENTENA_AVISO) Step cuarentenaAvisoStep,
                         @Qualifier(Constantes.STEP_CUARENTENA_CORTE) Step cuarentenaCorteStep) {
        this.jobRepository = jobRepository;
        this.resumenJobListener = resumenJobListener;
        this.decisor = decisor;
        this.limpiezaDeReintentoStep = limpiezaDeReintentoStep;
        this.exportarRechazadosStep = exportarRechazadosStep;
        this.cuarentenaAvisoStep = cuarentenaAvisoStep;
        this.cuarentenaCorteStep = cuarentenaCorteStep;
    }

    /**
     * @param nombre nombre del Job
     * @param migrar Step orientado a chunk que lee el archivo legacy
     * @param publicar Step que compila y publica el reporte del proceso
     */
    public Job jobDeMigracion(String nombre, Step migrar, Step publicar) {
        return new JobBuilder(nombre, jobRepository)
                .listener(resumenJobListener)
                .start(limpiezaDeReintentoStep)
                .next(migrar)
                .next(exportarRechazadosStep)
                .next(decisor)
                .on(Constantes.CALIDAD_ACEPTABLE).to(publicar)
                .from(decisor).on(Constantes.CALIDAD_DEGRADADA).to(cuarentenaAvisoStep)
                .from(decisor).on(Constantes.CALIDAD_INACEPTABLE).to(cuarentenaCorteStep)
                .from(cuarentenaAvisoStep).on(EXITO).to(publicar)
                .from(cuarentenaAvisoStep).on(CUALQUIER_OTRO).fail()
                // La rama de corte es terminal y termina en fallo a proposito: deja la
                // JobInstance reanudable para cuando el area de datos corrija el origen.
                .from(cuarentenaCorteStep).on(CUALQUIER_OTRO).fail()
                .from(publicar).on(EXITO).end()
                .from(publicar).on(CUALQUIER_OTRO).fail()
                .end()
                .build();
    }
}
