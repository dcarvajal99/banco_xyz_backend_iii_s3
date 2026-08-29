package com.bancoxyz.batch.listener;

import com.bancoxyz.common.Constantes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Instrumenta un Step para poder ajustar su configuracion con datos y no con intuicion.
 *
 * <p>Los contadores que Spring Batch guarda por si solo (leidos, escritos, omitidos) dicen
 * <em>que</em> se proceso, pero no <em>a que ritmo</em> ni <em>con cuantos hilos</em>, que son
 * las dos preguntas que se responden antes de tocar el tamano del chunk, el numero de hilos o
 * el de particiones.</p>
 *
 * <p>Este listener agrega tres cosas al log:</p>
 * <ul>
 *   <li><b>Rendimiento</b>: items por segundo y milisegundos por chunk. Es la metrica que se
 *       compara entre estrategias para saber si el escalado esta rindiendo o si el cuello de
 *       botella se movio a la base de datos.</li>
 *   <li><b>Reparto real entre hilos</b>: cuantos chunks proceso cada hilo. Detecta el caso en
 *       que se configuraron varios hilos pero trabajo uno solo.</li>
 *   <li><b>Configuracion efectiva</b>: el tamano de chunk con el que de verdad corrio el Step,
 *       para que la medicion del log sea interpretable meses despues.</li>
 * </ul>
 *
 * <h2>Por que el estado va por ejecucion y no en campos</h2>
 * <p>Hasta la semana 2 los contadores eran campos de instancia y bastaba con reiniciarlos en
 * {@code beforeStep}: habia un listener por Step y el Step se ejecutaba una vez. Con particiones
 * eso se rompe en silencio. El Step trabajador es <b>un unico objeto</b> que se ejecuta N veces
 * <b>a la vez</b>, una por particion, compartiendo este listener; el {@code beforeStep} de la
 * particion 1 borraria los chunks que la particion 0 lleva contados, y las metricas de las dos
 * saldrian mal sin que nada falle. Por eso el estado se guarda en un mapa concurrente indexado
 * por {@code stepExecution.getId()}: cada particion mide lo suyo y se retira del mapa al
 * terminar.</p>
 */
public class MedidorDeRendimientoListener implements StepExecutionListener, ChunkListener {

    private static final Logger log = LoggerFactory.getLogger(MedidorDeRendimientoListener.class);

    /* Claves con las que las metricas quedan persistidas en BATCH_STEP_EXECUTION_CONTEXT. */
    public static final String CTX_HILOS_USADOS = "banco.hilosUsados";
    public static final String CTX_REPARTO_POR_HILO = "banco.repartoPorHilo";
    public static final String CTX_ITEMS_POR_SEGUNDO = "banco.itemsPorSegundo";
    public static final String CTX_CHUNKS = "banco.chunks";
    public static final String CTX_TAMANO_CHUNK = "banco.tamanoChunk";

    /** Lo que mide una ejecucion concreta del Step: una corrida suelta o una particion. */
    private static final class Medicion {
        private final Map<String, AtomicLong> chunksPorHilo = new ConcurrentHashMap<>();
        private final AtomicLong chunksConError = new AtomicLong();
        private final long inicioNanos = System.nanoTime();
    }

    private final String nombrePaso;
    private final int tamanoChunk;
    private final int hilosConfigurados;

    /** Una medicion por StepExecution viva. Concurrente: las particiones corren a la vez. */
    private final Map<Long, Medicion> mediciones = new ConcurrentHashMap<>();

    public MedidorDeRendimientoListener(String nombrePaso, int tamanoChunk, int hilosConfigurados) {
        this.nombrePaso = nombrePaso;
        this.tamanoChunk = tamanoChunk;
        this.hilosConfigurados = hilosConfigurados;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        mediciones.put(stepExecution.getId(), new Medicion());
        log.info("[{}] inicio | chunk={} items | hilos configurados={}",
                stepExecution.getStepName(), tamanoChunk, hilosConfigurados);
    }

    @Override
    public void beforeChunk(ChunkContext contexto) {
        // Sin accion: el costo del chunk se contabiliza al cerrarlo.
    }

