package com.bancoxyz.batch.processor;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.dto.MovimientoAnualCsv;
import com.bancoxyz.entity.MovimientoAnual;
import com.bancoxyz.exception.DatoInvalidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Normalizacion de signo, fechas y descripciones del Job 3. */
class MovimientoAnualItemProcessorTest {

    private static final Long EJECUCION = 55L;

    private MovimientoAnualItemProcessor procesador;

    @BeforeEach
    void prepararProcesador() {
        procesador = new MovimientoAnualItemProcessor(new RegistroDeDuplicados(), EJECUCION);
    }

    private static MovimientoAnualCsv fila(int linea, String cuenta, String fecha,
                                           String tipo, String monto, String descripcion) {
        MovimientoAnualCsv csv = new MovimientoAnualCsv();
        csv.setNumeroLinea(linea);
        csv.setCuentaId(cuenta);
        csv.setFecha(fecha);
        csv.setTransaccion(tipo);
        csv.setMonto(monto);
        csv.setDescripcion(descripcion);
        return csv;
    }

    @Test
    @DisplayName("Un deposito conserva el signo positivo y calcula el ano")
    void migraDepositoValido() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "101", "2024-01-01", "deposito", "1000", "Ingreso mensual"));

        assertThat(movimiento.getMonto()).isEqualByComparingTo("1000.00");
        assertThat(movimiento.getAnio()).isEqualTo(2024);
        assertThat(movimiento.getFecha()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(movimiento.isAnomalia()).isFalse();
        assertThat(movimiento.getJobExecutionId()).isEqualTo(EJECUCION);
    }

    @Test
    @DisplayName("Un retiro cargado en positivo se normaliza a negativo")
    void normalizaSignoDeRetiroPositivo() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "110", "2024-07-24", "retiro", "1500", "Retiro"));

        assertThat(movimiento.getMonto()).isEqualByComparingTo("-1500.00");
        assertThat(movimiento.getObservacion()).contains(Constantes.MSG_SIGNO_CORREGIDO);
        assertThat(movimiento.isAnomalia()).isFalse();
    }

    @Test
    @DisplayName("Un retiro que ya venia negativo no se toca")
    void respetaRetiroYaNegativo() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "101", "2024-03-15", "retiro", "-500", "Retiro parcial"));

        assertThat(movimiento.getMonto()).isEqualByComparingTo("-500.00");
        assertThat(movimiento.getObservacion()).isNull();
    }

    @Test
    @DisplayName("Un deposito cargado en negativo se corrige Y se marca como anomalia")
    void marcaDepositoNegativo() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "106", "2024-02-12", "deposito", "-100", "Ingreso navideno"));

        assertThat(movimiento.getMonto()).isEqualByComparingTo("100.00");
        assertThat(movimiento.isAnomalia()).isTrue();
        assertThat(movimiento.getObservacion()).contains(Constantes.MSG_SIGNO_CORREGIDO);
    }

    @Test
    @DisplayName("Monto cero: se migra marcado como anomalia")
    void marcaMontoCero() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "107", "2024-12-25", "deposito", "0", "Ingreso navideno"));

        assertThat(movimiento.isAnomalia()).isTrue();
        assertThat(movimiento.getObservacion()).contains(Constantes.MSG_MONTO_NO_POSITIVO);
    }

    @Test
    @DisplayName("Descripcion vacia: se completa por el proceso (enriquecimiento)")
    void completaDescripcionVacia() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "110", "2024-07-24", "retiro", "-1500", ""));

        assertThat(movimiento.getDescripcion()).isEqualTo(Constantes.DESCRIPCION_POR_DEFECTO);
        assertThat(movimiento.getObservacion()).contains(Constantes.MSG_DESCRIPCION_COMPLETADA);
    }

    @Test
    @DisplayName("Fecha en formato legacy: se normaliza y queda anotado")
    void normalizaFechaLegacy() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "102", "22/05/2024", "deposito", "1500", "Ingreso mensual"));

        assertThat(movimiento.getFecha()).isEqualTo(LocalDate.of(2024, 5, 22));
        assertThat(movimiento.getObservacion()).contains("Fecha normalizada");
    }

    @Test
    @DisplayName("Un 'deposito' escrito con tilde se corrige y se migra, no se descarta")
    void normalizaElTipoConTilde() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "111", "2024-09-25", "dep\u00f3sito", "2000", "Ingreso navideno"));

        assertThat(movimiento.getTipoTransaccion()).isEqualTo("deposito");
        assertThat(movimiento.getMonto()).isEqualByComparingTo("2000.00");
        assertThat(movimiento.getObservacion()).contains(Constantes.MSG_TIPO_NORMALIZADO);
        assertThat(movimiento.isAnomalia()).isFalse();
    }

    @Test
    @DisplayName("Un tipo sin tilde no genera la observacion de normalizacion")
    void noAnotaNormalizacionSiNoHabiaTilde() {
        MovimientoAnual movimiento = procesador.process(
                fila(2, "111", "2024-09-25", "DEPOSITO", "2000", "Ingreso navideno"));

        assertThat(movimiento.getTipoTransaccion()).isEqualTo("deposito");
        assertThat(movimiento.getObservacion()).isNull();
    }

    @Test
    @DisplayName("Tipo de movimiento fuera del catalogo: se rechaza")
    void rechazaTipoDesconocido() {
        assertThatThrownBy(() -> procesador.process(
                fila(2, "103", "2024-07-10", "transferencia", "2000", "Ingreso")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_TIPO_INVALIDO);
    }

    @Test
    @DisplayName("Monto vacio: se rechaza")
    void rechazaMontoVacio() {
        assertThatThrownBy(() -> procesador.process(
                fila(2, "120", "2024-04-11", "compra", "", "Ingreso mensual")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_MONTO_INVALIDO);
    }

    @Test
    @DisplayName("Fecha ilegible: se rechaza")
    void rechazaFechaIlegible() {
        assertThatThrownBy(() -> procesador.process(
                fila(2, "120", "2024-13-11", "compra", "1000", "Compra")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_FECHA_INVALIDA);
    }

    @Test
    @DisplayName("Movimiento identico repetido: se migra marcado como posible duplicado")
    void marcaMovimientoDuplicado() {
        procesador.process(fila(2, "101", "2024-01-01", "deposito", "1000", "Ingreso mensual"));
        MovimientoAnual segundo = procesador.process(
                fila(9, "101", "2024-01-01", "deposito", "1000", "Ingreso mensual"));

        assertThat(segundo.isAnomalia()).isTrue();
        assertThat(segundo.getObservacion()).contains(Constantes.MSG_POSIBLE_DUPLICADO);
    }

    @Test
    @DisplayName("Reprocesar la misma linea no la marca como duplicada")
    void toleraReprocesoDeLaMismaLinea() {
        procesador.process(fila(2, "101", "2024-01-01", "deposito", "1000", "Ingreso mensual"));
        MovimientoAnual reprocesado = procesador.process(
                fila(2, "101", "2024-01-01", "deposito", "1000", "Ingreso mensual"));

        assertThat(reprocesado.isAnomalia()).isFalse();
    }
}
