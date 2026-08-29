package com.bancoxyz.batch.policy;

import com.bancoxyz.config.PropiedadesBatch;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Politica de reintento de la migracion: que fallos se vuelven a intentar y con que espera.
 *
 * <p>La regla es la simetrica de {@link PoliticaOmisionBancaria}. Aquella decide que errores
 * <em>del dato</em> se omiten; esta decide que errores <em>de la infraestructura</em> se
 * reintentan. La distincion importa: una fila con la fecha ilegible va a seguir ilegible por
 * mucho que se reintente, mientras que un bloqueo de tabla o un timeout de consulta suelen
 * resolverse solos en el segundo intento. Reintentar el dato sucio seria perder tiempo;
 * omitir el fallo de infraestructura seria perder datos buenos.</p>
 *
 * <h2>Por que el backoff exponencial deja de ser opcional al paralelizar</h2>
 * <p>Con un Step monohilo un reintento inmediato funcionaba: si la conexion se recuperaba, el
 * segundo intento pasaba. Con tres hilos escribiendo a la vez, la causa mas probable del
 * error transitorio ya no es la red sino la <b>contencion</b>: dos chunks compitiendo por el
 * mismo bloqueo de PostgreSQL. Reintentar de inmediato reproduce exactamente la misma
 * colision y agota los tres intentos en milisegundos, convirtiendo un problema pasajero en
 * un Job fallido. Separar los intentos 200 ms, 400 ms y 800 ms le da al otro hilo tiempo de
 * commitear y soltar el bloqueo. El tope de {@code backoffMaximoMs} evita el extremo
 * opuesto: que una espera creciente deje la ventana nocturna en pausa indefinida.</p>
 */
public final class PoliticaReintentoBancaria {

    private PoliticaReintentoBancaria() {
        // Clase de utilidad: no se instancia.
    }

    /**
     * Excepciones que se reintentan, todas ellas fallos recuperables de acceso a datos.
     * {@code true} significa "reintentable"; lo que no esta en el mapa no se reintenta.
     */
    public static Map<Class<? extends Throwable>, Boolean> excepcionesReintentables() {
        Map<Class<? extends Throwable>, Boolean> mapa = new LinkedHashMap<>();
        // Familia general de errores transitorios de Spring (conexion caida, recurso ocupado).
        mapa.put(TransientDataAccessException.class, true);
        // Contencion entre hilos: aparecen justo al escalar a varios hilos sobre la misma tabla.
        mapa.put(CannotAcquireLockException.class, true);
        mapa.put(PessimisticLockingFailureException.class, true);
        mapa.put(OptimisticLockingFailureException.class, true);
        mapa.put(QueryTimeoutException.class, true);
        return mapa;
    }

    /**
     * @param propiedades configuracion de la corrida
     * @return politica que reintenta hasta {@code limiteReintentos} intentos en total
     */
    public static RetryPolicy politica(PropiedadesBatch propiedades) {
        // El tercer parametro (traverseCauses = true) es necesario porque Hibernate envuelve
        // el error original: sin el, una TransientDataAccessException anidada no se reconoce.
        return new SimpleRetryPolicy(propiedades.getLimiteReintentos(), excepcionesReintentables(), true);
    }

    /**
     * Indica si una excepcion pertenece al catalogo reintentable.
     *
     * <p>Hace falta porque Spring Batch hace pasar por el mismo {@code RetryTemplate} tanto
     * los reintentos como las omisiones: un dato invalido dispara igualmente los callbacks de
     * {@code RetryListener}. Sin este filtro el log declararia "reintento" y "reintentos
     * agotados" cada vez que se omite una fila sucia, que es justo lo contrario de lo que
     * ocurre, y la evidencia de la corrida quedaria diciendo lo que no es.</p>
     */
    public static boolean esReintentable(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            for (Class<? extends Throwable> tipo : excepcionesReintentables().keySet()) {
                if (tipo.isInstance(causa)) {
                    return true;
                }
            }
            if (causa.getCause() == causa) {
                break;
            }
        }
        return false;
    }

    /** Espera creciente entre intentos, para no reproducir la contencion que causo el fallo. */
    public static BackOffPolicy backoff(PropiedadesBatch propiedades) {
        ExponentialBackOffPolicy espera = new ExponentialBackOffPolicy();
        espera.setInitialInterval(propiedades.getBackoffInicialMs());
        espera.setMultiplier(propiedades.getBackoffMultiplicador());
        espera.setMaxInterval(propiedades.getBackoffMaximoMs());
        return espera;
    }
}
