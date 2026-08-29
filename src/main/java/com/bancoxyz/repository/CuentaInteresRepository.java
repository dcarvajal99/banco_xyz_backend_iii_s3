package com.bancoxyz.repository;

import com.bancoxyz.entity.CuentaInteres;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Acceso a las cuentas con interes mensual calculado por el Job 2. */
@Repository
public interface CuentaInteresRepository extends JpaRepository<CuentaInteres, Long> {

    Page<CuentaInteres> findByJobExecutionId(Long jobExecutionId, Pageable paginacion);

    long countByJobExecutionId(Long jobExecutionId);

    /**
     * Borra lo que dejo una ejecucion anterior de la misma JobInstance.
     * Lo usa {@code LimpiezaDeReintentoTasklet} para que reanudar una corrida no deje
     * mezcladas las filas del intento fallido con las del intento bueno.
     */
    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
