package com.bancoxyz.service;

import com.bancoxyz.entity.RegistroRechazado;
import com.bancoxyz.repository.RegistroRechazadoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deja constancia de cada fila que la migracion no pudo aceptar.
 *
 * <p>La escritura usa {@link Propagation#REQUIRES_NEW} de forma deliberada: cuando un
 * {@code ItemProcessor} lanza una excepcion, Spring Batch hace rollback del chunk y
 * reintenta item por item. Si la bitacora participara de esa misma transaccion se
 * perderia justo el registro del error que queremos auditar.</p>
 */
@Service
public class RegistroRechazadoService {

    /** Fila descartada por una excepcion y saltada por la politica de omision. */
    public static final String CLASIFICACION_OMITIDO = "OMITIDO";
    /** Fila descartada por el {@code ItemProcessor} sin lanzar excepcion (duplicado). */
    public static final String CLASIFICACION_FILTRADO = "FILTRADO";

    private static final Logger log = LoggerFactory.getLogger(RegistroRechazadoService.class);

    private final RegistroRechazadoRepository repositorio;

    public RegistroRechazadoService(RegistroRechazadoRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarOmision(String job, String archivo, int numeroLinea,
                                 String contenido, String motivo, Long jobExecutionId) {
        guardar(job, archivo, CLASIFICACION_OMITIDO, numeroLinea, contenido, motivo, jobExecutionId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarFiltrado(String job, String archivo, int numeroLinea,
                                  String contenido, String motivo, Long jobExecutionId) {
        guardar(job, archivo, CLASIFICACION_FILTRADO, numeroLinea, contenido, motivo, jobExecutionId);
    }

    private void guardar(String job, String archivo, String clasificacion, int numeroLinea,
                         String contenido, String motivo, Long jobExecutionId) {
        repositorio.save(new RegistroRechazado(job, archivo, clasificacion, numeroLinea,
                contenido, motivo, jobExecutionId));
        log.warn("[{}] {} {}:{} -> {} | {}", job, clasificacion, archivo, numeroLinea, motivo, contenido);
    }
}
