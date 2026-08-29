package com.bancoxyz.repository;

import com.bancoxyz.entity.MovimientoAnual;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Acceso a los movimientos anuales normalizados por el Job 3. */
@Repository
public interface MovimientoAnualRepository extends JpaRepository<MovimientoAnual, Long> {

    Page<MovimientoAnual> findByJobExecutionId(Long jobExecutionId, Pageable paginacion);

    long countByJobExecutionId(Long jobExecutionId);

    /**
     * Borra lo que dejo una ejecucion anterior de la misma JobInstance.
     * Lo usa {@code LimpiezaDeReintentoTasklet} para que reanudar una corrida no deje
     * mezcladas las filas del intento fallido con las del intento bueno.
     */
    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
