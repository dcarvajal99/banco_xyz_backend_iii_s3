package com.bancoxyz.batch.tasklet;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.RegistroRechazado;
import com.bancoxyz.repository.RegistroRechazadoRepository;
import com.bancoxyz.util.EscritorCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Ultimo Step de los tres Jobs: exporta la bitacora de rechazos de la corrida.
 *
 * <p>Cierra el ciclo del manejo de errores. Los registros omitidos ya quedaron en la tabla
 * {@code registro_rechazado}; este paso los entrega en un CSV que el area de datos usa
 * para corregir el origen y volver a cargar solo lo que fallo, sin reprocesar el archivo
 * completo.</p>
 *
 * <p>El mismo Tasklet sirve para los tres Jobs: el nombre del archivo se deriva del Job
 * que lo esta ejecutando.</p>
 */
@Component
public class ExportarRechazadosTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ExportarRechazadosTasklet.class);

    private final RegistroRechazadoRepository rechazados;

    public ExportarRechazadosTasklet(RegistroRechazadoRepository rechazados) {
        this.rechazados = rechazados;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) throws Exception {
        Long jobExecutionId = contexto.getStepContext().getStepExecution().getJobExecutionId();
        String jobNombre = contexto.getStepContext().getJobName();
        Path carpetaSalida = Path.of(String.valueOf(
                contexto.getStepContext().getJobParameters().get(Constantes.PARAM_SALIDA)));

        List<RegistroRechazado> registros = rechazados.findByJobExecutionIdOrderByIdAsc(jobExecutionId);
        Path destino = carpetaSalida.resolve("rechazados_" + jobNombre.replace("Job", "") + ".csv");

        List<String> filas = registros.stream()
                .map(registro -> EscritorCsv.fila(
                        registro.getId(),
                        registro.getArchivo(),
                        registro.getNumeroLinea(),
                        registro.getClasificacion(),
                        registro.getMotivo(),
                        registro.getContenido()))
                .toList();
        EscritorCsv.escribir(destino, "id,archivo,linea,clasificacion,motivo,contenido_original", filas);

        contribucion.incrementWriteCount(filas.size());
        log.info("Bitacora de rechazos exportada: {} registros en {}", filas.size(), destino);
        return RepeatStatus.FINISHED;
    }
}
