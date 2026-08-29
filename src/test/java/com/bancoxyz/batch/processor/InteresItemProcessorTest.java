package com.bancoxyz.batch.processor;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import com.bancoxyz.dto.InteresCsv;
import com.bancoxyz.entity.CuentaInteres;
import com.bancoxyz.exception.DatoInvalidoException;
import com.bancoxyz.service.RegistroRechazadoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Reglas de calculo de interes y de calidad de datos del Job 2. */
@ExtendWith(MockitoExtension.class)
class InteresItemProcessorTest {

    private static final Long EJECUCION = 77L;

    @Mock
    private RegistroRechazadoService bitacora;

    private InteresItemProcessor procesador;

    @BeforeEach
    void prepararProcesador() {
        procesador = new InteresItemProcessor(new PropiedadesBatch(), bitacora, new RegistroDeDuplicados(), EJECUCION);
    }

    private static InteresCsv fila(int linea, String cuenta, String nombre, String saldo, String edad, String tipo) {
        InteresCsv csv = new InteresCsv();
        csv.setNumeroLinea(linea);
        csv.setCuentaId(cuenta);
        csv.setNombre(nombre);
        csv.setSaldo(saldo);
        csv.setEdad(edad);
        csv.setTipo(tipo);
        return csv;
    }

    @Test
    @DisplayName("Cuenta de ahorro: abona 0,5% mensual sobre el saldo")
    void calculaInteresDeAhorro() {
        CuentaInteres cuenta = procesador.process(fila(2, "101", "John Doe", "5000", "30", "ahorro"));

        assertThat(cuenta.getTasaMensual()).isEqualByComparingTo("0.00500");
        assertThat(cuenta.getInteres()).isEqualByComparingTo("25.00");
        assertThat(cuenta.getSaldoFinal()).isEqualByComparingTo("5025.00");
        assertThat(cuenta.isAnomalia()).isFalse();
        verifyNoInteractions(bitacora);
    }

    @Test
    @DisplayName("Prestamo: carga 1,5% mensual y la deuda final crece")
    void calculaInteresDePrestamo() {
        CuentaInteres cuenta = procesador.process(fila(2, "102", "Jane Smith", "8000", "25", "prestamo"));

        assertThat(cuenta.getTasaMensual()).isEqualByComparingTo("0.01500");
        assertThat(cuenta.getInteres()).isEqualByComparingTo("120.00");
        assertThat(cuenta.getSaldoFinal()).isEqualByComparingTo("8120.00");
    }

    @Test
    @DisplayName("Hipoteca: carga 0,9% mensual")
    void calculaInteresDeHipoteca() {
        CuentaInteres cuenta = procesador.process(fila(2, "105", "Charlie Green", "7000", "35", "hipoteca"));

        assertThat(cuenta.getTasaMensual()).isEqualByComparingTo("0.00900");
        assertThat(cuenta.getInteres()).isEqualByComparingTo("63.00");
    }

    @Test
    @DisplayName("Ahorro de tercera edad: suma la bonificacion a la tasa base")
    void aplicaBonificacionDeTerceraEdad() {
        CuentaInteres cuenta = procesador.process(fila(2, "108", "Steve Rogers", "10000", "65", "ahorro"));

        assertThat(cuenta.getTasaMensual()).isEqualByComparingTo("0.00600");
        assertThat(cuenta.getInteres()).isEqualByComparingTo("60.00");
        assertThat(cuenta.getObservacion()).contains("Bonificacion de tercera edad");
    }

    @Test
    @DisplayName("La bonificacion no aplica a prestamos aunque el titular sea mayor")
    void noBonificaPrestamos() {
        CuentaInteres cuenta = procesador.process(fila(2, "109", "Diana Prince", "10000", "70", "prestamo"));

        assertThat(cuenta.getTasaMensual()).isEqualByComparingTo("0.01500");
    }

