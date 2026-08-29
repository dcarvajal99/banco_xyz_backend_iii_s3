package com.bancoxyz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Bitacora de los registros que la migracion no pudo aceptar.
 *
 * <p>Omitir una fila sin dejar rastro seria peor que fallar: el negocio necesita saber
 * exactamente que se quedo fuera y por que. Cada omision del {@code SkipListener} y cada
 * duplicado filtrado por un {@code ItemProcessor} aterriza en esta tabla.</p>
 */
@Entity
@Table(name = "registro_rechazado", indexes = {
        @Index(name = "idx_rechazado_ejecucion", columnList = "job_execution_id"),
        @Index(name = "idx_rechazado_job", columnList = "job_nombre")
})
public class RegistroRechazado {

    @Id
    // SEQUENCE con allocationSize permite que Hibernate agrupe los INSERT en lotes JDBC;
    // con IDENTITY tendria que ir a la base por cada fila y el batch perderia rendimiento.
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "registro_rechazado_seq")
    @SequenceGenerator(name = "registro_rechazado_seq", sequenceName = "registro_rechazado_seq", allocationSize = 50)
    private Long id;

    @Column(name = "job_nombre", nullable = false, length = 80)
    private String jobNombre;

    @Column(nullable = false, length = 60)
    private String archivo;

    /** Clasificacion del rechazo: {@code OMITIDO} (excepcion) o {@code FILTRADO} (duplicado). */
    @Column(nullable = false, length = 20)
    private String clasificacion;

    /** Linea exacta del archivo de origen, para que el area de datos sepa que corregir. */
    @Column(name = "numero_linea", nullable = false)
    private int numeroLinea;

    @Column(nullable = false, length = 500)
    private String contenido;

    @Column(nullable = false, length = 255)
    private String motivo;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "registrado_en", nullable = false)
    private LocalDateTime registradoEn = LocalDateTime.now();

    public RegistroRechazado() {
        // Constructor requerido por JPA.
    }

    public RegistroRechazado(String jobNombre, String archivo, String clasificacion, int numeroLinea,
                             String contenido, String motivo, Long jobExecutionId) {
        this.jobNombre = jobNombre;
        this.archivo = archivo;
        this.clasificacion = clasificacion;
        this.numeroLinea = numeroLinea;
        this.contenido = recortar(contenido, 500);
        this.motivo = recortar(motivo, 255);
        this.jobExecutionId = jobExecutionId;
    }

    private static String recortar(String valor, int maximo) {
        String texto = valor == null ? "" : valor;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJobNombre() {
        return jobNombre;
    }

    public void setJobNombre(String jobNombre) {
        this.jobNombre = jobNombre;
    }

    public String getArchivo() {
        return archivo;
    }

    public void setArchivo(String archivo) {
        this.archivo = archivo;
    }

    public String getClasificacion() {
        return clasificacion;
    }

    public void setClasificacion(String clasificacion) {
        this.clasificacion = clasificacion;
    }

    public int getNumeroLinea() {
        return numeroLinea;
    }

    public void setNumeroLinea(int numeroLinea) {
        this.numeroLinea = numeroLinea;
    }

    public String getContenido() {
        return contenido;
    }

    public void setContenido(String contenido) {
        this.contenido = recortar(contenido, 500);
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = recortar(motivo, 255);
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(Long jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public LocalDateTime getRegistradoEn() {
        return registradoEn;
    }

    public void setRegistradoEn(LocalDateTime registradoEn) {
        this.registradoEn = registradoEn;
    }

    @Override
    public boolean equals(Object otro) {
        if (this == otro) {
            return true;
        }
        if (!(otro instanceof RegistroRechazado registro)) {
            return false;
        }
        return Objects.equals(id, registro.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "RegistroRechazado{archivo='" + archivo + "', linea=" + numeroLinea
                + ", clasificacion='" + clasificacion + "', motivo='" + motivo + "'}";
    }
}
