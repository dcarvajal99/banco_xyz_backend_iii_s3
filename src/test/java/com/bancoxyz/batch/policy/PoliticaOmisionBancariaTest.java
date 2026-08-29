package com.bancoxyz.batch.policy;

import com.bancoxyz.exception.DatoInvalidoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La politica solo puede tolerar problemas del dato, nunca de la infraestructura. */
class PoliticaOmisionBancariaTest {

    private final PoliticaOmisionBancaria politica = new PoliticaOmisionBancaria(3);

    @Test
    @DisplayName("Omite una fila rechazada por las validaciones de negocio")
    void omiteDatoInvalido() {
        assertThat(politica.shouldSkip(new DatoInvalidoException("tipo invalido", "x"), 0)).isTrue();
    }

    @Test
    @DisplayName("Omite una fila que no se pudo tokenizar")
    void omiteErrorDeParseo() {
        assertThat(politica.shouldSkip(new FlatFileParseException("mal formada", "1,2"), 1)).isTrue();
    }

    @Test
    @DisplayName("Tambien omite cuando el dato invalido viene envuelto en otra excepcion")
    void omiteDatoInvalidoEnvuelto() {
        RuntimeException envuelta = new RuntimeException("fallo", new DatoInvalidoException("edad", "x"));
        assertThat(politica.shouldSkip(envuelta, 0)).isTrue();
    }

    @Test
    @DisplayName("No omite un fallo de base de datos: la migracion debe detenerse")
    void noOmiteFallosDeInfraestructura() {
        assertThat(politica.shouldSkip(new DataAccessResourceFailureException("sin conexion"), 0)).isFalse();
    }

    @Test
    @DisplayName("Al superar el tope de omisiones detiene el Step")
    void detieneAlSuperarElTope() {
        assertThatThrownBy(() -> politica.shouldSkip(new DatoInvalidoException("x", "y"), 3))
                .isInstanceOf(SkipLimitExceededException.class);
    }
}
