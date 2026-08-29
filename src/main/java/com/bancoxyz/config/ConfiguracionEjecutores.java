package com.bancoxyz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.repository.ExecutionContextSerializer;
import org.springframework.batch.core.repository.dao.Jackson2ExecutionContextStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pools de hilos con los que la migracion escala.
 *
 * <p>Hay dos niveles de paralelismo y cada uno necesita su propio pool, porque compiten
 * por recursos distintos:</p>
 *
 * <ol>
 *   <li><b>Paralelismo dentro de un Step</b> ({@code ejecutorDePasos}): varios hilos
 *       procesan chunks distintos del <em>mismo</em> archivo. Es el que exige la actividad:
 *       tres hilos con chunks de cinco items. Cada hilo abre su propia transaccion, de modo
 *       que el tamano del pool marca el piso del pool de conexiones JDBC.</li>
 *   <li><b>Paralelismo entre flujos</b> ({@code ejecutorDeFlujos}): el cierre nocturno
 *       completo corre los tres archivos a la vez con un {@code split}. Aqui los hilos no
 *       procesan items, solo coordinan flujos, asi que el pool es chico.</li>
 * </ol>
 *
 * <p>Se usan {@link ThreadPoolTaskExecutor} y no {@code SimpleAsyncTaskExecutor} justamente
 * por el criterio de optimizacion de recursos: {@code SimpleAsyncTaskExecutor} crea un hilo
 * nuevo por tarea y no los reutiliza, de modo que con 200 chunks crearia 200 hilos. El pool
 * mantiene un numero fijo y encola el resto.</p>
 *
 * <h2>Advertencia: estos pools NO deben usarse para lanzar Jobs</h2>
 * <p>La autoconfiguracion de Spring Boot para Spring Batch recibe un
 * {@code ObjectProvider<TaskExecutor>} y, si encuentra <b>uno solo</b> en el contexto, se lo
 * entrega al {@code JobLauncher}. Ese launcher pasaria a ser asincrono: {@code jobLauncher.run()}
 * devolveria de inmediato, con el Job todavia en STARTING, y {@link LanzadorDeJobs} informaria
 * "finalizado" antes de que la migracion hubiera empezado. Aqui no ocurre porque hay dos
 * candidatos y el proveedor no puede elegir, pero eso es una coincidencia y no un diseno: si
 * algun dia se elimina uno de los dos pools, hay que comprobar que el lanzamiento siga siendo
 * sincrono. La prueba {@code EscalamientoParaleloIT.elLanzamientoDeJobsEsSincrono} vigila
 * exactamente eso y fallaria antes de que el problema llegue a una corrida real.</p>
 */
