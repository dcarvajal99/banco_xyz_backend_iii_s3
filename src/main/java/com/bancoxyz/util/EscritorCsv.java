package com.bancoxyz.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;

/**
 * Escritura de los archivos de salida de la migracion.
 *
 * <p>Los reportes se entregan en CSV porque son los que consume el area de auditoria del
 * banco, que hoy sigue trabajando con planillas. El separador y el escape de comillas se
 * resuelven aqui una sola vez para que los tres jobs generen archivos consistentes.</p>
 */
public final class EscritorCsv {

    private EscritorCsv() {
        // Clase de utilidad: no se instancia.
    }

    /**
     * Escribe el archivo creando la carpeta destino si no existe.
     *
     * @param destino ruta del archivo a generar
     * @param cabecera linea de encabezado ya formada
     * @param filas lineas de datos ya formadas
     */
    public static void escribir(Path destino, String cabecera, List<String> filas) throws IOException {
        Path carpeta = destino.getParent();
        if (carpeta != null) {
            Files.createDirectories(carpeta);
        }
        try (BufferedWriter escritor = Files.newBufferedWriter(destino, StandardCharsets.UTF_8)) {
            escritor.write(cabecera);
            escritor.newLine();
            for (String fila : filas) {
                escritor.write(fila);
                escritor.newLine();
            }
        }
    }

    /** Une los campos con coma, entrecomillando los que contengan comas, comillas o saltos. */
    public static String fila(Object... campos) {
        StringBuilder linea = new StringBuilder();
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) {
                linea.append(',');
            }
            linea.append(escapar(campos[i]));
        }
        return linea.toString();
    }

    /** Formatea un importe siempre con dos decimales, aunque venga como cero sin escala. */
    public static String importe(BigDecimal valor) {
        BigDecimal seguro = valor == null ? BigDecimal.ZERO : valor;
        return seguro.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String escapar(Object campo) {
        String valor = campo == null ? "" : String.valueOf(campo);
        if (valor.indexOf(',') < 0 && valor.indexOf('"') < 0 && valor.indexOf('\n') < 0) {
            return valor;
        }
        return '"' + valor.replace("\"", "\"\"") + '"';
    }
}