    @Test
    @DisplayName("Saldo en cero: se procesa con interes cero y queda marcado")
    void marcaSaldoCero() {
        CuentaInteres cuenta = procesador.process(fila(2, "104", "Alice Brown", "0", "45", "ahorro"));

        assertThat(cuenta.getInteres()).isEqualByComparingTo("0.00");
        assertThat(cuenta.isAnomalia()).isTrue();
        assertThat(cuenta.getObservacion()).contains(Constantes.MSG_SALDO_CERO);
    }

    @Test
    @DisplayName("Titular 'Unknown': se normaliza y se marca como anomalia")
    void normalizaTitularDesconocido() {
        CuentaInteres cuenta = procesador.process(fila(2, "114", "Unknown", "6000", "30", "ahorro"));

        assertThat(cuenta.getNombre()).isEqualTo("Sin identificar");
        assertThat(cuenta.isAnomalia()).isTrue();
    }

    @Test
    @DisplayName("Tipo de cuenta '-1': se rechaza")
    void rechazaTipoInvalido() {
        assertThatThrownBy(() -> procesador.process(fila(2, "103", "Bob", "12000", "30", "-1")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_TIPO_INVALIDO);
    }

    @Test
    @DisplayName("Saldo vacio: se rechaza")
    void rechazaSaldoVacio() {
        assertThatThrownBy(() -> procesador.process(fila(2, "104", "Alice", "", "45", "ahorro")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_SALDO_INVALIDO);
    }

    @Test
    @DisplayName("Edad fuera del rango 18-99: se rechaza")
    void rechazaEdadFueraDeRango() {
        assertThatThrownBy(() -> procesador.process(fila(2, "107", "Steve", "10000", "100", "ahorro")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_EDAD_INVALIDA);
        assertThatThrownBy(() -> procesador.process(fila(3, "110", "Nino", "1000", "12", "ahorro")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_EDAD_INVALIDA);
    }

    @Test
    @DisplayName("Edad vacia: se rechaza")
    void rechazaEdadVacia() {
        assertThatThrownBy(() -> procesador.process(fila(2, "137", "Bob", "7000", "", "prestamo")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_EDAD_INVALIDA);
    }

    @Test
    @DisplayName("Fila identica repetida: se filtra y queda en la bitacora")
    void filtraFilaIdentica() {
        procesador.process(fila(2, "101", "John Doe", "5000", "30", "ahorro"));
        CuentaInteres repetida = procesador.process(fila(10, "101", "John Doe", "5000", "30", "ahorro"));

        assertThat(repetida).isNull();
        verify(bitacora).registrarFiltrado(eq(Constantes.JOB_INTERESES_MENSUALES),
                eq(Constantes.ARCHIVO_INTERESES), eq(10), anyString(), anyString(), eq(EJECUCION));
    }

    @Test
    @DisplayName("Cuenta repetida con datos distintos: se procesa igual, pero marcada")
    void marcaCuentaRepetidaConDatosDistintos() {
        procesador.process(fila(2, "133", "Alice Brown", "12000", "40", "hipoteca"));
        CuentaInteres segunda = procesador.process(fila(11, "133", "Steve Rogers", "12000", "40", "prestamo"));

        assertThat(segunda).isNotNull();
        assertThat(segunda.isAnomalia()).isTrue();
        assertThat(segunda.getObservacion()).contains(Constantes.MSG_CUENTA_REPETIDA);
    }

    @Test
    @DisplayName("Mismo titular con los mismos datos en otra cuenta: se marca")
    void marcaTitularRepetido() {
        procesador.process(fila(2, "101", "John Doe", "5000", "30", "ahorro"));
        CuentaInteres otra = procesador.process(fila(7, "106", "John Doe", "5000", "30", "ahorro"));

        assertThat(otra.isAnomalia()).isTrue();
        assertThat(otra.getObservacion()).contains(Constantes.MSG_TITULAR_REPETIDO);
    }
}
