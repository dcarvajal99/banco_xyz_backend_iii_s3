package com.bancoxyz.batch.decider;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las tres salidas del control de finalizacion, con los umbrales del Banco XYZ.
 *
 * <p>Se prueba aparte de los Jobs porque es una regla de negocio: los umbrales los fija el
 * area de datos y cambiarlos no deberia obligar a levantar un contexto de Spring.</p>
 */
class DecisorCalidadDeDatosTest {

    private static final long SIN_PASO_PREVIO_ID = 1L;

    private DecisorCalidadDeDatos decisor(double alerta, double rechazo) {
        PropiedadesBatch propiedades = new PropiedadesBatch();
        propiedades.setUmbralAlertaOmision(alerta);
        propiedades.setUmbralRechazoOmision(rechazo);
        return new DecisorCalidadDeDatos(propiedades);
    }

    /** Arma una JobExecution con un Step de chunk que leyo y omito las cantidades indicadas. */
    private JobExecution ejecucionCon(long leidas, long omitidas) {
        JobExecution ejecucion = new JobExecution(SIN_PASO_PREVIO_ID);
        ejecucion.setJobInstance(new JobInstance(SIN_PASO_PREVIO_ID, "jobDePrueba"));
        pasoDeMigracion(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES, leidas, omitidas);
        return ejecucion;
    }

    /**
     * Agrega un Step de migracion. Lleva la marca que dejan el medidor de rendimiento (corridas
     * sueltas) o el resumen de particiones (Steps gestores), que es como el decisor distingue un
     * Step que lee del archivo de un Tasklet de agregacion.
     */
    private StepExecution pasoDeMigracion(JobExecution ejecucion, String nombre,
                                          long leidas, long omitidas) {
        StepExecution paso = ejecucion.createStepExecution(nombre);
        paso.setReadCount(leidas - omitidas);
        // Las omisiones se reparten entre lectura y proceso, como en una corrida real.
        paso.setReadSkipCount(omitidas);
        paso.setProcessSkipCount(0);
        paso.getExecutionContext().putString(Constantes.CTX_PASO_DE_MIGRACION, nombre);
        return paso;
    }

    /**
     * Agrega un Step gestor con sus particiones, tal como queda una corrida particionada: el
     * gestor con los contadores YA agregados y una StepExecution por particion con su parte.
     */
    private void pasoParticionado(JobExecution ejecucion, String nombreGestor,
                                  int particiones, long leidas, long omitidas) {
        pasoDeMigracion(ejecucion, nombreGestor, leidas, omitidas);
        for (int i = 0; i < particiones; i++) {
            StepExecution particion = ejecucion.createStepExecution(
                    nombreGestor + Constantes.SUFIJO_WORKER + Constantes.SUFIJO_PARTICION + i);
            particion.setReadCount((leidas - omitidas) / particiones);
            particion.setReadSkipCount(omitidas / particiones);
            particion.getExecutionContext()
                    .putString(Constantes.CTX_PASO_DE_MIGRACION, nombreGestor);
        }
    }

    @ParameterizedTest(name = "{0} leidas con {1} omitidas -> {2}")
    @DisplayName("La tasa de omision decide entre seguir, avisar o cortar la corrida")
    @CsvSource({
            "100,  0, CALIDAD_ACEPTABLE",
            "100,  9, CALIDAD_ACEPTABLE",
            "100, 10, CALIDAD_DEGRADADA",
            "100, 20, CALIDAD_DEGRADADA",
            "100, 29, CALIDAD_DEGRADADA",
            "100, 30, CALIDAD_INACEPTABLE",
            "100, 50, CALIDAD_INACEPTABLE",
            "100,100, CALIDAD_INACEPTABLE",
    })
    void clasificaSegunLaTasaDeOmision(long leidas, long omitidas, String esperado) {
        JobExecution ejecucion = ejecucionCon(leidas, omitidas);

        String veredicto = decisor(0.10, 0.30).decide(ejecucion, null).getName();

        assertThat(veredicto).isEqualTo(esperado);
    }

