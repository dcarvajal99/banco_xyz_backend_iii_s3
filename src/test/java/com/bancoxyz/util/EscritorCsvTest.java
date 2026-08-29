package com.bancoxyz.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Formato de los archivos de salida que consume el area de auditoria. */
class EscritorCsvTest {

    @Test
    @DisplayName("Escribe la cabecera y las filas creando la carpeta destino")
    void escribeElArchivoCreandoLaCarpeta(@TempDir Path carpeta) throws Exception {
        Path destino = carpeta.resolve("reportes").resolve("salida.csv");

        EscritorCsv.escribir(destino, "a,b", List.of("1,2", "3,4"));

        assertThat(destino).exists();
        assertThat(Files.readAllLines(destino)).containsExactly("a,b", "1,2", "3,4");
    }

    @Test
    @DisplayName("Entrecomilla los campos que traen comas o comillas")
    void escapaCamposConSeparadores() {
        assertThat(EscritorCsv.fila("101", "Ingreso mensual")).isEqualTo("101,Ingreso mensual");
        assertThat(EscritorCsv.fila("101", "Retiro, parcial"))
                .isEqualTo("101,\"Retiro, parcial\"");
        assertThat(EscritorCsv.fila("101", "Pago \"urgente\""))
                .isEqualTo("101,\"Pago \"\"urgente\"\"\"");
    }

    @Test
    @DisplayName("Un campo nulo se escribe vacio, no como la palabra null")
    void escribeNulosComoVacio() {
        assertThat(EscritorCsv.fila("101", null, "x")).isEqualTo("101,,x");
    }

    @Test
    @DisplayName("Los importes salen siempre con dos decimales")
    void formateaImportes() {
        assertThat(EscritorCsv.importe(BigDecimal.ZERO)).isEqualTo("0.00");
        assertThat(EscritorCsv.importe(new BigDecimal("1500"))).isEqualTo("1500.00");
        assertThat(EscritorCsv.importe(new BigDecimal("1500.555"))).isEqualTo("1500.56");
        assertThat(EscritorCsv.importe(null)).isEqualTo("0.00");
    }
}
