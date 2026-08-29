package com.bancoxyz.batch.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de deduplicacion compartido por todas las particiones de una misma corrida.
 *
 * <h2>Por que el particionado obliga a esta clase</h2>
 * <p>Hasta la semana 2 cada {@code ItemProcessor} llevaba sus propios {@link DetectorDeDuplicados}
 * como campos. Funcionaba porque el procesador es {@code @StepScope}: habia una instancia por
 * ejecucion del Step, y el Step era uno solo aunque tuviera varios hilos.</p>
 *
 * <p>Con particiones eso deja de ser cierto y falla en silencio. Cada particion es un
 * {@code StepExecution} distinto, de modo que Spring crea <b>un procesador por particion</b> y
 * cada uno arranca con sus mapas vacios. Un duplicado repartido entre dos particiones —la fila
 * 100 en la particion 0 y su copia en la fila 800, en la particion 2— dejaria de detectarse: las
 * dos particiones verian su clave por primera vez y las dos la darian por buena. En
 * {@code intereses.csv} eso significa liquidar dos veces la misma cuenta, que es un error
 * contable, no un detalle.</p>
 *
 * <h2>Por que es un singleton y no un bean {@code @JobScope}</h2>
 * <p>Lo natural seria marcar esta clase {@code @JobScope}: una instancia por corrida, compartida
 * por todas sus particiones. <b>No funciona.</b> El ambito {@code job} de Spring Batch se apoya
 * en un contexto ligado al hilo, y {@code TaskExecutorPartitionHandler} lanza cada particion en
 * un hilo del pool al que ese contexto nunca se propaga. El primer item que procesa una particion
 * revienta con {@code ScopeNotActiveException: Scope 'job' is not active for the current thread}.
 * Es un detalle facil de pasar por alto porque el codigo compila, el contexto de Spring arranca
 * sin quejarse y el fallo solo aparece al ejecutar con particiones.</p>
 *
 * <p>La solucion es un singleton que particiona su propio estado por {@code jobExecutionId}. Al
 * no depender de ningun ambito, funciona desde cualquier hilo —el principal, los de chunk y los
 * de particion— y sigue aislando corridas distintas entre si. El estado se libera al terminar el
 * Job, para lo cual esta clase es tambien {@link JobExecutionListener}.</p>
 *
 * <p>Todo lo de adentro es concurrente porque las particiones corren a la vez: aqui llegan
 * llamadas de varios hilos sobre las mismas claves, y es {@link ConcurrentHashMap#putIfAbsent}
 * —atomico— lo que garantiza que exactamente una gane.</p>
 */
@Component
public class RegistroDeDuplicados implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeDuplicados.class);

    /** Estado de una corrida: sus detectores y las filas que ya quedaron en la bitacora. */
    private record EstadoDeCorrida(Map<String, DetectorDeDuplicados> detectores,
                                   Set<String> yaEnBitacora) {
        static EstadoDeCorrida nuevo() {
            return new EstadoDeCorrida(new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
        }
    }

    private final Map<Long, EstadoDeCorrida> porCorrida = new ConcurrentHashMap<>();

    /**
     * @param jobExecutionId corrida a la que pertenece la fila; aisla corridas simultaneas
     * @param detector proposito de la comprobacion, por ejemplo {@code "transacciones.id"}
     * @param clave clave de negocio del registro
     * @param numeroLinea linea del archivo de la que salio la fila
     * @return {@code true} si la clave ya aparecio en <em>otra</em> linea del archivo
     */
    public boolean esDuplicado(Long jobExecutionId, String detector, String clave, int numeroLinea) {
        return estado(jobExecutionId).detectores()
                .computeIfAbsent(detector, nombre -> new DetectorDeDuplicados())
                .esDuplicado(clave, numeroLinea);
    }

    /**
     * @return {@code true} la primera vez que se pregunta por esa fila, {@code false} despues.
     *         Sirve para escribir en la bitacora una sola vez aunque la fila se reprocese tras
     *         revertirse su chunk.
     */
    public boolean primeraVezEnBitacora(Long jobExecutionId, String archivo, int numeroLinea) {
        // La clave incluye el archivo y no solo la linea porque el cierre nocturno completo
        // procesa los tres archivos dentro de la misma corrida: sin el archivo, la linea 42 de
        // transacciones.csv taparia a la linea 42 de intereses.csv.
        return estado(jobExecutionId).yaEnBitacora().add(archivo + ":" + numeroLinea);
    }

    /** Expuesto para las pruebas: claves distintas registradas por un detector. */
    public int clavesRegistradas(Long jobExecutionId, String detector) {
        DetectorDeDuplicados detectorDeClaves = estado(jobExecutionId).detectores().get(detector);
        return detectorDeClaves == null ? 0 : detectorDeClaves.clavesRegistradas();
    }

    /** El estado de una corrida se libera al terminar: si no, crece durante toda la vida del proceso. */
    @Override
    public void afterJob(JobExecution ejecucion) {
        EstadoDeCorrida liberado = porCorrida.remove(ejecucion.getId());
        if (liberado != null) {
            log.debug("Estado de deduplicacion liberado para la corrida {} ({} detectores)",
                    ejecucion.getId(), liberado.detectores().size());
        }
    }

    /** {@code null} solo llega desde pruebas unitarias que instancian el procesador a mano. */
    private EstadoDeCorrida estado(Long jobExecutionId) {
        return porCorrida.computeIfAbsent(jobExecutionId == null ? -1L : jobExecutionId,
                id -> EstadoDeCorrida.nuevo());
    }
}
