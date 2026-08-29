package com.bancoxyz.batch.decider;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Control de finalizacion del Job: decide, a la vista de cuanto se leyo y cuanto se omitio, si
 * la corrida puede seguir hasta publicar el reporte o si debe derivarse a revision manual.
 *
 * <p><b>El problema que resuelve.</b> La politica de omision hace que una fila sucia no detenga
 * la migracion, y eso esta bien fila a fila. Pero llevado al extremo produce el peor resultado
 * posible para un banco: un Job que termina en {@code COMPLETED}, un reporte diario publicado
 * con normalidad, y detras un archivo del que se descarto la mitad. El area de negocio toma
 * decisiones sobre un reporte que parece completo y no lo esta. El tope de
 * {@code limiteOmisiones} no alcanza para evitarlo, porque es un numero absoluto: mil omisiones
 * son irrelevantes en un archivo de un millon de filas y catastroficas en uno de mil.</p>
 *
 * <h2>Dos preguntas distintas, no una</h2>
 * <p>El decisor comprueba dos cosas, y en este orden:</p>
 * <ol>
 *   <li><b>Cobertura</b>: ¿llego el archivo? Un archivo <em>truncado</em>, del que solo llego la
 *       cabecera, no tiene ninguna fila omitida y por lo tanto su tasa de omision es 0 %, la mejor
 *       posible. Sin esta comprobacion la migracion perderia el 100 % de los datos del dia, lo
 *       informaria como calidad aceptable y publicaria un reporte vacio encima del del dia
 *       anterior, indistinguible de una jornada sin movimientos. El lector solo falla cuando el
 *       archivo <em>no existe</em>; uno presente pero vacio abre sin protestar.</li>
 *   <li><b>Proporcion de omisiones</b>: de lo que llego, ¿cuanto se pudo aprovechar?</li>
 * </ol>
 *
 * <table border="1">
 *   <caption>Salidas del decisor</caption>
 *   <tr><th>Situacion</th><th>Salida</th><th>Que pasa con la corrida</th></tr>
 *   <tr><td>algun archivo trajo menos filas que el minimo esperado</td>
 *       <td>{@link Constantes#CALIDAD_INACEPTABLE}</td>
 *       <td>no publica, deja el aviso y termina en fallo</td></tr>
 *   <tr><td>omisiones bajo el umbral de alerta</td>
 *       <td>{@link Constantes#CALIDAD_ACEPTABLE}</td>
 *       <td>sigue normal y publica el reporte</td></tr>
 *   <tr><td>omisiones entre alerta y rechazo</td>
 *       <td>{@link Constantes#CALIDAD_DEGRADADA}</td>
 *       <td>publica el reporte, pero deja aviso de cuarentena para el area de datos</td></tr>
 *   <tr><td>omisiones sobre el umbral de rechazo</td>
 *       <td>{@link Constantes#CALIDAD_INACEPTABLE}</td>
 *       <td>no publica el reporte, deja el aviso y termina en fallo</td></tr>
 * </table>
 *
 * <p>Terminar en fallo es deliberado y no es un efecto colateral: deja la {@code JobInstance}
 * en estado reanudable, de modo que cuando el area de datos corrija el archivo de origen la
 * misma corrida se puede relanzar con la misma {@code --corrida} y Spring Batch retoma desde el
 * paso que quedo incompleto.</p>
 *
 * <p>Los tres parametros son propiedades, no constantes: un cierre de contingencia puede
 * necesitar migrar igual un archivo degradado, y eso se resuelve con
 * {@code --banco.batch.umbral-rechazo-omision=0.80} y no con un cambio de codigo.</p>
 */
@Component(Constantes.DECISOR_CALIDAD)
public class DecisorCalidadDeDatos implements JobExecutionDecider {

    private static final Logger log = LoggerFactory.getLogger(DecisorCalidadDeDatos.class);

    private final PropiedadesBatch propiedades;

    public DecisorCalidadDeDatos(PropiedadesBatch propiedades) {
        this.propiedades = propiedades;
    }

    @Override
    public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
        long leidas = 0;
        long omitidas = 0;
        List<String> pasosSinDatos = new ArrayList<>();

        for (StepExecution paso : jobExecution.getStepExecutions()) {
            // Las particiones se descartan: sus contadores YA estan sumados en los del Step
            // gestor, asi que contarlas ademas duplicaria las filas leidas y dividiria por dos
            // la tasa de omision. Y la comprobacion de cobertura, pensada para un archivo
            // completo, pasaria a exigirle el minimo a cada fragmento por separado.
            if (paso.getStepName().contains(Constantes.SUFIJO_PARTICION)) {
                continue;
            }
            // De los que quedan, solo cuentan los que leyeron del archivo legacy. Se reconocen
            // por la marca que dejan el medidor de rendimiento (corridas sueltas) o el resumen
            // de particiones (Steps gestores): preguntarlo asi evita mantener una lista de
            // nombres que se desincronice del codigo.
            if (!paso.getExecutionContext().containsKey(Constantes.CTX_PASO_DE_MIGRACION)) {
                continue;
            }
            long lineasDelPaso = paso.getReadCount() + paso.getReadSkipCount();
            if (lineasDelPaso < propiedades.getMinimoFilasEsperadas()) {
                pasosSinDatos.add(paso.getStepName() + " (" + lineasDelPaso + " filas)");
            }
            leidas += lineasDelPaso;
            omitidas += paso.getSkipCount();
        }

        if (!pasosSinDatos.isEmpty()) {
            return registrar(jobExecution, Constantes.CALIDAD_INACEPTABLE, 0.0,
                    "Cobertura insuficiente: se esperaban al menos "
                            + propiedades.getMinimoFilasEsperadas() + " filas por archivo y no llegaron en "
                            + String.join(", ", pasosSinDatos)
                            + ". El archivo de origen puede venir truncado.");
        }

        double tasa = leidas == 0 ? 0.0 : (double) omitidas / leidas;
        String veredicto = clasificar(tasa);
        return registrar(jobExecution, veredicto, tasa,
                "Tasa de omision " + porcentaje(tasa) + " sobre " + leidas + " filas leidas ("
                        + omitidas + " omitidas). Umbrales: alerta "
                        + porcentaje(propiedades.getUmbralAlertaOmision()) + ", rechazo "
                        + porcentaje(propiedades.getUmbralRechazoOmision()) + ".");
    }

    /**
     * Deja el veredicto en el contexto del Job —para que el paso de cuarentena lo informe sin
     * recalcularlo y para que quede persistido en {@code BATCH_JOB_EXECUTION_CONTEXT}— y lo
     * escribe en el log con el nivel que corresponde a su gravedad.
     */
    private FlowExecutionStatus registrar(JobExecution jobExecution, String veredicto,
                                          double tasa, String motivo) {
        jobExecution.getExecutionContext().putDouble(Constantes.CTX_TASA_OMISION, tasa);
        jobExecution.getExecutionContext().putString(Constantes.CTX_VEREDICTO_CALIDAD, veredicto);
        jobExecution.getExecutionContext().putString(Constantes.CTX_MOTIVO_CALIDAD, motivo);

        if (Constantes.CALIDAD_INACEPTABLE.equals(veredicto)) {
            log.error("Decisor de calidad -> {} | {}", veredicto, motivo);
        } else if (Constantes.CALIDAD_DEGRADADA.equals(veredicto)) {
            log.warn("Decisor de calidad -> {} | {}", veredicto, motivo);
        } else {
            log.info("Decisor de calidad -> {} | {}", veredicto, motivo);
        }
        return new FlowExecutionStatus(veredicto);
    }

    private String clasificar(double tasa) {
        if (tasa >= propiedades.getUmbralRechazoOmision()) {
            return Constantes.CALIDAD_INACEPTABLE;
        }
        if (tasa >= propiedades.getUmbralAlertaOmision()) {
            return Constantes.CALIDAD_DEGRADADA;
        }
        return Constantes.CALIDAD_ACEPTABLE;
    }

    /** Formatea una proporcion como porcentaje con un decimal, para el log y el aviso. */
    public static String porcentaje(double proporcion) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", proporcion * 100);
    }
}
