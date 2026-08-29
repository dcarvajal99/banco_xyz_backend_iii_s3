package com.bancoxyz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Resumen por dia que produce el segundo Step del Job 1.
 *
 * <p>Es el reemplazo del listado diario que el sistema legacy imprimia desde COBOL:
 * totales por tipo, monto maximo del dia y cantidad de anomalias detectadas.</p>
 */
@Entity
@Table(name = "resumen_diario", indexes = {
        @Index(name = "idx_resumen_diario_ejecucion", columnList = "job_execution_id")
})
public class ResumenDiario {

    @Id
    // SEQUENCE con allocationSize permite que Hibernate agrupe los INSERT en lotes JDBC;
    // con IDENTITY tendria que ir a la base por cada fila y el batch perderia rendimiento.
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "resumen_diario_seq")
    @SequenceGenerator(name = "resumen_diario_seq", sequenceName = "resumen_diario_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(name = "cantidad_transacciones", nullable = false)
    private long cantidadTransacciones;

    @Column(name = "total_debitos", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDebitos = BigDecimal.ZERO;

    @Column(name = "total_creditos", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCreditos = BigDecimal.ZERO;

    @Column(name = "monto_maximo", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoMaximo = BigDecimal.ZERO;

    @Column(name = "cantidad_anomalias", nullable = false)
    private long cantidadAnomalias;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "generado_en", nullable = false)
    private LocalDateTime generadoEn = LocalDateTime.now();

    public ResumenDiario() {
        // Constructor requerido por JPA.
    }

    public ResumenDiario(LocalDate fecha, Long jobExecutionId) {
        this.fecha = fecha;
        this.jobExecutionId = jobExecutionId;
    }

    /** Saldo neto del dia: creditos menos debitos. */
    public BigDecimal getSaldoNeto() {
        return totalCreditos.subtract(totalDebitos);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public long getCantidadTransacciones() {
        return cantidadTransacciones;
    }

    public void setCantidadTransacciones(long cantidadTransacciones) {
        this.cantidadTransacciones = cantidadTransacciones;
    }

    public BigDecimal getTotalDebitos() {
        return totalDebitos;
    }

    public void setTotalDebitos(BigDecimal totalDebitos) {
        this.totalDebitos = totalDebitos;
    }

    public BigDecimal getTotalCreditos() {
        return totalCreditos;
    }

    public void setTotalCreditos(BigDecimal totalCreditos) {
        this.totalCreditos = totalCreditos;
    }

    public BigDecimal getMontoMaximo() {
        return montoMaximo;
    }

    public void setMontoMaximo(BigDecimal montoMaximo) {
        this.montoMaximo = montoMaximo;
    }

    public long getCantidadAnomalias() {
        return cantidadAnomalias;
    }

    public void setCantidadAnomalias(long cantidadAnomalias) {
        this.cantidadAnomalias = cantidadAnomalias;
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(Long jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public LocalDateTime getGeneradoEn() {
        return generadoEn;
    }

    public void setGeneradoEn(LocalDateTime generadoEn) {
        this.generadoEn = generadoEn;
    }

    @Override
    public boolean equals(Object otro) {
        if (this == otro) {
            return true;
        }
        if (!(otro instanceof ResumenDiario resumen)) {
            return false;
        }
        return Objects.equals(fecha, resumen.fecha) && Objects.equals(jobExecutionId, resumen.jobExecutionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fecha, jobExecutionId);
    }

    @Override
    public String toString() {
        return "ResumenDiario{fecha=" + fecha + ", transacciones=" + cantidadTransacciones
                + ", debitos=" + totalDebitos + ", creditos=" + totalCreditos
                + ", anomalias=" + cantidadAnomalias + "}";
    }
}
