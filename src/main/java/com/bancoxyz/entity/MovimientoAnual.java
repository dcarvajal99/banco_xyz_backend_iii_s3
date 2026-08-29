package com.bancoxyz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Movimiento anual normalizado (primer Step del Job 3).
 *
 * <p>El monto se guarda ya con el signo que corresponde al tipo de movimiento: positivo
 * para depositos y negativo para retiros, compras y pagos. Asi el estado de cuenta se
 * calcula sumando, sin reglas condicionales repartidas por el codigo.</p>
 */
@Entity
@Table(name = "movimiento_anual", indexes = {
        @Index(name = "idx_movimiento_ejecucion", columnList = "job_execution_id"),
        @Index(name = "idx_movimiento_cuenta_anio", columnList = "cuenta_id, anio")
})
public class MovimientoAnual {

    @Id
    // SEQUENCE con allocationSize permite que Hibernate agrupe los INSERT en lotes JDBC;
    // con IDENTITY tendria que ir a la base por cada fila y el batch perderia rendimiento.
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "movimiento_anual_seq")
    @SequenceGenerator(name = "movimiento_anual_seq", sequenceName = "movimiento_anual_seq", allocationSize = 50)
    private Long id;

    @NotNull
    @Column(name = "cuenta_id", nullable = false)
    private Long cuentaId;

    @NotNull
    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private int anio;

    @NotNull
    @Column(name = "tipo_transaccion", nullable = false, length = 20)
    private String tipoTransaccion;

    @NotNull
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, length = 255)
    private String descripcion;

    @Column(nullable = false)
    private boolean anomalia;

    @Column(length = 255)
    private String observacion;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "procesado_en", nullable = false)
    private LocalDateTime procesadoEn = LocalDateTime.now();

    public MovimientoAnual() {
        // Constructor requerido por JPA.
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCuentaId() {
        return cuentaId;
    }

    public void setCuentaId(Long cuentaId) {
        this.cuentaId = cuentaId;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
        this.anio = fecha == null ? 0 : fecha.getYear();
    }

    public int getAnio() {
        return anio;
    }

    public void setAnio(int anio) {
        this.anio = anio;
    }

    public String getTipoTransaccion() {
        return tipoTransaccion;
    }

    public void setTipoTransaccion(String tipoTransaccion) {
        this.tipoTransaccion = tipoTransaccion;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public void setMonto(BigDecimal monto) {
        this.monto = monto;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public boolean isAnomalia() {
        return anomalia;
    }

    public void setAnomalia(boolean anomalia) {
        this.anomalia = anomalia;
    }

    public String getObservacion() {
        return observacion;
    }

    /** Recorta la observacion al largo de la columna: nunca debe tumbar el insert. */
    public void setObservacion(String observacion) {
        this.observacion = observacion == null || observacion.length() <= 255
                ? observacion
                : observacion.substring(0, 255);
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(Long jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public LocalDateTime getProcesadoEn() {
        return procesadoEn;
    }

    public void setProcesadoEn(LocalDateTime procesadoEn) {
        this.procesadoEn = procesadoEn;
    }

    @Override
    public boolean equals(Object otro) {
        if (this == otro) {
            return true;
        }
        if (!(otro instanceof MovimientoAnual movimiento)) {
            return false;
        }
        return Objects.equals(id, movimiento.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MovimientoAnual{cuentaId=" + cuentaId + ", fecha=" + fecha
                + ", tipo='" + tipoTransaccion + "', monto=" + monto + "}";
    }
}
