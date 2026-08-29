package com.bancoxyz.repository;

import com.bancoxyz.entity.ResumenDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Acceso al reporte diario de transacciones generado por el Job 1. */
@Repository
public interface ResumenDiarioRepository extends JpaRepository<ResumenDiario, Long> {

    List<ResumenDiario> findByJobExecutionIdOrderByFechaAsc(Long jobExecutionId);

    /**
     * Borra lo que dejo una ejecucion anterior de la misma JobInstance.
     * Lo usa {@code LimpiezaDeReintentoTasklet} para que reanudar una corrida no deje
     * mezcladas las filas del intento fallido con las del intento bueno.
     */
    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
