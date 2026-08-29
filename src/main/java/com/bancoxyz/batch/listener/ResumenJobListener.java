package com.bancoxyz.batch.listener;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import com.bancoxyz.repository.RegistroRechazadoRepository;
import com.bancoxyz.service.RegistroRechazadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Imprime al cierre de cada Job el cuadro de control de la migracion.
 *
 * <p>Los contadores de Spring Batch quedan en las tablas {@code BATCH_STEP_EXECUTION},
 * pero el operador que lanza la migracion de madrugada necesita verlos en la consola sin
 * abrir la base de datos. Este resumen es, ademas, la evidencia de ejecucion que se
 * adjunta en el informe.</p>
 */
@Component
public class ResumenJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ResumenJobListener.class);
    private static final String LINEA = "=".repeat(104);
    private static final String SEPARADOR = "-".repeat(104);

    private final RegistroRechazadoRepository rechazados;
    private final PropiedadesBatch propiedades;

    public ResumenJobListener(RegistroRechazadoRepository rechazados, PropiedadesBatch propiedades) {
        this.rechazados = rechazados;
        this.propiedades = propiedades;
    }

    @Override
    public void beforeJob(JobExecution ejecucion) {
        log.info("");
        log.info(LINEA);
        log.info("  BANCO XYZ | MIGRACION BATCH -> {}", ejecucion.getJobInstance().getJobName());
        log.info("  Parametros: {}", parametrosCompactos(ejecucion));
        log.info("  Escalado:   estrategia={} | chunk={} items | {} particiones sobre {} hilos | {} hilos de flujos",
                propiedades.getEstrategia(), propiedades.getTamanoChunk(),
                propiedades.getParticiones(), propiedades.getHilosDeParticiones(),
                propiedades.getHilosDeFlujos());
        log.info(LINEA);
    }

    @Override
    public void afterJob(JobExecution ejecucion) {
        long omitidos = rechazados.countByJobExecutionIdAndClasificacion(
                ejecucion.getId(), RegistroRechazadoService.CLASIFICACION_OMITIDO);
        long filtrados = rechazados.countByJobExecutionIdAndClasificacion(
                ejecucion.getId(), RegistroRechazadoService.CLASIFICACION_FILTRADO);

        log.info("");
        log.info(LINEA);
        log.info("  RESUMEN DE EJECUCION -> {}", ejecucion.getJobInstance().getJobName());
        log.info("  jobExecutionId={}  jobInstanceId={}  estado={}  salida={}  duracion={}",
                ejecucion.getId(), ejecucion.getJobInstance().getInstanceId(), ejecucion.getStatus(),
                ejecucion.getExitStatus().getExitCode(),
                duracion(ejecucion.getStartTime(), ejecucion.getEndTime()));
        log.info(SEPARADOR);
        log.info(String.format("  %-45s %8s %9s %9s %10s %10s %9s %10s  %s",
                "STEP", "LEIDOS", "ESCRITOS", "OMITIDOS", "FILTRADOS", "ROLLBACKS",
                "COMMITS", "ITEMS/S", "ESTADO"));

        // Los Steps se imprimen en dos niveles. Arriba los que forman el flujo del Job; debajo
        // de cada Step gestor, indentadas, sus particiones. El total se acumula SOLO sobre el
        // primer nivel: los contadores de un gestor ya son la suma de los de sus particiones,
        // de modo que sumar ambos daria el doble de filas leidas.
        long totalLeidos = 0;
        for (StepExecution step : ejecucion.getStepExecutions()) {
            if (step.getStepName().contains(Constantes.SUFIJO_PARTICION)) {
                continue;
            }
            long leidosDelPaso = step.getReadCount() + step.getReadSkipCount();
            totalLeidos += leidosDelPaso;
            log.info(lineaDePaso(step.getStepName(), step, leidosDelPaso));

            for (StepExecution particion : particionesDe(ejecucion, step.getStepName())) {
                log.info(lineaDePaso("  \\_ " + particion.getStepName(), particion,
                        particion.getReadCount() + particion.getReadSkipCount()));
            }
        }
        log.info(SEPARADOR);
        log.info("  Rendimiento global: {} filas leidas en {} -> {}",
                totalLeidos,
                duracion(ejecucion.getStartTime(), ejecucion.getEndTime()),
                rendimiento(totalLeidos, ejecucion.getStartTime(), ejecucion.getEndTime()));
        log.info("  Bitacora de rechazos: {} registros (omitidos por excepcion={}, filtrados por duplicado={})",
                omitidos + filtrados, omitidos, filtrados);
        if (!ejecucion.getAllFailureExceptions().isEmpty()) {
            log.error("  Fallas registradas: {}", ejecucion.getAllFailureExceptions());
        }
        log.info(LINEA);
        log.info("");
    }

    /** Los JobParameters en su toString() ocupan tres lineas: aqui se resumen a clave=valor. */
    private static String parametrosCompactos(JobExecution ejecucion) {
        return ejecucion.getJobParameters().getParameters().entrySet().stream()
                .map(entrada -> entrada.getKey() + "=" + entrada.getValue().getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String lineaDePaso(String etiqueta, StepExecution step, long leidos) {
        return String.format("  %-45s %8d %9d %9d %10d %10d %9d %10s  %s",
                etiqueta,
                leidos,
                step.getWriteCount(),
                step.getSkipCount(),
                step.getFilterCount(),
                // Un rollback es un chunk revertido: dato sucio omitido o reintento.
                step.getRollbackCount(),
                step.getCommitCount(),
                rendimiento(leidos, step.getStartTime(), step.getEndTime()),
                step.getStatus());
    }

    /** Las particiones de un Step gestor, en el orden en que las nombro Spring Batch. */
    private static java.util.List<StepExecution> particionesDe(JobExecution ejecucion, String gestor) {
        String prefijo = gestor + Constantes.SUFIJO_WORKER + Constantes.SUFIJO_PARTICION;
        return ejecucion.getStepExecutions().stream()
                .filter(paso -> paso.getStepName().startsWith(prefijo))
                .sorted(java.util.Comparator.comparing(StepExecution::getStepName))
                .toList();
    }

    /**
     * Filas por segundo del paso o del Job. Es la metrica con la que se compara una corrida
     * secuencial contra una paralela: sin ella el log solo dice cuanto se proceso, no si el
     * cambio de configuracion sirvio de algo.
     */
    private static String rendimiento(long filas, LocalDateTime inicio, LocalDateTime fin) {
        if (filas == 0 || inicio == null || fin == null) {
            return "-";
        }
        long ms = Math.max(1, Duration.between(inicio, fin).toMillis());
        return String.format(java.util.Locale.ROOT, "%.0f", filas * 1000.0 / ms);
    }

    private static String duracion(LocalDateTime inicio, LocalDateTime fin) {
        if (inicio == null || fin == null) {
            return "n/d";
        }
        return Duration.between(inicio, fin).toMillis() + " ms";
    }
}
