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
 * Transaccion diaria ya migrada y normalizada (destino del Job 1).
 *
 * <p>Conserva {@code idOrigen} para poder reconciliar cada fila con el archivo legacy y
 * {@code jobExecutionId} para poder aislar los datos de cada corrida del batch.</p>
 */
@Entity
@Table(name = "transaccion", indexes = {
        @Index(name = "idx_transaccion_ejecucion", columnList = "job_execution_id"),
        @Index(name = "idx_transaccion_fecha", columnList = "fecha")
})
public class Transaccion {

    @Id
    // SEQUENCE con allocationSize permite que Hibernate agrupe los INSERT en lotes JDBC;
    // con IDENTITY tendria que ir a la base por cada fila y el batch perderia rendimiento.
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "transaccion_seq")
    @SequenceGenerator(name = "transaccion_seq", sequenceName = "transaccion_seq", allocationSize = 50)
    private Long id;

    /** Identificador que traia la fila en {@code transacciones.csv}. */
    @NotNull
    @Column(name = "id_origen", nullable = false)
    private Long idOrigen;

    @NotNull
    @Column(nullable = false)
    private LocalDate fecha;

    @NotNull
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal monto;

    @NotNull
    @Column(nullable = false, length = 20)
    private String tipo;

    /** {@code true} cuando la fila se migro pero requiere revision del area de riesgo. */
    @Column(nullable = false)
    private boolean anomalia;

    @Column(length = 255)
    private String observacion;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "procesado_en", nullable = false)
    private LocalDateTime procesadoEn = LocalDateTime.now();

    public Transaccion() {
        // Constructor requerido por JPA.
    }

    public Transaccion(Long idOrigen, LocalDate fecha, BigDecimal monto, String tipo) {
        this.idOrigen = idOrigen;
        this.fecha = fecha;
        this.monto = monto;
        this.tipo = tipo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdOrigen() {
        return idOrigen;
    }

    public void setIdOrigen(Long idOrigen) {
        this.idOrigen = idOrigen;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public void setMonto(BigDecimal monto) {
        this.monto = monto;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
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
        if (!(otro instanceof Transaccion transaccion)) {
            return false;
        }
        return Objects.equals(idOrigen, transaccion.idOrigen)
                && Objects.equals(jobExecutionId, transaccion.jobExecutionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idOrigen, jobExecutionId);
    }

    @Override
    public String toString() {
        return "Transaccion{idOrigen=" + idOrigen + ", fecha=" + fecha + ", monto=" + monto
                + ", tipo='" + tipo + "', anomalia=" + anomalia + "}";
    }
}