    @Test
    @DisplayName("El decisor deja la tasa y el veredicto en el contexto del Job para el aviso de cuarentena")
    void publicaLaTasaEnElContextoDelJob() {
        JobExecution ejecucion = ejecucionCon(200, 50);

        decisor(0.10, 0.30).decide(ejecucion, null);

        assertThat(ejecucion.getExecutionContext().getDouble(Constantes.CTX_TASA_OMISION))
                .isEqualTo(0.25);
        assertThat(ejecucion.getExecutionContext().getString(Constantes.CTX_VEREDICTO_CALIDAD))
                .isEqualTo(Constantes.CALIDAD_DEGRADADA);
    }

    @Test
    @DisplayName("Los Steps que no leen del archivo no diluyen la tasa de omision")
    void ignoraLosPasosDeAgregacion() {
        JobExecution ejecucion = ejecucionCon(100, 40);
        // Un Tasklet de agregacion: escribe mucho pero no lee ninguna fila del CSV, y no lleva
        // la marca del medidor de rendimiento porque ese listener solo se monta en los Steps de
        // chunk. Es lo que permite al decisor no confundirlo con un archivo truncado.
        StepExecution agregacion = ejecucion.createStepExecution(Constantes.STEP_RESUMEN_DIARIO);
        agregacion.setReadCount(0);
        agregacion.setWriteCount(900);

        String veredicto = decisor(0.10, 0.30).decide(ejecucion, null).getName();

        // 40/100 = 40 %. Si el Tasklet contara, la tasa caeria a 40/1000 = 4 % y el archivo
        // inservible se daria por bueno.
        assertThat(veredicto).isEqualTo(Constantes.CALIDAD_INACEPTABLE);
    }

    @Test
    @DisplayName("Un archivo truncado se rechaza aunque su tasa de omision sea del 0 %")
    void detectaElArchivoTruncado() {
        // Un archivo del que solo llego la cabecera: cero filas leidas y, por lo tanto, cero
        // omitidas. Su tasa es 0 %, la mejor posible. Si el decisor mirara solo la proporcion,
        // daria la corrida por buena y publicaria un reporte vacio encima del del dia anterior,
        // indistinguible de una jornada sin movimientos: perdida total de los datos informada
        // como exito. El lector no lo detecta, porque strict(true) solo comprueba que el archivo
        // exista, no que traiga filas.
        JobExecution ejecucion = ejecucionCon(0, 0);

        String veredicto = decisor(0.10, 0.30).decide(ejecucion, null).getName();

        assertThat(veredicto).isEqualTo(Constantes.CALIDAD_INACEPTABLE);
        assertThat(ejecucion.getExecutionContext().getString(Constantes.CTX_MOTIVO_CALIDAD))
                .contains("Cobertura insuficiente")
                .contains("truncado");
    }

    @Test
    @DisplayName("Basta con que UNO de los archivos venga truncado para cortar la corrida")
    void detectaUnSoloArchivoTruncadoEntreVarios() {
        // El cierre nocturno completo procesa tres archivos. Si se mirara solo el agregado, dos
        // archivos sanos de 1.000 filas taparian por completo a un tercero que llego vacio.
        JobExecution ejecucion = ejecucionCon(1000, 0);
        pasoDeMigracion(ejecucion, Constantes.STEP_PROCESAR_INTERESES, 1000, 0);
        pasoDeMigracion(ejecucion, Constantes.STEP_PROCESAR_MOVIMIENTOS, 0, 0);

        String veredicto = decisor(0.10, 0.30).decide(ejecucion, null).getName();

        assertThat(veredicto).isEqualTo(Constantes.CALIDAD_INACEPTABLE);
        assertThat(ejecucion.getExecutionContext().getString(Constantes.CTX_MOTIVO_CALIDAD))
                .contains(Constantes.STEP_PROCESAR_MOVIMIENTOS);
    }

