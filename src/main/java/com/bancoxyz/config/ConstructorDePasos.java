package com.bancoxyz.config;

import com.bancoxyz.batch.listener.MedidorDeRendimientoListener;
import com.bancoxyz.batch.listener.ReintentoListener;
import com.bancoxyz.batch.listener.ResumenDeParticionesListener;
import com.bancoxyz.batch.policy.PoliticaOmisionBancaria;
import com.bancoxyz.batch.policy.PoliticaReintentoBancaria;
import com.bancoxyz.batch.reader.LectoresCsv;
import com.bancoxyz.batch.writer.EscritorConFalloSimulado;
import com.bancoxyz.common.Constantes;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.SimpleStepExecutionSplitter;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Arma los Steps de la migracion con la estrategia de escalado configurada y la misma politica
 * de tolerancia a fallos.
 *
 * <p>Los tres procesos del Banco XYZ leen archivos distintos pero necesitan exactamente el
 * mismo comportamiento frente al error y frente a la carga. Concentrarlo aqui evita que cada
 * Job termine con su propia variante de la politica, y evita el olvido peligroso: si un Job
 * pudiera declarar su propio Step, podria armarlo mal y esa omision no daria error de
 * compilacion, solo filas corruptas de vez en cuando.</p>
 *
 * <h2>Las tres estrategias, detras de una propiedad</h2>
 * <p>{@code banco.batch.estrategia} decide como se reparte el trabajo, sin recompilar:</p>
 *
 * <table border="1">
 *   <caption>Que arma cada estrategia</caption>
 *   <tr><th>Estrategia</th><th>Forma del Step</th><th>Lector</th></tr>
 *   <tr><td>{@code SECUENCIAL}</td>
 *       <td>un Step de chunk, un hilo</td>
 *       <td>archivo completo</td></tr>
 *   <tr><td>{@code MULTIHILO}</td>
 *       <td>un Step de chunk con {@code taskExecutor} de N hilos</td>
 *       <td>archivo completo, <b>sincronizado</b></td></tr>
 *   <tr><td>{@code PARTICIONADO}</td>
 *       <td>un Step gestor + N Steps trabajadores, uno por particion</td>
 *       <td>un lector por particion, acotado a su rango</td></tr>
 * </table>
 *
 * <p>Ninguna de las tres guarda la posicion del lector: la reanudacion se resuelve rehaciendo el
 * paso sobre una base que {@code LimpiezaDeReintentoTasklet} dejo limpia. Ver el Javadoc de
 * {@code LectoresCsv}, que explica por que las dos estrategias de reanudacion no son
 * acumulables.</p>
 *
 *
 * <h2>Donde van las politicas</h2>
 * <p>La omision, el reintento y el backoff se configuran en el Step <b>trabajador</b>, no en el
 * gestor. El gestor no lee ni escribe: solo reparte particiones y espera. Ponerle a el la
 * politica de omision no tendria efecto —nunca ve un item— y dejaria a las particiones sin
 * tolerancia a fallos, de modo que la primera fila sucia mataria su particion entera.</p>
 *
 * <h2>Cuidado con el orden de la cadena</h2>
 * <p>{@code taskExecutor(...)} esta declarado en {@code AbstractTaskletStepBuilder<B>} y devuelve
 * {@code B}, que para un {@code FaultTolerantStepBuilder} sigue siendo {@code SimpleStepBuilder}.
 * Llamarlo antes que {@code skipPolicy()} o {@code retryPolicy()} degrada el tipo estatico y esas
 * llamadas dejan de compilar. Por eso va al final.</p>
 */
@Component
public class ConstructorDePasos {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final PropiedadesBatch propiedades;
    private final ThreadPoolTaskExecutor ejecutorDePasos;
    private final ThreadPoolTaskExecutor ejecutorDeParticiones;

