package com.bancoxyz.repository;

import com.bancoxyz.entity.Transaccion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Acceso a las transacciones diarias ya migradas. */
@Repository
public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    /**
     * Recorre paginadamente las transacciones de una ejecucion concreta del Job.
     * Se pagina para que el Step de resumen no cargue el archivo completo en memoria.
     */
    Page<Transaccion> findByJobExecutionId(Long jobExecutionId, Pageable paginacion);

    long countByJobExecutionId(Long jobExecutionId);

    long countByJobExecutionIdAndAnomaliaTrue(Long jobExecutionId);

    /**
     * Borra lo que dejo una ejecucion anterior de la misma JobInstance.
     * Lo usa {@code LimpiezaDeReintentoTasklet} para que reanudar una corrida no deje
     * mezcladas las filas del intento fallido con las del intento bueno.
     */
    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