    @Test
    @DisplayName("La comprobacion de cobertura se puede desactivar para un archivo que puede venir vacio")
    void permiteDesactivarLaCoberturaMinima() {
        JobExecution ejecucion = ejecucionCon(0, 0);
        PropiedadesBatch propiedades = new PropiedadesBatch();
        propiedades.setUmbralAlertaOmision(0.10);
        propiedades.setUmbralRechazoOmision(0.30);
        propiedades.setMinimoFilasEsperadas(0);

        String veredicto = new DecisorCalidadDeDatos(propiedades).decide(ejecucion, null).getName();

        assertThat(veredicto).isEqualTo(Constantes.CALIDAD_ACEPTABLE);
    }

    @Test
    @DisplayName("Con particiones cuenta el gestor y no vuelve a contar cada particion")
    void noCuentaDosVecesLasFilasDeUnPasoParticionado() {
        // El gestor trae 100 leidas y 20 omitidas; sus 4 particiones traen 25 y 5 cada una,
        // porque los contadores del gestor son la SUMA de los de sus particiones. Si el decisor
        // sumara ambos niveles veria 200 filas y una tasa del 20 % sobre el doble de datos: el
        // veredicto saldria igual por casualidad, pero el mensaje mentiria y la comprobacion de
        // cobertura minima se aplicaria a fragmentos en vez de al archivo completo.
        JobExecution ejecucion = new JobExecution(SIN_PASO_PREVIO_ID);
        ejecucion.setJobInstance(new JobInstance(SIN_PASO_PREVIO_ID, "jobDePrueba"));
        pasoParticionado(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES, 4, 100, 20);

        String veredicto = decisor(0.10, 0.30).decide(ejecucion, null).getName();

        assertThat(veredicto).isEqualTo(Constantes.CALIDAD_DEGRADADA);
        assertThat(ejecucion.getExecutionContext().getDouble(Constantes.CTX_TASA_OMISION))
                .isEqualTo(0.20);
        assertThat(ejecucion.getExecutionContext().getString(Constantes.CTX_MOTIVO_CALIDAD))
                .contains("100 filas leidas");
    }

    @Test
    @DisplayName("La cobertura minima se exige al archivo completo, no a cada particion")
    void laCoberturaSeExigeAlArchivoNoALaParticion() {
        // Cuatro particiones de 25 filas cada una: ninguna llega sola a 50, pero el archivo
        // trae 100. Si el minimo se aplicara por particion, un archivo sano se rechazaria solo
        // por haberlo repartido en mas trozos.
        JobExecution ejecucion = new JobExecution(SIN_PASO_PREVIO_ID);
        ejecucion.setJobInstance(new JobInstance(SIN_PASO_PREVIO_ID, "jobDePrueba"));
        pasoParticionado(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES, 4, 100, 0);

        PropiedadesBatch propiedades = new PropiedadesBatch();
        propiedades.setUmbralAlertaOmision(0.10);
        propiedades.setUmbralRechazoOmision(0.30);
        propiedades.setMinimoFilasEsperadas(50);

        String veredicto = new DecisorCalidadDeDatos(propiedades).decide(ejecucion, null).getName();

        assertThat(veredicto).isEqualTo(Constantes.CALIDAD_ACEPTABLE);
    }

    @Test
    @DisplayName("Los umbrales son configurables: un cierre de contingencia puede migrar igual")
    void respetaUmbralesRelajados() {
        JobExecution ejecucion = ejecucionCon(100, 50);

        // Con la politica estricta el archivo se rechaza...
        assertThat(decisor(0.10, 0.30).decide(ejecucion, null).getName())
                .isEqualTo(Constantes.CALIDAD_INACEPTABLE);
        // ...y con la de contingencia se migra igual, dejando el aviso.
        assertThat(decisor(0.10, 0.80).decide(ejecucion, null).getName())
                .isEqualTo(Constantes.CALIDAD_DEGRADADA);
    }
}
