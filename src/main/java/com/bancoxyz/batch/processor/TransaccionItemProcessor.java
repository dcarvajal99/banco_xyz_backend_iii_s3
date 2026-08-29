package com.bancoxyz.batch.processor;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import com.bancoxyz.dto.TransaccionCsv;
import com.bancoxyz.entity.Transaccion;
import com.bancoxyz.exception.DatoInvalidoException;
import com.bancoxyz.service.RegistroRechazadoService;
import com.bancoxyz.util.ParseadorFechas;
import com.bancoxyz.util.ParseadorNumeros;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Valida, corrige y clasifica cada fila de {@code transacciones.csv} (Job 1).
 *
 * <p>Distingue dos situaciones que el sistema legacy trataba igual:</p>
 * <ul>
 *   <li><b>Error estructural</b> (fecha ilegible, monto no numerico, tipo desconocido):
 *       se lanza {@link DatoInvalidoException}, la politica de omision salta la fila y el
 *       {@code SkipListener} la deja en la bitacora de rechazos.</li>
 *   <li><b>Anomalia de negocio</b> (monto no positivo, monto atipico, posible duplicado):
 *       la fila <em>si</em> se migra, marcada con {@code anomalia = true}, porque un banco
 *       no puede hacer desaparecer un movimiento; lo que corresponde es senalarlo para
 *       revision del area de riesgo.</li>
 * </ul>
 *
 * <p>Es {@code @StepScope} porque mantiene el estado de deduplicacion de la corrida: se
 * crea una instancia nueva por cada ejecucion del Step.</p>
 */
@Component
@StepScope
public class TransaccionItemProcessor implements ItemProcessor<TransaccionCsv, Transaccion> {

    private final PropiedadesBatch propiedades;
    private final RegistroRechazadoService bitacora;
    private final Long jobExecutionId;

    /**
     * Estado de deduplicacion, compartido por todas las particiones de la corrida.
     *
     * <p>Antes vivia como campos de este procesador y funcionaba porque habia una instancia por
     * ejecucion del Step. Con particiones hay una instancia POR PARTICION, asi que un duplicado
     * repartido entre dos particiones dejaria de detectarse. Ver {@link RegistroDeDuplicados},
     * que explica ademas por que ese estado compartido es un singleton particionado por corrida
     * y no un bean {@code @JobScope}.</p>
     */
    private final RegistroDeDuplicados duplicados;




    public TransaccionItemProcessor(PropiedadesBatch propiedades,
                                    RegistroRechazadoService bitacora,
                                    RegistroDeDuplicados duplicados,
                                    @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        this.propiedades = propiedades;
        this.bitacora = bitacora;
        this.duplicados = duplicados;
        this.jobExecutionId = jobExecutionId;
    }

    @Override
    public Transaccion process(TransaccionCsv fila) {
        String crudo = fila.comoLinea();
        int linea = fila.getNumeroLinea();
        List<String> observaciones = new ArrayList<>();
        boolean anomalia = false;

        Long idOrigen = ParseadorNumeros.parsearEnteroLargo(fila.getId())
                .orElseThrow(() -> new DatoInvalidoException("Identificador de transaccion invalido: "
                        + fila.getId(), crudo));

        // Mismo identificador en otra linea: la fila no aporta informacion nueva y se filtra.
        if (duplicados.esDuplicado(jobExecutionId, "transacciones.id", String.valueOf(idOrigen), linea)) {
            if (duplicados.primeraVezEnBitacora(jobExecutionId, Constantes.ARCHIVO_TRANSACCIONES, linea)) {
                bitacora.registrarFiltrado(Constantes.JOB_TRANSACCIONES_DIARIAS, Constantes.ARCHIVO_TRANSACCIONES,
                        linea, crudo, Constantes.MSG_DUPLICADO + "id=" + idOrigen, jobExecutionId);
            }
            return null;
        }

        LocalDate fecha = ParseadorFechas.parsear(fila.getFecha())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_FECHA_INVALIDA
                        + fila.getFecha(), crudo));
        if (!ParseadorFechas.vieneEnFormatoIso(fila.getFecha())) {
            observaciones.add("Fecha normalizada desde el formato legacy '" + fila.getFecha().trim() + "'");
        }

        BigDecimal monto = ParseadorNumeros.parsearMonto(fila.getMonto())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_MONTO_INVALIDO
                        + "'" + nvl(fila.getMonto()) + "'", crudo));

        String tipo = ParseadorNumeros.normalizarTexto(fila.getTipo());
        if (!Constantes.TIPOS_TRANSACCION.contains(tipo)) {
            throw new DatoInvalidoException(Constantes.MSG_TIPO_INVALIDO + "'" + tipo + "'", crudo);
        }

        if (monto.compareTo(BigDecimal.ZERO) <= 0) {
            anomalia = true;
            observaciones.add(Constantes.MSG_MONTO_NO_POSITIVO);
        } else if (monto.compareTo(propiedades.getUmbralMontoAtipico()) > 0) {
            anomalia = true;
            observaciones.add(Constantes.MSG_MONTO_ATIPICO + " (" + propiedades.getUmbralMontoAtipico() + ")");
        }

        // Misma fecha, mismo monto y mismo tipo con otro id: se migra igual, pero marcada.
        String claveContenido = fecha + "|" + monto.stripTrailingZeros().toPlainString() + "|" + tipo;
        if (duplicados.esDuplicado(jobExecutionId, "transacciones.contenido", claveContenido, linea)) {
            anomalia = true;
            observaciones.add(Constantes.MSG_POSIBLE_DUPLICADO);
        }

        Transaccion transaccion = new Transaccion(idOrigen, fecha, monto, tipo);
        transaccion.setAnomalia(anomalia);
        transaccion.setObservacion(observaciones.isEmpty() ? null : String.join("; ", observaciones));
        transaccion.setJobExecutionId(jobExecutionId);
        return transaccion;
    }

    private static String nvl(String valor) {
        return valor == null ? "" : valor.trim();
    }
}
