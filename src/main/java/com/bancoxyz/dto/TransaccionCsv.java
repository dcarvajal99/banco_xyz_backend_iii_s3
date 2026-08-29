package com.bancoxyz.dto;

/**
 * Fila cruda de {@code transacciones.csv} tal como sale del sistema legacy.
 *
 * <p>Todos los campos son {@code String} a proposito: si el DTO de lectura ya exigiera
 * tipos, el {@code ItemReader} reventaria en la fila 4 y perderiamos la trazabilidad del
 * dato sucio. Leemos texto y dejamos que el {@code ItemProcessor} decida si se corrige,
 * se marca como anomalia o se rechaza.</p>
 */
public class TransaccionCsv implements FilaLegacy {

    private int numeroLinea;

    private String id;
    private String fecha;
    private String monto;
    private String tipo;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFecha() {
        return fecha;
    }

    public void setFecha(String fecha) {
        this.fecha = fecha;
    }

    public String getMonto() {
        return monto;
    }

    public void setMonto(String monto) {
        this.monto = monto;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    @Override
    public int getNumeroLinea() {
        return numeroLinea;
    }

    @Override
    public void setNumeroLinea(int numeroLinea) {
        this.numeroLinea = numeroLinea;
    }

    /** Representacion de la fila para dejarla registrada cuando se rechaza. */
    @Override
    public String comoLinea() {
        return String.join(",", nvl(id), nvl(fecha), nvl(monto), nvl(tipo));
    }

    private static String nvl(String valor) {
        return valor == null ? "" : valor.trim();
    }

    @Override
    public String toString() {
        return "linea " + numeroLinea + ": " + comoLinea();
    }
}
