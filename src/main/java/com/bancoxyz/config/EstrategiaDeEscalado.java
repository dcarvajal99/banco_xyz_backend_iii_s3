package com.bancoxyz.config;

/**
 * Como se reparte el trabajo de un Step de migracion.
 *
 * <p>Existe como propiedad y no como decision de codigo porque el criterio de la actividad es
 * <em>comparar</em> configuraciones para encontrar la optima. Teniendo las tres estrategias
 * detras de un parametro, la comparacion se hace con el mismo jar y el mismo dato, cambiando
 * una linea de la invocacion:</p>
 *
 * <pre>
 *   java -jar banco-xyz-batch.jar --job=estados --dataset=semana_3 --banco.batch.estrategia=SECUENCIAL
 *   java -jar banco-xyz-batch.jar --job=estados --dataset=semana_3 --banco.batch.estrategia=MULTIHILO
 *   java -jar banco-xyz-batch.jar --job=estados --dataset=semana_3 --banco.batch.estrategia=PARTICIONADO
 * </pre>
 *
 * <p>Si cada estrategia viviera en una rama distinta del codigo, comparar exigiria recompilar y
 * las mediciones dejarian de ser comparables entre si.</p>
 */
public enum EstrategiaDeEscalado {

    /**
     * Un solo hilo recorre el archivo completo. Es la linea base contra la que se mide todo lo
     * demas: sin ella, decir que el paralelismo "mejora" no significa nada.
     */
    SECUENCIAL,

    /**
     * Un Step con varios hilos procesando chunks del mismo archivo (lo que implemento la
     * semana 2). Obliga a sincronizar el lector y a renunciar a guardar su posicion.
     */
    MULTIHILO,

    /**
     * El archivo se divide en particiones y cada una se procesa como un Step independiente.
     * Es la estrategia de esta semana: cada particion tiene su propio lector monohilo, su
     * propio contexto de ejecucion y su propia contabilidad de errores.
     */
    PARTICIONADO
}
