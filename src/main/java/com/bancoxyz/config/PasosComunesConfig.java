package com.bancoxyz.config;

import com.bancoxyz.batch.tasklet.CuarentenaCalidadTasklet;
import com.bancoxyz.batch.tasklet.ExportarRechazadosTasklet;
import com.bancoxyz.batch.tasklet.LimpiezaDeReintentoTasklet;
import com.bancoxyz.common.Constantes;
import org.springframework.batch.core.Step;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Steps que comparten los cuatro Jobs de la migracion.
 *
 * <p>Estan aqui y no repetidos en cada {@code ...JobConfig} porque son exactamente el mismo
 * paso: la limpieza previa a un reintento, la exportacion de la bitacora de rechazos y las
 * dos ramas del decisor de calidad. Un Step declarado una sola vez tambien aparece una sola
 * vez en los metadatos de Spring Batch, lo que hace legible el historial de ejecuciones.</p>
 *
 * <p>Las dos ramas de cuarentena usan el <b>mismo</b> {@link CuarentenaCalidadTasklet} pero se
 * registran como Steps distintos. No es una duplicacion: en un flujo de Spring Batch cada
 * nodo tiene una unica transicion de salida, y estas dos ramas van a sitios opuestos (una
 * deja publicar el reporte y la otra corta la corrida). Ademas tiene una ventaja practica:
 * mirando el nombre del Step en {@code BATCH_STEP_EXECUTION} se sabe que decidio el decisor
 * esa noche, sin abrir un solo log.</p>
 */
@Configuration
public class PasosComunesConfig {

    private final ConstructorDePasos constructor;

    public PasosComunesConfig(ConstructorDePasos constructor) {
        this.constructor = constructor;
    }

    /** Primer Step de todos los Jobs: deja la base sin restos del intento anterior. */
    @Bean
    public Step limpiezaDeReintentoStep(LimpiezaDeReintentoTasklet limpiezaDeReintentoTasklet) {
        return constructor.pasoDeTarea(Constantes.STEP_LIMPIEZA, limpiezaDeReintentoTasklet);
    }

    /**
     * Vuelca la bitacora de rechazos de la corrida. Va <b>antes</b> del decisor: la evidencia
     * de que se descarto hay que producirla siempre, y con mas razon cuando la calidad fue
     * mala, porque es el archivo que el area de datos necesita para corregir el origen.
     */
    @Bean
    public Step exportarRechazadosStep(ExportarRechazadosTasklet exportarRechazadosTasklet) {
        return constructor.pasoDeTarea(Constantes.STEP_EXPORTAR_RECHAZADOS, exportarRechazadosTasklet);
    }

    /** Rama del decisor para calidad degradada: avisa y la corrida sigue hasta publicar. */
    @Bean
    public Step cuarentenaAvisoStep(CuarentenaCalidadTasklet cuarentenaCalidadTasklet) {
        return constructor.pasoDeTarea(Constantes.STEP_CUARENTENA_AVISO, cuarentenaCalidadTasklet);
    }

    /** Rama del decisor para calidad inaceptable: avisa y la corrida termina en fallo. */
    @Bean
    public Step cuarentenaCorteStep(CuarentenaCalidadTasklet cuarentenaCalidadTasklet) {
        return constructor.pasoDeTarea(Constantes.STEP_CUARENTENA_CORTE, cuarentenaCalidadTasklet);
    }
}
