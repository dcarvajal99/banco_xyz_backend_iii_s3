package com.bancoxyz.batch.processor;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import com.bancoxyz.dto.InteresCsv;
import com.bancoxyz.entity.CuentaInteres;
import com.bancoxyz.exception.DatoInvalidoException;
import com.bancoxyz.service.RegistroRechazadoService;
import com.bancoxyz.util.ParseadorNumeros;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Aplica el interes mensual sobre cada cuenta de {@code intereses.csv} (Job 2).
 *
 * <p>El calculo se guarda desglosado (saldo inicial, tasa, interes, saldo final) para que
 * auditoria pueda rehacerlo sin volver a correr el batch. La aritmetica usa
 * {@link BigDecimal} con redondeo {@link RoundingMode#HALF_UP} a dos decimales, que es el
 * criterio de redondeo bancario que aplicaba el sistema COBOL.</p>
 *
 * <p>Semantica del saldo segun el tipo de cuenta:</p>
 * <ul>
 *   <li><b>ahorro</b>: el saldo es a favor del cliente y el interes se abona.</li>
 *   <li><b>prestamo</b> y <b>hipoteca</b>: el saldo es la deuda vigente y el interes se
 *       carga, por lo que el saldo final tambien crece.</li>
 * </ul>
 *
 * <p>Criterio sobre duplicados: solo se descarta la fila <b>identica</b> en todos sus
 * campos. Una cuenta que aparece dos veces con datos distintos es una inconsistencia del
 * origen, no una copia: se procesa igual y se marca como anomalia, porque descartarla
 * seria decidir por el negocio cual de los dos saldos es el bueno.</p>
 */
@Component
@StepScope
public class InteresItemProcessor implements ItemProcessor<InteresCsv, CuentaInteres> {

    private static final String TITULAR_SIN_IDENTIFICAR = "Sin identificar";

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




    public InteresItemProcessor(PropiedadesBatch propiedades,
                                RegistroRechazadoService bitacora,
                                RegistroDeDuplicados duplicados,
                                @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        this.propiedades = propiedades;
        this.bitacora = bitacora;
        this.duplicados = duplicados;
        this.jobExecutionId = jobExecutionId;
    }

    @Override
    public CuentaInteres process(InteresCsv fila) {
        String crudo = fila.comoLinea();
        int linea = fila.getNumeroLinea();
        List<String> observaciones = new ArrayList<>();
        boolean anomalia = false;

        if (duplicados.esDuplicado(jobExecutionId, "intereses.filaCompleta", crudo, linea)) {
            if (duplicados.primeraVezEnBitacora(jobExecutionId, Constantes.ARCHIVO_INTERESES, linea)) {
                bitacora.registrarFiltrado(Constantes.JOB_INTERESES_MENSUALES, Constantes.ARCHIVO_INTERESES,
                        linea, crudo, Constantes.MSG_DUPLICADO + "fila identica", jobExecutionId);
            }
            return null;
        }

        Long cuentaId = ParseadorNumeros.parsearEnteroLargo(fila.getCuentaId())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_CUENTA_INVALIDA
                        + fila.getCuentaId(), crudo));

        String tipo = ParseadorNumeros.normalizarTexto(fila.getTipo());
        if (!Constantes.TIPOS_CUENTA.contains(tipo)) {
            throw new DatoInvalidoException(Constantes.MSG_TIPO_INVALIDO + "'" + tipo + "'", crudo);
        }

        BigDecimal saldo = ParseadorNumeros.parsearMonto(fila.getSaldo())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_SALDO_INVALIDO
                        + "'" + nvl(fila.getSaldo()) + "'", crudo));
        if (saldo.compareTo(BigDecimal.ZERO) < 0) {
            throw new DatoInvalidoException("Saldo negativo no admitido: " + saldo, crudo);
        }
        if (saldo.compareTo(BigDecimal.ZERO) == 0) {
            anomalia = true;
            observaciones.add(Constantes.MSG_SALDO_CERO);
        }

        int edad = ParseadorNumeros.parsearEntero(fila.getEdad())
                .orElseThrow(() -> new DatoInvalidoException(Constantes.MSG_EDAD_INVALIDA
                        + "'" + nvl(fila.getEdad()) + "'", crudo));
        if (edad < Constantes.EDAD_MINIMA || edad > Constantes.EDAD_MAXIMA) {
            throw new DatoInvalidoException(Constantes.MSG_EDAD_INVALIDA + edad
                    + " (rango permitido " + Constantes.EDAD_MINIMA + "-" + Constantes.EDAD_MAXIMA + ")", crudo);
        }

        String nombre = nvl(fila.getNombre());
        if (nombre.isBlank() || "unknown".equalsIgnoreCase(nombre)) {
            nombre = TITULAR_SIN_IDENTIFICAR;
            anomalia = true;
            observaciones.add("Titular sin identificar en el archivo legacy");
        }

        if (duplicados.esDuplicado(jobExecutionId, "intereses.cuenta", String.valueOf(cuentaId), linea)) {
            anomalia = true;
            observaciones.add(Constantes.MSG_CUENTA_REPETIDA);
        }

        String claveTitular = nombre.toLowerCase() + "|" + saldo.stripTrailingZeros().toPlainString()
                + "|" + edad + "|" + tipo;
        if (duplicados.esDuplicado(jobExecutionId, "intereses.titular", claveTitular, linea)) {
            anomalia = true;
            observaciones.add(Constantes.MSG_TITULAR_REPETIDO);
        }

        BigDecimal tasa = tasaBase(tipo);
        if ("ahorro".equals(tipo) && edad >= Constantes.EDAD_TERCERA_EDAD) {
            tasa = tasa.add(propiedades.getBonificacionTerceraEdad());
            observaciones.add("Bonificacion de tercera edad aplicada (+"
                    + propiedades.getBonificacionTerceraEdad() + ")");
        }

        BigDecimal interes = saldo.multiply(tasa).setScale(2, RoundingMode.HALF_UP);
        BigDecimal saldoFinal = saldo.add(interes).setScale(2, RoundingMode.HALF_UP);

        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(cuentaId);
        cuenta.setNombre(nombre);
        cuenta.setTipo(tipo);
        cuenta.setEdad(edad);
        cuenta.setSaldoInicial(saldo.setScale(2, RoundingMode.HALF_UP));
        cuenta.setTasaMensual(tasa);
        cuenta.setInteres(interes);
        cuenta.setSaldoFinal(saldoFinal);
        cuenta.setAnomalia(anomalia);
        cuenta.setObservacion(observaciones.isEmpty() ? null : String.join("; ", observaciones));
        cuenta.setJobExecutionId(jobExecutionId);
        return cuenta;
    }

    private BigDecimal tasaBase(String tipo) {
        return switch (tipo) {
            case "ahorro" -> propiedades.getTasaAhorro();
            case "prestamo" -> propiedades.getTasaPrestamo();
            case "hipoteca" -> propiedades.getTasaHipoteca();
            default -> BigDecimal.ZERO;
        };
    }

    private static String nvl(String valor) {
        return valor == null ? "" : valor.trim();
    }
}