@Configuration
public class ConfiguracionEjecutores {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionEjecutores.class);

    /**
     * Pool que ejecuta los chunks de un mismo Step en paralelo.
     *
     * <p>{@code corePoolSize == maxPoolSize} de forma deliberada: el paralelismo de la
     * migracion debe ser <em>predecible</em>. Si el pool pudiera crecer, la cantidad de
     * transacciones simultaneas contra PostgreSQL variaria segun la carga y el banco
     * perderia el control sobre cuantas conexiones consume el proceso nocturno.</p>
     *
     * <p>{@code destroyMethod = "shutdown"} garantiza que el {@code java -jar} no quede
     * colgado esperando hilos vivos cuando el Job termina.</p>
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor ejecutorDePasos(PropiedadesBatch propiedades) {
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(propiedades.getHilosPorPaso());
        ejecutor.setMaxPoolSize(propiedades.getHilosPorPaso());
        ejecutor.setQueueCapacity(propiedades.getCapacidadCola());
        // El prefijo es lo que hace legible el log: cada linea muestra que hilo la escribio.
        ejecutor.setThreadNamePrefix("batch-chunk-");
        // Si la cola se llena, el hilo que envia la tarea la ejecuta el mismo en vez de
        // descartarla: un chunk perdido seria una perdida de datos silenciosa.
        ejecutor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ejecutor.setWaitForTasksToCompleteOnShutdown(true);
        ejecutor.setAwaitTerminationSeconds(60);
        ejecutor.initialize();

        log.info("Pool de chunks listo: {} hilos fijos, cola de {} tareas, prefijo '{}'",
                propiedades.getHilosPorPaso(), propiedades.getCapacidadCola(), "batch-chunk-");
        return ejecutor;
    }

    /**
     * Pool que ejecuta las particiones de un Step particionado.
     *
     * <p>Es un pool distinto del de chunks a proposito. Sus hilos no procesan items: cada uno
     * conduce un Step completo de particion, con su lector, su transaccion y su contabilidad de
     * errores. Mezclarlos en un mismo pool haria que una particion pudiera quedarse esperando
     * turno detras de un chunk, y el reparto dejaria de ser el que dice la configuracion.</p>
     *
     * <p>El tamano acompana a {@code banco.batch.particiones}: si hubiera menos hilos que
     * particiones, algunas esperarian y el particionado seria en parte secuencial sin que se
     * note en el log. La cola se dimensiona igual que el pool porque las particiones se
     * despachan todas de una vez al empezar el Step.</p>
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor ejecutorDeParticiones(PropiedadesBatch propiedades) {
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(propiedades.getHilosDeParticiones());
        ejecutor.setMaxPoolSize(propiedades.getHilosDeParticiones());
        // La cola tiene que dar cabida a las particiones de LOS TRES flujos del cierre nocturno
        // completo, que despachan las suyas casi a la vez. Si se quedara corta, CallerRunsPolicy
        // haria que el hilo del flujo ejecutara particiones el mismo: no se pierde trabajo, pero
        // el paralelismo real dejaria de ser el que dice la configuracion y la medicion mentiria.
        ejecutor.setQueueCapacity(propiedades.getParticiones() * propiedades.getHilosDeFlujos());
        // El prefijo es lo que hace legible el log: cada linea dice que particion la escribio.
        ejecutor.setThreadNamePrefix("batch-particion-");
        ejecutor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ejecutor.setWaitForTasksToCompleteOnShutdown(true);
        ejecutor.setAwaitTerminationSeconds(120);
        ejecutor.initialize();

        log.info("Pool de particiones listo: {} hilos fijos para {} particiones, prefijo '{}'",
                propiedades.getHilosDeParticiones(), propiedades.getParticiones(), "batch-particion-");
        return ejecutor;
    }

    /**
     * Guarda los metadatos de ejecucion en JSON en vez de la serializacion binaria de Java.
     *
     * <p>Spring Batch guarda por defecto el {@code ExecutionContext} como un objeto Java
     * serializado y codificado en Base64. Funciona, pero convierte
     * {@code BATCH_STEP_EXECUTION_CONTEXT} en una columna ilegible: las metricas de rendimiento
     * que deja {@code MedidorDeRendimientoListener} —hilos que trabajaron, reparto de chunks,
     * items por segundo— quedarian encerradas ahi sin forma de consultarlas por SQL.</p>
     *
     * <p>Con el serializador JSON esas metricas se pueden leer, agrupar y comparar entre
     * corridas con una consulta corriente, que es lo que hace util guardarlas. Es tambien la
     * opcion recomendada por Spring Batch: la serializacion Java esta en retirada.</p>
     *
     * <p><b>Al cambiarlo hay que partir de una base limpia.</b> Los contextos ya guardados con
     * el formato anterior no se pueden leer con este serializador. En una migracion real habria
     * que vaciar las tablas {@code BATCH_*} o convertirlas; aqui la semana 2 estrena su propia
     * base ({@code docker-compose.yml}, puerto 5434), asi que no hay historial que convertir.</p>
     */
    @Bean
    public ExecutionContextSerializer serializadorDeContexto() {
        return new Jackson2ExecutionContextStringSerializer();
    }

    /** Pool que corre en paralelo los tres flujos del cierre nocturno completo. */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor ejecutorDeFlujos(PropiedadesBatch propiedades) {
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(propiedades.getHilosDeFlujos());
        ejecutor.setMaxPoolSize(propiedades.getHilosDeFlujos());
        ejecutor.setQueueCapacity(propiedades.getHilosDeFlujos());
        ejecutor.setThreadNamePrefix("batch-flujo-");
        ejecutor.setWaitForTasksToCompleteOnShutdown(true);
        ejecutor.setAwaitTerminationSeconds(120);
        ejecutor.initialize();

        log.info("Pool de flujos listo: {} hilos fijos, prefijo '{}'",
                propiedades.getHilosDeFlujos(), "batch-flujo-");
        return ejecutor;
    }
}
