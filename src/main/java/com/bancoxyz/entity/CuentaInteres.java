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
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Cuenta con su interes mensual ya calculado (destino del Job 2).
 *
 * <p>Se guardan por separado el saldo inicial, la tasa aplicada, el interes y el saldo
 * final: auditoria necesita poder rehacer el calculo sin volver a correr el batch.</p>
 */
@Entity
@Table(name = "cuenta_interes", indexes = {
        @Index(name = "idx_cuenta_interes_ejecucion", columnList = "job_execution_id"),
        @Index(name = "idx_cuenta_interes_cuenta", columnList = "cuenta_id")
})
public class CuentaInteres {

    @Id
    // SEQUENCE con allocationSize permite que Hibernate agrupe los INSERT en lotes JDBC;
    // con IDENTITY tendria que ir a la base por cada fila y el batch perderia rendimiento.
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cuenta_interes_seq")
    @SequenceGenerator(name = "cuenta_interes_seq", sequenceName = "cuenta_interes_seq", allocationSize = 50)
    private Long id;

    @NotNull
    @Column(name = "cuenta_id", nullable = false)
    private Long cuentaId;

    @Column(nullable = false, length = 120)
    private String nombre;

    @NotNull
    @Column(nullable = false, length = 20)
    private String tipo;

    @Column(nullable = false)
    private int edad;

    @Column(name = "saldo_inicial", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoInicial;

    /** Tasa mensual efectivamente aplicada, incluida la bonificacion por tercera edad. */
    @Column(name = "tasa_mensual", nullable = false, precision = 8, scale = 5)
    private BigDecimal tasaMensual;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal interes;

    @Column(name = "saldo_final", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoFinal;

    @Column(nullable = false)
    private boolean anomalia;

    @Column(length = 255)
    private String observacion;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "procesado_en", nullable = false)
    private LocalDateTime procesadoEn = LocalDateTime.now();

    public CuentaInteres() {
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

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public int getEdad() {
        return edad;
    }

    public void setEdad(int edad) {
        this.edad = edad;
    }

    public BigDecimal getSaldoInicial() {
        return saldoInicial;
    }

    public void setSaldoInicial(BigDecimal saldoInicial) {
        this.saldoInicial = saldoInicial;
    }

    public BigDecimal getTasaMensual() {
        return tasaMensual;
    }

    public void setTasaMensual(BigDecimal tasaMensual) {
        this.tasaMensual = tasaMensual;
    }

    public BigDecimal getInteres() {
        return interes;
    }

    public void setInteres(BigDecimal interes) {
        this.interes = interes;
    }

    public BigDecimal getSaldoFinal() {
        return saldoFinal;
    }

    public void setSaldoFinal(BigDecimal saldoFinal) {
        this.saldoFinal = saldoFinal;
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
        if (!(otro instanceof CuentaInteres cuenta)) {
            return false;
        }
        return Objects.equals(cuentaId, cuenta.cuentaId) && Objects.equals(jobExecutionId, cuenta.jobExecutionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cuentaId, jobExecutionId);
    }

    @Override
    public String toString() {
        return "CuentaInteres{cuentaId=" + cuentaId + ", tipo='" + tipo + "', saldoInicial=" + saldoInicial
                + ", interes=" + interes + ", saldoFinal=" + saldoFinal + "}";
    }
}
