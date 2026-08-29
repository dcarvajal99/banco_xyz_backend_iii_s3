package com.bancoxyz.repository;

import com.bancoxyz.entity.EstadoCuentaAnual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Acceso a los estados de cuenta anuales compilados para auditoria. */
@Repository
public interface EstadoCuentaAnualRepository extends JpaRepository<EstadoCuentaAnual, Long> {

    List<EstadoCuentaAnual> findByJobExecutionIdOrderByCuentaIdAscAnioAsc(Long jobExecutionId);

    /**
     * Borra lo que dejo una ejecucion anterior de la misma JobInstance.
     * Lo usa {@code LimpiezaDeReintentoTasklet} para que reanudar una corrida no deje
     * mezcladas las filas del intento fallido con las del intento bueno.
     */
    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
