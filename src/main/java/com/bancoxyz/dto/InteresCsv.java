package com.bancoxyz.dto;

/**
 * Fila cruda de {@code intereses.csv} (maestro de cuentas del sistema legacy).
 *
 * <p>Igual que el resto de DTO de entrada, todos los campos son {@code String} para que la
 * lectura nunca falle por un saldo vacio o una edad fuera de rango.</p>
 */
public class InteresCsv implements FilaLegacy {

    private int numeroLinea;

    private String cuentaId;
    private String nombre;
    private String saldo;
    private String edad;
    private String tipo;

    public String getCuentaId() {
        return cuentaId;
    }

    public void setCuentaId(String cuentaId) {
        this.cuentaId = cuentaId;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getSaldo() {
        return saldo;
    }

    public void setSaldo(String saldo) {
        this.saldo = saldo;
    }

    public String getEdad() {
        return edad;
    }

    public void setEdad(String edad) {
        this.edad = edad;
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
        return String.join(",", nvl(cuentaId), nvl(nombre), nvl(saldo), nvl(edad), nvl(tipo));
    }

    private static String nvl(String valor) {
        return valor == null ? "" : valor.trim();
    }

    @Override
    public String toString() {
        return "linea " + numeroLinea + ": " + comoLinea();
    }
}
