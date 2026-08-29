package com.bancoxyz.batch.processor;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import com.bancoxyz.dto.TransaccionCsv;
import com.bancoxyz.entity.Transaccion;
import com.bancoxyz.exception.DatoInvalidoException;
import com.bancoxyz.service.RegistroRechazadoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Reglas de validacion, correccion y clasificacion del Job 1. */
@ExtendWith(MockitoExtension.class)
class TransaccionItemProcessorTest {

    private static final Long EJECUCION = 99L;

    @Mock
    private RegistroRechazadoService bitacora;

    private TransaccionItemProcessor procesador;

    @BeforeEach
    void prepararProcesador() {
        procesador = new TransaccionItemProcessor(new PropiedadesBatch(), bitacora, new RegistroDeDuplicados(), EJECUCION);
    }

    private static TransaccionCsv fila(int linea, String id, String fecha, String monto, String tipo) {
        TransaccionCsv csv = new TransaccionCsv();
        csv.setNumeroLinea(linea);
        csv.setId(id);
        csv.setFecha(fecha);
        csv.setMonto(monto);
        csv.setTipo(tipo);
        return csv;
    }

    @Test
    @DisplayName("Migra una transaccion valida sin marcarla como anomalia")
    void migraTransaccionValida() {
        Transaccion resultado = procesador.process(fila(2, "1", "2024-01-01", "1000", "debito"));

        assertThat(resultado).isNotNull();
        assertThat(resultado.getIdOrigen()).isEqualTo(1L);
        assertThat(resultado.getFecha()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(resultado.getMonto()).isEqualByComparingTo("1000");
        assertThat(resultado.getTipo()).isEqualTo("debito");
        assertThat(resultado.isAnomalia()).isFalse();
        assertThat(resultado.getObservacion()).isNull();
        assertThat(resultado.getJobExecutionId()).isEqualTo(EJECUCION);
        verifyNoInteractions(bitacora);
    }

    @Test
    @DisplayName("Normaliza la fecha legacy y lo deja anotado sin marcar anomalia")
    void normalizaFechaLegacy() {
        Transaccion resultado = procesador.process(fila(2, "1", "2024/01/02", "1500", "CREDITO"));

        assertThat(resultado.getFecha()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(resultado.getTipo()).isEqualTo("credito");
        assertThat(resultado.isAnomalia()).isFalse();
        assertThat(resultado.getObservacion()).contains("Fecha normalizada");
    }

    @Test
    @DisplayName("Un monto no positivo se migra marcado como anomalia, no se descarta")
    void marcaMontoNoPositivo() {
        Transaccion resultado = procesador.process(fila(2, "3", "2024-01-03", "-200", "debito"));

        assertThat(resultado.isAnomalia()).isTrue();
        assertThat(resultado.getObservacion()).contains(Constantes.MSG_MONTO_NO_POSITIVO);
    }

    @Test
    @DisplayName("Un monto sobre el umbral queda marcado para revision de riesgo")
    void marcaMontoAtipico() {
        Transaccion resultado = procesador.process(fila(2, "9", "2024-01-07", "3000", "debito"));

        assertThat(resultado.isAnomalia()).isTrue();
        assertThat(resultado.getObservacion()).contains(Constantes.MSG_MONTO_ATIPICO);
    }

    @Test
    @DisplayName("Fecha ilegible: se rechaza con DatoInvalidoException")
    void rechazaFechaIlegible() {
        assertThatThrownBy(() -> procesador.process(fila(2, "5", "2024-13-01", "800", "credito")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_FECHA_INVALIDA);
    }

    @Test
    @DisplayName("Monto vacio: se rechaza con DatoInvalidoException")
    void rechazaMontoVacio() {
        assertThatThrownBy(() -> procesador.process(fila(2, "4", "2024-01-03", "", "debito")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_MONTO_INVALIDO);
    }

    @Test
    @DisplayName("Tipo fuera del catalogo: se rechaza con DatoInvalidoException")
    void rechazaTipoDesconocido() {
        assertThatThrownBy(() -> procesador.process(fila(2, "6", "2024-01-05", "700", "invalid")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining(Constantes.MSG_TIPO_INVALIDO);
    }

    @Test
    @DisplayName("Identificador no numerico: se rechaza con DatoInvalidoException")
    void rechazaIdentificadorInvalido() {
        assertThatThrownBy(() -> procesador.process(fila(2, "abc", "2024-01-05", "700", "debito")))
                .isInstanceOf(DatoInvalidoException.class)
                .hasMessageContaining("Identificador de transaccion invalido");
    }

    @Test
    @DisplayName("Id repetido en otra linea: se filtra y queda en la bitacora")
    void filtraIdentificadorRepetido() {
        procesador.process(fila(2, "1", "2024-01-01", "1000", "debito"));
        Transaccion repetida = procesador.process(fila(7, "1", "2024-02-01", "500", "credito"));

        assertThat(repetida).isNull();
        verify(bitacora).registrarFiltrado(eq(Constantes.JOB_TRANSACCIONES_DIARIAS),
                eq(Constantes.ARCHIVO_TRANSACCIONES), eq(7), anyString(), anyString(), eq(EJECUCION));
    }

    @Test
    @DisplayName("Reprocesar la misma linea tras un rollback no la trata como duplicada")
    void toleraReprocesoDeLaMismaLinea() {
        procesador.process(fila(2, "1", "2024-01-01", "1000", "debito"));
        Transaccion reprocesada = procesador.process(fila(2, "1", "2024-01-01", "1000", "debito"));

        assertThat(reprocesada).isNotNull();
        assertThat(reprocesada.isAnomalia()).isFalse();
        verifyNoInteractions(bitacora);
    }

    @Test
    @DisplayName("Misma fecha, monto y tipo con otro id: se migra marcada como posible duplicado")
    void marcaDuplicadoPorContenido() {
        procesador.process(fila(2, "6", "2024-01-05", "700", "debito"));
        Transaccion segunda = procesador.process(fila(9, "8", "2024-01-05", "700", "debito"));

        assertThat(segunda).isNotNull();
        assertThat(segunda.isAnomalia()).isTrue();
        assertThat(segunda.getObservacion()).contains(Constantes.MSG_POSIBLE_DUPLICADO);
        verify(bitacora, times(0)).registrarFiltrado(anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("El umbral de monto atipico es configurable")
    void respetaElUmbralConfigurado() {
        PropiedadesBatch propiedades = new PropiedadesBatch();
        propiedades.setUmbralMontoAtipico(new BigDecimal("500"));
        TransaccionItemProcessor estricto = new TransaccionItemProcessor(propiedades, bitacora, new RegistroDeDuplicados(), EJECUCION);

        assertThat(estricto.process(fila(2, "1", "2024-01-01", "700", "debito")).isAnomalia()).isTrue();
    }
}
