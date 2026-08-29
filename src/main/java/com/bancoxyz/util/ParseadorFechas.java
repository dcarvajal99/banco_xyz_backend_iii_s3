package com.bancoxyz.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;

/**
 * Normaliza a {@link LocalDate} las fechas del sistema legacy, que llegan en cuatro
 * formatos distintos dentro del mismo archivo.
 *
 * <p>Se usa {@link ResolverStyle#STRICT} con el patron {@code uuuu} (ano proleptico) para
 * que una fecha inexistente como {@code 2024-13-01} falle en vez de "corregirse" sola a
 * diciembre, que es justo el tipo de silencio que arrastraba el sistema COBOL.</p>
 *
 * <p>El orden de los patrones importa: primero los que empiezan por ano (ISO), despues los
 * que empiezan por dia. Con ello {@code 12/02/2024} se interpreta como 12 de febrero,
 * la convencion chilena {@code dd/MM/uuuu}.</p>
 */
public final class ParseadorFechas {

    private static final List<DateTimeFormatter> FORMATOS = List.of(
            formato("uuuu-MM-dd"),
            formato("uuuu/MM/dd"),
            formato("dd-MM-uuuu"),
            formato("dd/MM/uuuu"));

    private ParseadorFechas() {
        // Clase de utilidad: no se instancia.
    }

    private static DateTimeFormatter formato(String patron) {
        return DateTimeFormatter.ofPattern(patron).withResolverStyle(ResolverStyle.STRICT);
    }

    /**
     * Intenta interpretar el texto con cada uno de los formatos conocidos.
     *
     * @param valor texto crudo del CSV (puede venir con espacios o vacio)
     * @return la fecha normalizada, o {@link Optional#empty()} si ningun formato calza
     */
    public static Optional<LocalDate> parsear(String valor) {
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        String limpio = valor.trim();
        for (DateTimeFormatter formato : FORMATOS) {
            try {
                return Optional.of(LocalDate.parse(limpio, formato));
            } catch (DateTimeParseException ignorada) {
                // Se prueba con el siguiente formato del sistema legacy.
            }
        }
        return Optional.empty();
    }

    /** Indica si el texto original ya venia en el formato ISO {@code uuuu-MM-dd}. */
    public static boolean vieneEnFormatoIso(String valor) {
        return valor != null && valor.trim().matches("\\d{4}-\\d{2}-\\d{2}");
    }
}
