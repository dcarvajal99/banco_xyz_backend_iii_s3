package com.bancoxyz.batch.tasklet;

import com.bancoxyz.batch.decider.DecisorCalidadDeDatos;
import com.bancoxyz.common.Constantes;
import com.bancoxyz.config.PropiedadesBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Paso al que deriva el {@link DecisorCalidadDeDatos} cuando el archivo de origen llego
 * demasiado sucio.
 *
 * <p>No corrige nada ni reprocesa: su trabajo es dejar constancia accionable. Escribe un
 * aviso en la carpeta de salida con la tasa de omision, el detalle por Step y la instruccion
 * de que hacer, y repite lo mismo en el log con nivel de error para que el operador de turno
 * lo vea sin abrir archivos.</p>
 *
 * <p>Es un paso propio y no un simple {@code log.error} dentro del decisor porque asi queda
 * registrado en {@code BATCH_STEP_EXECUTION}: la auditoria del banco puede demostrar, meses
 * despues, que esa noche el proceso <em>si</em> detecto el problema y a que hora.</p>
 */
@Component
public class CuarentenaCalidadTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(CuarentenaCalidadTasklet.class);
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PropiedadesBatch propiedades;

    public CuarentenaCalidadTasklet(PropiedadesBatch propiedades) {
        this.propiedades = propiedades;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) throws IOException {
        StepExecution stepExecution = contexto.getStepContext().getStepExecution();
        JobExecution jobExecution = stepExecution.getJobExecution();
        ExecutionContext contextoJob = jobExecution.getExecutionContext();

        double tasa = contextoJob.containsKey(Constantes.CTX_TASA_OMISION)
                ? contextoJob.getDouble(Constantes.CTX_TASA_OMISION) : 0.0;
        String veredicto = contextoJob.containsKey(Constantes.CTX_VEREDICTO_CALIDAD)
                ? contextoJob.getString(Constantes.CTX_VEREDICTO_CALIDAD) : Constantes.CALIDAD_DEGRADADA;
        String motivo = contextoJob.containsKey(Constantes.CTX_MOTIVO_CALIDAD)
                ? contextoJob.getString(Constantes.CTX_MOTIVO_CALIDAD) : "Sin detalle";
        boolean inaceptable = Constantes.CALIDAD_INACEPTABLE.equals(veredicto);

        List<String> lineas = new ArrayList<>();
        lineas.add("AVISO DE CUARENTENA DE CALIDAD DE DATOS - BANCO XYZ");
        lineas.add("=".repeat(70));
        lineas.add("Job              : " + jobExecution.getJobInstance().getJobName());
        lineas.add("jobExecutionId   : " + jobExecution.getId());
        lineas.add("jobInstanceId    : " + jobExecution.getJobInstance().getInstanceId());
        lineas.add("Fecha            : " + LocalDateTime.now().format(SELLO));
        lineas.add("Veredicto        : " + veredicto);
        lineas.add("Tasa de omision  : " + DecisorCalidadDeDatos.porcentaje(tasa));
        lineas.add("Umbral de alerta : " + DecisorCalidadDeDatos.porcentaje(propiedades.getUmbralAlertaOmision()));
        lineas.add("Umbral de rechazo: " + DecisorCalidadDeDatos.porcentaje(propiedades.getUmbralRechazoOmision()));
        lineas.add("");
        lineas.add("MOTIVO");
        lineas.add("-".repeat(70));
        lineas.add(motivo);
        lineas.add("");
        lineas.add("DETALLE POR PASO");
        lineas.add("-".repeat(70));
        boolean hayDetalle = false;
        for (StepExecution paso : jobExecution.getStepExecutions()) {
            // Se listan los Steps de migracion aunque hayan leido cero filas: precisamente ese
            // caso —el archivo truncado— es el que hay que mostrar, y filtrarlo por lineas == 0
            // dejaria el aviso sin la informacion que motivo la cuarentena.
            if (paso.getStepName().contains(Constantes.SUFIJO_PARTICION)
                    || !paso.getExecutionContext().containsKey(Constantes.CTX_PASO_DE_MIGRACION)) {
                continue;
            }
            hayDetalle = true;
            long lineasDelPaso = paso.getReadCount() + paso.getReadSkipCount();
            lineas.add(String.format("%-45s leidas=%-6d escritas=%-6d omitidas=%-6d (%s)",
                    paso.getStepName(), lineasDelPaso, paso.getWriteCount(), paso.getSkipCount(),
                    lineasDelPaso == 0 ? "sin datos"
                            : DecisorCalidadDeDatos.porcentaje((double) paso.getSkipCount() / lineasDelPaso)));
        }
        if (!hayDetalle) {
            lineas.add("(ningun Step de migracion llego a ejecutarse)");
        }
        lineas.add("");
        lineas.add("ACCION REQUERIDA");
        lineas.add("-".repeat(70));
        if (inaceptable) {
            lineas.add("El reporte NO se publico. La corrida termina en fallo a proposito, para que");
            lineas.add("la JobInstance quede reanudable.");
            lineas.add("1. Revisar rechazados_<job>.csv y la tabla registro_rechazado.");
            lineas.add("2. Verificar que el archivo de origen llego completo y corregirlo en la");
            lineas.add("   carpeta de entrada.");
            lineas.add("3. Relanzar con la MISMA etiqueta de corrida para reanudar esta instancia:");
            lineas.add("      java -jar banco-xyz-batch-1.0.0.jar --job=<alias> --dataset=<carpeta> \\");
            lineas.add("           --corrida=" + jobExecution.getJobParameters()
                    .getString(Constantes.PARAM_EJECUCION, "<etiqueta>"));
        } else {
            lineas.add("El reporte se publico, pero con una tasa de omision sobre el umbral de alerta.");
            lineas.add("1. Revisar rechazados_<job>.csv antes de usar el reporte para decisiones.");
            lineas.add("2. Reportar al proveedor del archivo legacy la degradacion detectada.");
        }

        Path carpetaSalida = Path.of(jobExecution.getJobParameters()
                .getString(Constantes.PARAM_SALIDA, "salida"));
        Files.createDirectories(carpetaSalida);
        Path archivo = carpetaSalida.resolve(Constantes.SALIDA_CUARENTENA);
        Files.write(archivo, String.join(System.lineSeparator(), lineas)
                .getBytes(StandardCharsets.UTF_8));

        // Se repite el motivo que dejo el decisor en vez de reconstruirlo: hay dos razones
        // distintas para llegar aqui (tasa de omision alta y cobertura insuficiente) y hablar
        // siempre de "tasa de omision" describiria mal la mitad de los casos.
        if (inaceptable) {
            log.error("CUARENTENA | {} | {} El reporte no se publica. Aviso en {}",
                    jobExecution.getJobInstance().getJobName(), motivo, archivo.toAbsolutePath());
        } else {
            log.warn("CUARENTENA | {} | {} El reporte se publica con advertencia. Aviso en {}",
                    jobExecution.getJobInstance().getJobName(), motivo, archivo.toAbsolutePath());
        }

        contribucion.incrementWriteCount(1);
        return RepeatStatus.FINISHED;
    }
}
