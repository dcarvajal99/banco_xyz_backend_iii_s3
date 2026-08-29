package com.bancoxyz.batch.listener;

import com.bancoxyz.common.Constantes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Informa como quedo el reparto real entre las particiones de un Step particionado.
 *
 * <p>El gestor de particiones deja en sus contadores la <em>suma</em> de lo que hicieron sus
 * particiones, y con eso basta para saber cuanto se migro. Lo que no dice es lo unico que
 * permite ajustar el {@code gridSize}: si el trabajo quedo repartido o si una particion cargo
 * con casi todo mientras las demas terminaban temprano y se quedaban esperando.</p>
 *
 * <p>Ese desbalance es el que fija el tiempo total, porque un Step particionado no termina
 * hasta que termina su particion mas lenta. Por eso el resumen informa, ademas del detalle, el
 * <b>desbalance</b>: cuanto mas tardo la particion mas lenta respecto del promedio. Un
 * desbalance alto con particiones del mismo tamano significa que el reparto por filas no
 * representa bien el costo real —por ejemplo, porque un tramo del archivo trae mucho mas dato
 * sucio y por lo tanto muchos mas rollbacks— y que conviene revisar el criterio de particion
 * antes que subir el numero de particiones.</p>
 */
public class ResumenDeParticionesListener implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ResumenDeParticionesListener.class);

    /* Claves con las que el resumen queda persistido en BATCH_STEP_EXECUTION_CONTEXT. */
    public static final String CTX_PARTICIONES = "banco.particionesEjecutadas";
    public static final String CTX_DESBALANCE = "banco.desbalanceParticiones";

    private final String nombreGestor;

    public ResumenDeParticionesListener(String nombreGestor) {
        this.nombreGestor = nombreGestor;
    }

    @Override
    public ExitStatus afterStep(StepExecution gestor) {
        List<StepExecution> particiones = particionesDe(gestor);
        if (particiones.isEmpty()) {
            return gestor.getExitStatus();
        }

        log.info("[{}] particiones | {} ejecutadas, {} filas leidas en total",
                nombreGestor, particiones.size(),
                gestor.getReadCount() + gestor.getReadSkipCount());

        long maxMs = 0;
        long totalMs = 0;
        for (StepExecution particion : particiones) {
            long ms = duracionMs(particion);
            maxMs = Math.max(maxMs, ms);
            totalMs += ms;
            log.info("[{}]   {} | leidas={} escritas={} omitidas={} rollbacks={} {} ms -> {}",
                    nombreGestor,
                    String.format("%-32s", particion.getStepName()),
                    particion.getReadCount() + particion.getReadSkipCount(),
                    particion.getWriteCount(),
                    particion.getSkipCount(),
                    particion.getRollbackCount(),
                    ms,
                    particion.getStatus());
        }

        double promedioMs = (double) totalMs / particiones.size();
        // 1,00 seria un reparto perfecto; 2,00 significa que la particion mas lenta tardo el
        // doble que el promedio y que la mitad del paralelismo se desperdicio esperandola.
        double desbalance = promedioMs == 0 ? 1.0 : maxMs / promedioMs;

        log.info("[{}] particiones | mas lenta {} ms, promedio {} ms, desbalance {}x{}",
                nombreGestor, maxMs, Math.round(promedioMs),
                String.format(java.util.Locale.ROOT, "%.2f", desbalance),
                desbalance > 1.5 ? "  <-- reparto disparejo: revisar el criterio de particion" : "");

        gestor.getExecutionContext().putInt(CTX_PARTICIONES, particiones.size());
        gestor.getExecutionContext().putString(CTX_DESBALANCE,
                String.format(java.util.Locale.ROOT, "%.2f", desbalance));
        // El gestor tambien lleva la marca de "aqui se leyo del archivo legacy": sus contadores
        // ya vienen agregados desde las particiones, asi que es EL, y no cada particion, el que
        // representa el archivo completo para el decisor de calidad.
        gestor.getExecutionContext().putString(Constantes.CTX_PASO_DE_MIGRACION, nombreGestor);
        gestor.getExecutionContext().putInt(MedidorDeRendimientoListener.CTX_CHUNKS,
                particiones.stream()
                        .mapToInt(p -> p.getExecutionContext()
                                .containsKey(MedidorDeRendimientoListener.CTX_CHUNKS)
                                ? p.getExecutionContext().getInt(MedidorDeRendimientoListener.CTX_CHUNKS)
                                : 0)
                        .sum());

        return gestor.getExitStatus();
    }

    /**
     * Las ejecuciones de las particiones de <em>este</em> gestor. Spring Batch las nombra
     * {@code <stepTrabajador>:particionN}, y todas cuelgan de la misma JobExecution que el
     * gestor, junto con las de los otros Steps del Job.
     */
    private List<StepExecution> particionesDe(StepExecution gestor) {
        String prefijo = nombreGestor + Constantes.SUFIJO_WORKER + ":";
        return gestor.getJobExecution().getStepExecutions().stream()
                .filter(paso -> paso.getStepName().startsWith(prefijo))
                .sorted(Comparator.comparing(StepExecution::getStepName))
                .toList();
    }

    private static long duracionMs(StepExecution paso) {
        if (paso.getStartTime() == null || paso.getEndTime() == null) {
            return 0;
        }
        return Duration.between(paso.getStartTime(), paso.getEndTime()).toMillis();
    }
}
