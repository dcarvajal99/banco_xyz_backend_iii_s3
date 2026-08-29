package com.bancoxyz.dto;

/**
 * Contrato comun de las filas crudas leidas de los archivos legacy.
 *
 * <p>El {@code numeroLinea} no viene en el CSV: lo inyecta el {@code LineMapper} al leer.
 * Es la identidad fisica de la fila y resuelve un problema sutil de Spring Batch: cuando
 * un item de un chunk falla, el chunk se revierte y se vuelve a procesar item por item,
 * de modo que un {@code ItemProcessor} con estado (por ejemplo, uno que detecta
 * duplicados) ve dos veces la <em>misma</em> fila y la confundiria con un duplicado real.
 * Comparando el numero de linea se distingue "esta fila ya la vi" de "esta fila se esta
 * reprocesando".</p>
 *
 * <p>Ademas permite decirle al area de datos exactamente que linea del archivo corregir.</p>
 */
public interface FilaLegacy {

    /** Numero de linea en el archivo de origen, contando la cabecera. */
    int getNumeroLinea();

    void setNumeroLinea(int numeroLinea);

    /** Contenido original de la fila, para dejarlo en la bitacora de rechazos. */
    String comoLinea();
}
