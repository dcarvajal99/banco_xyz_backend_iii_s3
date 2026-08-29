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
 * Estado de cuenta anual compilado por cuenta y ano (segundo Step del Job 3).
 *
 * <p>Es el informe que auditoria pedia al sistema legacy y que se generaba a mano con
 * shell scripts: totales, saldo neto, ventana de fechas y cantidad de movimientos
 * marcados como anomalia.</p>
 */
@Entity
@Table(name = "estado_cuenta_anual", indexes = {
        @Index(name = "idx_estado_cuenta_ejecucion", columnList = "job_execution_id")
})
public class EstadoCuentaAnual {

    @Id
    // SEQUENCE con allocationSize permite que Hibernate agrupe los INSERT en lotes JDBC;
    // con IDENTITY tendria que ir a la base por cada fila y el batch perderia rendimiento.
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "estado_cuenta_anual_seq")
    @SequenceGenerator(name = "estado_cuenta_anual_seq", sequenceName = "estado_cuenta_anual_seq", allocationSize = 50)
    private Long id;

    @Column(name = "cuenta_id", nullable = false)
    private Long cuentaId;

    @Column(nullable = false)
    private int anio;

    @Column(name = "cantidad_movimientos", nullable = false)
    private long cantidadMovimientos;

    @Column(name = "total_depositos", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDepositos = BigDecimal.ZERO;

    @Column(name = "total_cargos", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCargos = BigDecimal.ZERO;

    @Column(name = "saldo_neto", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoNeto = BigDecimal.ZERO;

    @Column(name = "primera_fecha")
    private LocalDate primeraFecha;

    @Column(name = "ultima_fecha")
    private LocalDate ultimaFecha;

    @Column(name = "movimientos_con_anomalia", nullable = false)
    private long movimientosConAnomalia;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "generado_en", nullable = false)
    private LocalDateTime generadoEn = LocalDateTime.now();

    public EstadoCuentaAnual() {
        // Constructor requerido por JPA.
    }

    public EstadoCuentaAnual(Long cuentaId, int anio, Long jobExecutionId) {
        this.cuentaId = cuentaId;
        this.anio = anio;
        this.jobExecutionId = jobExecutionId;
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

    public int getAnio() {
        return anio;
    }

    public void setAnio(int anio) {
        this.anio = anio;
    }

    public long getCantidadMovimientos() {
        return cantidadMovimientos;
    }

    public void setCantidadMovimientos(long cantidadMovimientos) {
        this.cantidadMovimientos = cantidadMovimientos;
    }

    public BigDecimal getTotalDepositos() {
        return totalDepositos;
    }

    public void setTotalDepositos(BigDecimal totalDepositos) {
        this.totalDepositos = totalDepositos;
    }

    public BigDecimal getTotalCargos() {
        return totalCargos;
    }

    public void setTotalCargos(BigDecimal totalCargos) {
        this.totalCargos = totalCargos;
    }

    public BigDecimal getSaldoNeto() {
        return saldoNeto;
    }

    public void setSaldoNeto(BigDecimal saldoNeto) {
        this.saldoNeto = saldoNeto;
    }

    public LocalDate getPrimeraFecha() {
        return primeraFecha;
    }

    public void setPrimeraFecha(LocalDate primeraFecha) {
        this.primeraFecha = primeraFecha;
    }

    public LocalDate getUltimaFecha() {
        return ultimaFecha;
    }

    public void setUltimaFecha(LocalDate ultimaFecha) {
        this.ultimaFecha = ultimaFecha;
    }

    public long getMovimientosConAnomalia() {
        return movimientosConAnomalia;
    }

    public void setMovimientosConAnomalia(long movimientosConAnomalia) {
        this.movimientosConAnomalia = movimientosConAnomalia;
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
        if (!(otro instanceof EstadoCuentaAnual estado)) {
            return false;
        }
        return anio == estado.anio
                && Objects.equals(cuentaId, estado.cuentaId)
                && Objects.equals(jobExecutionId, estado.jobExecutionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cuentaId, anio, jobExecutionId);
    }

    @Override
    public String toString() {
        return "EstadoCuentaAnual{cuentaId=" + cuentaId + ", anio=" + anio
                + ", movimientos=" + cantidadMovimientos + ", saldoNeto=" + saldoNeto + "}";
    }
}
