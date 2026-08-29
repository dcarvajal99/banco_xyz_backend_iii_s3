package com.bancoxyz.batch.listener;

import com.bancoxyz.dto.FilaLegacy;
import com.bancoxyz.service.RegistroRechazadoService;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;

/**
 * Convierte cada omision de Spring Batch en una fila auditable de la bitacora.
 *
 * <p>Sin este listener la politica de omision seria una perdida silenciosa de datos: el
 * Job terminaria en {@code COMPLETED} y nadie sabria que 250 movimientos quedaron fuera.
 * Aqui se captura el momento exacto del descarte (lectura, procesamiento o escritura), la
 * linea del archivo, su contenido crudo y el motivo.</p>
 *
 * <p>Desde la semana 2 el motivo incluye ademas el <b>hilo</b> que produjo el descarte. Con
 * el Step a tres hilos, saber que la fila 412 la rechazo {@code batch-chunk-2} mientras
 * {@code batch-chunk-1} trabajaba en la 407 es lo que permite reconstruir despues el orden
 * real de una corrida, que ya no coincide con el orden del archivo.</p>
 *
 * @param <I> tipo del item de entrada (DTO del CSV)
 * @param <O> tipo del item de salida (entidad destino)
 */
public class RegistroRechazadoSkipListener<I extends FilaLegacy, O> implements SkipListener<I, O> {

    private static final int LINEA_DESCONOCIDA = 0;

    private final String jobNombre;
    private final String archivo;
    private final RegistroRechazadoService bitacora;
    private final Long jobExecutionId;

    public RegistroRechazadoSkipListener(String jobNombre, String archivo,
                                         RegistroRechazadoService bitacora, Long jobExecutionId) {
        this.jobNombre = jobNombre;
        this.archivo = archivo;
        this.bitacora = bitacora;
        this.jobExecutionId = jobExecutionId;
    }

    /** La fila ni siquiera pudo tokenizarse: columnas de mas, de menos o archivo corrupto. */
    @Override
    public void onSkipInRead(Throwable excepcion) {
        if (excepcion instanceof FlatFileParseException parseo) {
            bitacora.registrarOmision(jobNombre, archivo, parseo.getLineNumber(), parseo.getInput(),
                    "Error de lectura: " + mensaje(excepcion), jobExecutionId);
            return;
        }
        bitacora.registrarOmision(jobNombre, archivo, LINEA_DESCONOCIDA, "(linea no disponible)",
                "Error de lectura: " + mensaje(excepcion), jobExecutionId);
    }

    /** La fila se leyo bien pero no paso las validaciones de negocio del ItemProcessor. */
    @Override
    public void onSkipInProcess(I item, Throwable excepcion) {
        bitacora.registrarOmision(jobNombre, archivo, item.getNumeroLinea(), item.comoLinea(),
                mensaje(excepcion), jobExecutionId);
    }

    /** El registro fallo al persistirse (por ejemplo, violacion de una restriccion). */
    @Override
    public void onSkipInWrite(O item, Throwable excepcion) {
        bitacora.registrarOmision(jobNombre, archivo, LINEA_DESCONOCIDA, String.valueOf(item),
                "Error de escritura: " + mensaje(excepcion), jobExecutionId);
    }

    private static String mensaje(Throwable excepcion) {
        String detalle;
        if (excepcion == null) {
            detalle = "Sin detalle";
        } else {
            detalle = excepcion.getMessage() == null
                    ? excepcion.getClass().getSimpleName()
                    : excepcion.getMessage();
        }
        return detalle + " [hilo=" + Thread.currentThread().getName() + "]";
    }
}
