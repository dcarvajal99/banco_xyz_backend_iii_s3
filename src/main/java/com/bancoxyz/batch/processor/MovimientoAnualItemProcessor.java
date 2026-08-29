package com.bancoxyz.batch.processor;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.dto.MovimientoAnualCsv;
import com.bancoxyz.entity.MovimientoAnual;
import com.bancoxyz.exception.DatoInvalidoException;
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
 * Normaliza cada movimiento de {@code cuentas_anuales.csv} (Job 3).
 *
 * <p>La correccion mas importante es el <b>signo del monto</b>: el sistema legacy escribia
 * los retiros a veces en positivo y a veces en negativo, de modo que sumar la columna daba
 * un saldo sin sentido. Aqui el signo se deriva del tipo de movimiento y no del dato de
 * origen, y el estado de cuenta pasa a ser una simple suma.</p>
 */
@Component
@StepScope
public class MovimientoAnualItemProcessor implements ItemProcessor<MovimientoAnualCsv, MovimientoAnual> {

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


    public MovimientoAnualItemProcessor(RegistroDeDuplicados duplicados,
                                        @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        this.duplicados = duplicados;
        this.jobExecutionId = jobExecutionId;
    }

    @Override
    public MovimientoAnual process(MovimientoAnualCsv fila) {
        String crudo = fila.comoLinea();
        List<String> observaciones = new ArrayList<>();
        boolean anomalia = false;

        Long cuentaId = ParseadorNumeros.parsearEnteroLargo(fila.getCuentaId())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_CUENTA_INVALIDA
                        + fila.getCuentaId(), crudo));

        LocalDate fecha = ParseadorFechas.parsear(fila.getFecha())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_FECHA_INVALIDA
                        + fila.getFecha(), crudo));
        if (!ParseadorFechas.vieneEnFormatoIso(fila.getFecha())) {
            observaciones.add("Fecha normalizada desde el formato legacy '" + fila.getFecha().trim() + "'");
        }

        String tipo = ParseadorNumeros.normalizarTexto(fila.getTransaccion());
        if (!Constantes.TIPOS_MOVIMIENTO.contains(tipo)) {
            throw new DatoInvalidoException(Constantes.MSG_TIPO_INVALIDO + "'" + tipo + "'", crudo);
        }
        // El archivo legacy escribe el mismo movimiento con y sin tilde: se corrige y se anota.
        if (ParseadorNumeros.tieneDiacriticos(fila.getTransaccion())) {
            observaciones.add(Constantes.MSG_TIPO_NORMALIZADO + "'" + fila.getTransaccion().trim() + "'");
        }

        BigDecimal montoOriginal = ParseadorNumeros.parsearMonto(fila.getMonto())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_MONTO_INVALIDO
                        + "'" + nvl(fila.getMonto()) + "'", crudo));

        boolean esCargo = Constantes.MOVIMIENTOS_DE_CARGO.contains(tipo);
        BigDecimal monto = esCargo ? montoOriginal.abs().negate() : montoOriginal.abs();
        if (monto.compareTo(montoOriginal) != 0) {
            observaciones.add(Constantes.MSG_SIGNO_CORREGIDO);
            // Un deposito cargado en negativo es un error de captura del sistema legacy,
            // no una simple diferencia de convencion: se marca para revision.
            if (!esCargo && montoOriginal.compareTo(BigDecimal.ZERO) < 0) {
                anomalia = true;
            }
        }
        if (monto.compareTo(BigDecimal.ZERO) == 0) {
            anomalia = true;
            observaciones.add(Constantes.MSG_MONTO_NO_POSITIVO);
        }

        String descripcion = nvl(fila.getDescripcion());
        if (descripcion.isBlank()) {
            descripcion = Constantes.DESCRIPCION_POR_DEFECTO;
            observaciones.add(Constantes.MSG_DESCRIPCION_COMPLETADA);
        }

        String clave = cuentaId + "|" + fecha + "|" + tipo + "|"
                + monto.stripTrailingZeros().toPlainString() + "|" + descripcion.toLowerCase();
        if (duplicados.esDuplicado(jobExecutionId, "movimientos.contenido", clave, fila.getNumeroLinea())) {
            anomalia = true;
            observaciones.add(Constantes.MSG_POSIBLE_DUPLICADO);
        }

        MovimientoAnual movimiento = new MovimientoAnual();
        movimiento.setCuentaId(cuentaId);
        movimiento.setFecha(fecha);
        movimiento.setTipoTransaccion(tipo);
        movimiento.setMonto(monto.setScale(2, java.math.RoundingMode.HALF_UP));
        movimiento.setDescripcion(descripcion);
        movimiento.setAnomalia(anomalia);
        movimiento.setObservacion(observaciones.isEmpty() ? null : String.join("; ", observaciones));
        movimiento.setJobExecutionId(jobExecutionId);
        return movimiento;
    }

    private static String nvl(String valor) {
        return valor == null ? "" : valor.trim();
    }
}
