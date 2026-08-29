package com.bancoxyz.repository;

import com.bancoxyz.entity.RegistroRechazado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Acceso a la bitacora de registros omitidos o filtrados durante la migracion. */
@Repository
public interface RegistroRechazadoRepository extends JpaRepository<RegistroRechazado, Long> {

    List<RegistroRechazado> findByJobExecutionIdOrderByIdAsc(Long jobExecutionId);

    long countByJobExecutionId(Long jobExecutionId);

    long countByJobExecutionIdAndClasificacion(Long jobExecutionId, String clasificacion);

    /**
     * Borra lo que dejo una ejecucion anterior de la misma JobInstance.
     * Lo usa {@code LimpiezaDeReintentoTasklet} para que reanudar una corrida no deje
     * mezcladas las filas del intento fallido con las del intento bueno.
     */
    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
