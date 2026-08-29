package com.bancoxyz.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Normalizacion de las cuatro convenciones de fecha que trae el sistema legacy. */
class ParseadorFechasTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2024-01-01, 2024-01-01",
            "2024/10/15, 2024-10-15",
            "03-04-2024, 2024-04-03",
            "19/06/2024, 2024-06-19",
            "  2024-05-22  , 2024-05-22"
    })
    @DisplayName("Interpreta los formatos legacy y los normaliza a ISO")
    void normalizaLosFormatosConocidos(String entrada, String esperada) {
        assertThat(ParseadorFechas.parsear(entrada)).contains(LocalDate.parse(esperada));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-13-01", "32-01-2024", "2024-02-30", "01-01-24", "hoy", "//"})
    @DisplayName("Rechaza fechas inexistentes o ilegibles en vez de corregirlas en silencio")
    void rechazaFechasInvalidas(String entrada) {
        assertThat(ParseadorFechas.parsear(entrada)).isEmpty();
    }

    @Test
    @DisplayName("Un valor vacio o nulo no es una fecha")
    void rechazaVacios() {
        assertThat(ParseadorFechas.parsear(null)).isEmpty();
        assertThat(ParseadorFechas.parsear("")).isEmpty();
        assertThat(ParseadorFechas.parsear("   ")).isEmpty();
    }

    @Test
    @DisplayName("Reconoce si la fecha ya venia en formato ISO para no marcarla como corregida")
    void detectaFormatoIso() {
        assertThat(ParseadorFechas.vieneEnFormatoIso("2024-01-01")).isTrue();
        assertThat(ParseadorFechas.vieneEnFormatoIso("2024/01/01")).isFalse();
        assertThat(ParseadorFechas.vieneEnFormatoIso(null)).isFalse();
    }

    @Test
    @DisplayName("Ante dd/MM ambiguo aplica la convencion chilena: dia primero")
    void aplicaConvencionChilena() {
        Optional<LocalDate> fecha = ParseadorFechas.parsear("12/02/2024");
        assertThat(fecha).contains(LocalDate.of(2024, 2, 12));
    }
}