    @Override
    public void afterChunk(ChunkContext contexto) {
        medicionDe(contexto).chunksPorHilo
                .computeIfAbsent(Thread.currentThread().getName(), h -> new AtomicLong())
                .incrementAndGet();
    }

    @Override
    public void afterChunkError(ChunkContext contexto) {
        // Un chunk con error se revierte y se reprocesa item a item: interesa cuantas veces
        // paso, porque es el costo oculto que explica por que una corrida con datos sucios
        // rinde menos que una limpia con el mismo volumen.
        medicionDe(contexto).chunksConError.incrementAndGet();
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        Medicion medicion = mediciones.remove(stepExecution.getId());
        if (medicion == null) {
            return stepExecution.getExitStatus();
        }

        long duracionMs = Math.max(1, (System.nanoTime() - medicion.inicioNanos) / 1_000_000);
        long leidos = stepExecution.getReadCount() + stepExecution.getReadSkipCount();
        long chunks = medicion.chunksPorHilo.values().stream().mapToLong(AtomicLong::get).sum();
        double itemsPorSegundo = leidos * 1000.0 / duracionMs;

        log.info("[{}] rendimiento | {} items en {} ms = {} items/s | {} chunks ({} ms/chunk) | "
                        + "{} chunks revertidos",
                stepExecution.getStepName(), leidos, duracionMs,
                String.format(java.util.Locale.ROOT, "%.1f", itemsPorSegundo),
                chunks,
                chunks == 0 ? "n/d" : String.format(java.util.Locale.ROOT, "%.1f", (double) duracionMs / chunks),
                medicion.chunksConError.get());

        String reparto = repartoLegible(medicion);
        log.info("[{}] reparto entre hilos | {} hilo(s) activo(s) -> {}",
                stepExecution.getStepName(), medicion.chunksPorHilo.size(), reparto);

        // Las metricas se guardan tambien en el ExecutionContext del Step. Spring Batch lo
        // persiste en BATCH_STEP_EXECUTION_CONTEXT, de modo que el rendimiento de cada corrida
        // queda consultable por SQL meses despues. Un log se rota y se pierde; esto no.
        stepExecution.getExecutionContext().putInt(CTX_HILOS_USADOS, medicion.chunksPorHilo.size());
        stepExecution.getExecutionContext().putString(CTX_REPARTO_POR_HILO, reparto);
        stepExecution.getExecutionContext().putString(CTX_ITEMS_POR_SEGUNDO,
                String.format(java.util.Locale.ROOT, "%.1f", itemsPorSegundo));
        stepExecution.getExecutionContext().putInt(CTX_CHUNKS, (int) chunks);
        stepExecution.getExecutionContext().putInt(CTX_TAMANO_CHUNK, tamanoChunk);
        // Marca de "aqui se leyo del archivo legacy". El decisor de calidad la usa para saber
        // que Steps entran en el calculo de la tasa de omision y cuales son Tasklet de
        // agregacion, sin tener que mantener una lista de nombres a mano.
        stepExecution.getExecutionContext().putString(Constantes.CTX_PASO_DE_MIGRACION, nombrePaso);

        // El ExitStatus no se altera: medir no debe cambiar el resultado del Step.
        return stepExecution.getExitStatus();
    }

    private Medicion medicionDe(ChunkContext contexto) {
        return mediciones.computeIfAbsent(
                contexto.getStepContext().getStepExecution().getId(), id -> new Medicion());
    }

    /** Ejemplo: {@code batch-chunk-1=67, batch-chunk-2=67, batch-chunk-3=66}. */
    private static String repartoLegible(Medicion medicion) {
        return medicion.chunksPorHilo.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(e -> e.getKey() + "=" + e.getValue().get())
                .collect(Collectors.joining(", "));
    }

    /** Expuesto para las pruebas: cuantos hilos distintos completaron al menos un chunk. */
    public int hilosQueTrabajaron(Long stepExecutionId) {
        Medicion medicion = mediciones.get(stepExecutionId);
        return medicion == null ? 0 : medicion.chunksPorHilo.size();
    }
}
