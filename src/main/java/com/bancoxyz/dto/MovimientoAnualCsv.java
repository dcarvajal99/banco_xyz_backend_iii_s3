package com.bancoxyz.dto;

/**
 * Fila cruda de {@code cuentas_anuales.csv} (historial anual de operaciones).
 *
 * <p>Campos en {@code String} para tolerar fechas en formatos mixtos, montos vacios y
 * descripciones faltantes sin romper la lectura.</p>
 */
public class MovimientoAnualCsv implements FilaLegacy {

    private int numeroLinea;

    private String cuentaId;
    private String fecha;
    private String transaccion;
    private String monto;
    private String descripcion;

    public String getCuentaId() {
        return cuentaId;
    }

    public void setCuentaId(String cuentaId) {
        this.cuentaId = cuentaId;
    }

    public String getFecha() {
        return fecha;
    }

    public void setFecha(String fecha) {
        this.fecha = fecha;
    }

    public String getTransaccion() {
        return transaccion;
    }

    public void setTransaccion(String transaccion) {
        this.transaccion = transaccion;
    }

    public String getMonto() {
        return monto;
    }

    public void setMonto(String monto) {
        this.monto = monto;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
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
        return String.join(",", nvl(cuentaId), nvl(fecha), nvl(transaccion), nvl(monto), nvl(descripcion));
    }

    private static String nvl(String valor) {
        return valor == null ? "" : valor.trim();
    }

    @Override
    public String toString() {
        return "linea " + numeroLinea + ": " + comoLinea();
    }
}