    public ConstructorDePasos(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              PropiedadesBatch propiedades,
                              @Qualifier("ejecutorDePasos") ThreadPoolTaskExecutor ejecutorDePasos,
                              @Qualifier("ejecutorDeParticiones") ThreadPoolTaskExecutor ejecutorDeParticiones) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.propiedades = propiedades;
        this.ejecutorDePasos = ejecutorDePasos;
        this.ejecutorDeParticiones = ejecutorDeParticiones;
    }

    /**
     * Step de migracion, armado segun la estrategia configurada.
     *
     * @param nombre nombre del Step tal como lo ve el operador y como queda en los metadatos
     * @param lector origen de datos legacy; con particiones, ya viene acotado a su rango
     * @param procesador validaciones y transformaciones
     * @param escritor destino relacional
     * @param oyenteOmision listener que registra en bitacora cada fila descartada
     * @param particionador reparte el archivo; solo se usa con {@code PARTICIONADO}
     */
    public <I, O> Step pasoDeMigracion(String nombre,
                                       ItemStreamReader<I> lector,
                                       ItemProcessor<I, O> procesador,
                                       ItemWriter<O> escritor,
                                       SkipListener<I, O> oyenteOmision,
                                       Partitioner particionador) {

        return switch (propiedades.getEstrategia()) {
            case SECUENCIAL -> pasoDeChunk(nombre, lector, procesador, escritor, oyenteOmision, null);
            case MULTIHILO -> pasoDeChunk(nombre, lector, procesador, escritor, oyenteOmision, ejecutorDePasos);
            case PARTICIONADO -> pasoParticionado(nombre, lector, procesador, escritor, oyenteOmision, particionador);
        };
    }

    /**
     * Step orientado a chunk. Con {@code ejecutorDeChunks} distinto de {@code null} reparte sus
     * chunks entre varios hilos; con {@code null} los procesa de a uno.
     */
    private <I, O> Step pasoDeChunk(String nombre,
                                    ItemStreamReader<I> lector,
                                    ItemProcessor<I, O> procesador,
                                    ItemWriter<O> escritor,
                                    SkipListener<I, O> oyenteOmision,
                                    ThreadPoolTaskExecutor ejecutorDeChunks) {
        return pasoDeChunk(nombre, lector, procesador, escritor, oyenteOmision, ejecutorDeChunks, true);
    }

    /**
     * @param conLimiteDeArranques {@code false} para el Step trabajador de una particion, donde
     *        {@code startLimit} no tiene efecto porque las particiones no pasan por el control
     *        de {@code SimpleStepHandler}: alli el tope lo lleva el gestor.
     */
    private <I, O> Step pasoDeChunk(String nombre,
                                    ItemStreamReader<I> lector,
                                    ItemProcessor<I, O> procesador,
                                    ItemWriter<O> escritor,
                                    SkipListener<I, O> oyenteOmision,
                                    ThreadPoolTaskExecutor ejecutorDeChunks,
                                    boolean conLimiteDeArranques) {

        boolean multihilo = ejecutorDeChunks != null;
        int hilos = multihilo ? propiedades.getHilosPorPaso() : 1;

        MedidorDeRendimientoListener medidor = new MedidorDeRendimientoListener(
                nombre, propiedades.getTamanoChunk(), hilos);

        // El tipo declarado es SimpleStepBuilder y no FaultTolerantStepBuilder porque
        // listener(StepExecutionListener) esta declarado en StepBuilderHelper<B> y para esta
        // jerarquia B es SimpleStepBuilder: la ultima llamada de la cadena degrada el tipo
        // estatico. El objeto en tiempo de ejecucion sigue siendo el fault-tolerant, asi que
        // build() despacha al suyo y las politicas se aplican igual.
        SimpleStepBuilder<I, O> constructor = new StepBuilder(nombre, jobRepository)
                .<I, O>chunk(propiedades.getTamanoChunk(), transactionManager)
                // El lector solo se serializa cuando de verdad lo comparten varios hilos. Con
                // una particion por hilo no hace falta, y envolverlo igual seria pagar un
                // candado por nada en el camino critico.
                .reader(multihilo ? LectoresCsv.sincronizado(lector) : lector)
                .processor(procesador)
                .writer(new EscritorConFalloSimulado<>(escritor, propiedades.isSimularFalloTransitorio()))
                .faultTolerant()
                // Omision: solo el dato sucio, y con tope. Ver PoliticaOmisionBancaria.
                .skipPolicy(new PoliticaOmisionBancaria(propiedades.getLimiteOmisiones()))
                // Reintento: solo el fallo tecnico, con espera creciente. Ver PoliticaReintentoBancaria.
                .retryPolicy(PoliticaReintentoBancaria.politica(propiedades))
                .backOffPolicy(PoliticaReintentoBancaria.backoff(propiedades))
                .listener(oyenteOmision)
                .listener(new ReintentoListener(nombre))
                .listener((org.springframework.batch.core.ChunkListener) medidor)
                .listener((org.springframework.batch.core.StepExecutionListener) medidor);

        // taskExecutor degrada el tipo del builder (ver Javadoc de la clase), asi que va ultimo
        // y a partir de aqui solo quedan los metodos comunes.
        SimpleStepBuilder<I, O> conEjecutor = multihilo
                ? constructor.taskExecutor(ejecutorDeChunks)
                : constructor;

        return conEjecutor
                // Al reanudar, el paso se rehace completo sobre una base que LimpiezaDeReintento
                // Tasklet dejo limpia. Es la otra mitad del pacto que explica LectoresCsv: o se
                // guarda la posicion y se retoma, o se rehace y se limpia, pero nunca las dos.
                .allowStartIfComplete(true)
                .startLimit(conLimiteDeArranques
                        ? propiedades.getLimiteReejecuciones()
                        : Integer.MAX_VALUE)
                .build();
    }

    /**
     * Step gestor que reparte el archivo en particiones y las ejecuta en paralelo.
     *
     * <p>El gestor no procesa nada: le pide al {@link Partitioner} un contexto por particion,
     * lanza una ejecucion del Step trabajador por cada uno sobre el pool de particiones, espera
     * a que terminen y agrega sus contadores. El trabajo real —leer, validar, escribir— ocurre
     * dentro de cada particion, con la misma configuracion de chunk y las mismas politicas de
     * omision y reintento que tendria un Step suelto.</p>
     *
     * <p>El Step trabajador se llama igual que el gestor con el sufijo {@code Worker}, y sus
     * ejecuciones quedan en los metadatos como {@code <worker>:particionN}. Ese detalle importa
     * para leer el historial: en {@code BATCH_STEP_EXECUTION} aparece una fila por particion
     * ademas de la del gestor, y los contadores del gestor son la suma de las suyas.</p>
     */
    private <I, O> Step pasoParticionado(String nombre,
                                         ItemStreamReader<I> lector,
                                         ItemProcessor<I, O> procesador,
                                         ItemWriter<O> escritor,
                                         SkipListener<I, O> oyenteOmision,
                                         Partitioner particionador) {

        String nombreTrabajador = nombre + Constantes.SUFIJO_WORKER;
        // Sin ejecutor de chunks: dentro de una particion se procesa con un solo hilo. El
        // paralelismo ya lo aporta el gestor al correr varias particiones a la vez, y sumar
        // hilos dentro de cada una solo devolveria los problemas que el particionado resuelve
        // (lector compartido y posicion de lectura sin sentido).
        Step trabajador = pasoDeChunk(nombreTrabajador, lector, procesador, escritor, oyenteOmision,
                null, false);

        return new StepBuilder(nombre, jobRepository)
                .partitioner(nombreTrabajador, particionador)
                .step(trabajador)
                .gridSize(propiedades.getParticiones())
                .taskExecutor(ejecutorDeParticiones)
                // El splitter se construye a mano solo para poder pasarle allowStartIfComplete.
                // El que deriva el builder lo deja en false, y entonces una reanudacion se
                // saltaria las particiones que ya habian terminado bien... despues de que la
                // limpieza previa borrara sus filas. La corrida terminaria en COMPLETED con
                // parte de los datos perdidos, que es exactamente el fallo silencioso que esta
                // migracion existe para evitar.
                .splitter(new SimpleStepExecutionSplitter(
                        jobRepository, true, nombreTrabajador, particionador))
                .listener(new ResumenDeParticionesListener(nombre))
                .allowStartIfComplete(true)
                // startLimit va aqui y no en el trabajador: el control lo hace SimpleStepHandler
                // al recorrer el flujo del Job, y las particiones no pasan por ahi —las lanza
                // directamente el PartitionHandler—, de modo que en el trabajador no tendria
                // ningun efecto.
                .startLimit(propiedades.getLimiteReejecuciones())
                .build();
    }

    /**
     * Step de tarea unica: agregaciones y generacion de archivos de salida.
     *
     * <p>Va con {@code allowStartIfComplete(true)} a proposito. Son pasos idempotentes que
     * recalculan a partir de lo que hay en la base, de modo que al reanudar conviene rehacerlos:
     * el reporte tiene que reflejar los datos nuevos que trajo el reintento, no los de la
     * corrida que fallo.</p>
     */
    public Step pasoDeTarea(String nombre, Tasklet tarea) {
        return new StepBuilder(nombre, jobRepository)
                .tasklet(tarea, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }
}
