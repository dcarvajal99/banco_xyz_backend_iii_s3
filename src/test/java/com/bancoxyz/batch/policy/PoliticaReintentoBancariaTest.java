package com.bancoxyz.batch.policy;

import com.bancoxyz.config.PropiedadesBatch;
import com.bancoxyz.exception.DatoInvalidoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/** Que se reintenta, que no, y con que espera. */
class PoliticaReintentoBancariaTest {

    private PropiedadesBatch propiedades(int reintentos) {
        PropiedadesBatch p = new PropiedadesBatch();
        p.setLimiteReintentos(reintentos);
        return p;
    }

    /** Simula el ciclo de reintento: abre el contexto y va registrando los fallos. */
    private boolean sePuedeReintentarTras(RetryPolicy politica, Throwable error, int fallosPrevios) {
        RetryContext contexto = politica.open(null);
        for (int i = 0; i < fallosPrevios; i++) {
            politica.registerThrowable(contexto, error);
        }
        return politica.canRetry(contexto);
    }

    @Test
    @DisplayName("Un fallo transitorio de base de datos se reintenta hasta agotar el limite")
    void reintentaElFalloTransitorio() {
        RetryPolicy politica = PoliticaReintentoBancaria.politica(propiedades(3));
        Throwable transitorio = new TransientDataAccessResourceException("conexion perdida");

        assertThat(sePuedeReintentarTras(politica, transitorio, 1)).isTrue();
        assertThat(sePuedeReintentarTras(politica, transitorio, 2)).isTrue();
        // Al tercer intento se agota: el limite cuenta intentos totales, no reintentos extra.
        assertThat(sePuedeReintentarTras(politica, transitorio, 3)).isFalse();
    }

    @Test
    @DisplayName("La contencion entre hilos se reintenta: es el fallo tipico al paralelizar")
    void reintentaLaContencionEntreHilos() {
        RetryPolicy politica = PoliticaReintentoBancaria.politica(propiedades(3));

        assertThat(sePuedeReintentarTras(politica, new CannotAcquireLockException("bloqueo"), 1))
                .isTrue();
    }

    @Test
    @DisplayName("Un dato invalido NO se reintenta: seguiria invalido en el segundo intento")
    void noReintentaElDatoSucio() {
        RetryPolicy politica = PoliticaReintentoBancaria.politica(propiedades(3));

        assertThat(sePuedeReintentarTras(politica, new DatoInvalidoException("fecha ilegible", "x"), 1))
                .isFalse();
    }

    @Test
    @DisplayName("Una violacion de integridad NO se reintenta: es un problema de datos, no de infraestructura")
    void noReintentaLaViolacionDeIntegridad() {
        RetryPolicy politica = PoliticaReintentoBancaria.politica(propiedades(3));

        assertThat(sePuedeReintentarTras(politica, new DataIntegrityViolationException("clave duplicada"), 1))
                .isFalse();
    }

    @Test
    @DisplayName("La espera entre reintentos es creciente y toma sus valores de la configuracion")
    void configuraElBackoffExponencial() {
        PropiedadesBatch p = new PropiedadesBatch();
        p.setBackoffInicialMs(250);
        p.setBackoffMultiplicador(3.0);
        p.setBackoffMaximoMs(4000);

        ExponentialBackOffPolicy espera = (ExponentialBackOffPolicy) PoliticaReintentoBancaria.backoff(p);

        assertThat(espera.getInitialInterval()).isEqualTo(250L);
        assertThat(espera.getMultiplier()).isEqualTo(3.0);
        assertThat(espera.getMaxInterval()).isEqualTo(4000L);
        // Las esperas resultantes serian 250, 750 y 2250 ms: tiempo de sobra para que el hilo
        // que provoco la contencion commitee y suelte el bloqueo.
    }

    @Test
    @DisplayName("El catalogo de excepciones reintentables cubre la contencion y la caida transitoria")
    void declaraLasExcepcionesReintentables() {
        assertThat(PoliticaReintentoBancaria.excepcionesReintentables().values())
                .containsOnly(true);
        assertThat(PoliticaReintentoBancaria.excepcionesReintentables()).hasSize(5);
    }
}
