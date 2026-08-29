package com.bancoxyz.batch.processor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detecta claves repetidas dentro de una misma ejecucion de un Step, sin confundirse
 * cuando Spring Batch reprocesa una fila y sin romperse cuando el Step corre en paralelo.
 *
 * <p><b>Por que recuerda la linea y no solo la clave.</b> Guardar un {@code Set} de claves
 * vistas parece suficiente hasta que aparece el primer dato sucio: al fallar un item, el
 * chunk completo se revierte y se vuelve a procesar item por item, de modo que las filas
 * buenas de ese chunk pasan por el procesador <b>dos veces</b> y el {@code Set} las
 * marcaria como duplicadas. Recordando la linea de la primera aparicion, una fila que
 * vuelve a pasar por su propia linea se reconoce como reproceso y no como duplicado.</p>
 *
 * <p><b>Por que es concurrente desde la semana 2.</b> Al pasar los Steps a tres hilos, tres
 * chunks distintos consultan y actualizan este mapa al mismo tiempo. Con un {@code HashMap}
 * la consecuencia no seria un resultado raro sino corrupcion: dos {@code put} simultaneos
 * durante un redimensionamiento pueden dejar la tabla interna inconsistente y hacer que una
 * lectura posterior se cuelgue o pierda entradas. {@link ConcurrentHashMap#putIfAbsent} es
 * ademas <em>atomico</em>, que es lo que aqui hace falta: si dos hilos traen la misma clave
 * a la vez, exactamente uno la registra como primera aparicion y el otro la ve duplicada.
 * Sin esa atomicidad ambos podrian creerse los primeros y la fila se migraria dos veces.</p>
 *
 * <p><b>Que garantiza y que no.</b> Con varios hilos el <em>conjunto</em> de filas que
 * sobreviven es siempre el mismo (una por clave) y la cantidad migrada es estable entre
 * corridas; lo que puede variar es <em>cual</em> de las copias identicas queda registrada
 * como la primera. Para el Banco XYZ eso es indiferente: las copias que se descartan son
 * identicas entre si, y las que solo quedan marcadas como sospechosas se migran igual. Lo
 * que no seria admisible —perder una fila o duplicarla— es justamente lo que la atomicidad
 * del {@code putIfAbsent} impide.</p>
 */
public class DetectorDeDuplicados {

    private final Map<String, Integer> primeraAparicion = new ConcurrentHashMap<>();

    /**
     * @param clave clave de negocio del registro (id, cuenta, contenido completo, etc.)
     * @param numeroLinea linea del archivo de la que salio la fila
     * @return {@code true} si la clave ya aparecio en <em>otra</em> linea del archivo
     */
    public boolean esDuplicado(String clave, int numeroLinea) {
        Integer primera = primeraAparicion.putIfAbsent(clave, numeroLinea);
        return primera != null && primera != numeroLinea;
    }

    /** Cantidad de claves distintas registradas hasta el momento. */
    public int clavesRegistradas() {
        return primeraAparicion.size();
    }
}
