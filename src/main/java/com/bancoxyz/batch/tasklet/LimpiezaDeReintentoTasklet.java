package com.bancoxyz.batch.tasklet;

import com.bancoxyz.repository.CuentaInteresRepository;
import com.bancoxyz.repository.EstadoCuentaAnualRepository;
import com.bancoxyz.repository.MovimientoAnualRepository;
import com.bancoxyz.repository.RegistroRechazadoRepository;
import com.bancoxyz.repository.ResumenDiarioRepository;
import com.bancoxyz.repository.TransaccionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Primer Step de todos los Jobs: deja la base limpia de lo que escribio un intento anterior
 * de <em>esta misma</em> corrida.
 *
 * <p><b>El problema que resuelve.</b> Al pasar los Steps a tres hilos hubo que renunciar a
 * guardar la posicion del lector en el {@code ExecutionContext} (ver {@code LectoresCsv}), de
 * modo que un Step que falla no se reanuda a mitad de archivo: se rehace completo. Eso, por
 * si solo, dejaria la base peor que antes. Si el primer intento alcanzo a escribir 400 filas
 * antes de caerse, el segundo intento escribe las 1000 y en la tabla quedan 1400: las 400
 * huerfanas del intento fallido y las 1000 buenas. Peor todavia, los Steps de agregacion
 * consultan por {@code jobExecutionId}, asi que el reporte saldria bien y nadie notaria las
 * 400 filas duplicadas escondidas debajo.</p>
 *
 * <p><b>Como lo resuelve.</b> Pregunta al {@link JobExplorer} que otras ejecuciones tuvo esta
 * misma {@code JobInstance} y borra las filas que quedaron a nombre de ellas. En la primera
 * corrida no hay nada que borrar y el paso es un no-op de milisegundos; solo hace trabajo
 * real cuando de verdad se esta reanudando algo.</p>
 *
 * <p>Con esto la re-ejecucion pasa a ser <b>idempotente</b>: relanzar la misma corrida las
 * veces que haga falta siempre deja la base en el mismo estado, que es la condicion que un
 * banco exige antes de autorizar que un proceso nocturno se pueda reintentar solo.</p>
 *
 * <p><b>Este paso no funciona solo.</b> Va necesariamente de la mano de
 * {@code allowStartIfComplete(true)} en los Steps de migracion. Si se borra aqui lo del
 * intento anterior pero el Step que lo escribio no se vuelve a ejecutar —porque ya figuraba
 * como COMPLETED—, la reanudacion termina en COMPLETED con la tabla vacia. Las dos piezas
 * son una sola decision de diseno; cambiar una sin la otra rompe la migracion en silencio.</p>
 */
@Component
public class LimpiezaDeReintentoTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaDeReintentoTasklet.class);

    private final JobExplorer jobExplorer;
    private final TransaccionRepository transacciones;
    private final ResumenDiarioRepository resumenes;
    private final CuentaInteresRepository cuentas;
    private final MovimientoAnualRepository movimientos;
    private final EstadoCuentaAnualRepository estados;
    private final RegistroRechazadoRepository rechazados;

    public LimpiezaDeReintentoTasklet(JobExplorer jobExplorer,
                                      TransaccionRepository transacciones,
                                      ResumenDiarioRepository resumenes,
                                      CuentaInteresRepository cuentas,
                                      MovimientoAnualRepository movimientos,
                                      EstadoCuentaAnualRepository estados,
                                      RegistroRechazadoRepository rechazados) {
        this.jobExplorer = jobExplorer;
        this.transacciones = transacciones;
        this.resumenes = resumenes;
        this.cuentas = cuentas;
        this.movimientos = movimientos;
        this.estados = estados;
        this.rechazados = rechazados;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) {
        JobExecution actual = contexto.getStepContext().getStepExecution().getJobExecution();

        List<Long> ejecucionesPrevias = jobExplorer
                .getJobExecutions(actual.getJobInstance()).stream()
                .map(JobExecution::getId)
                .filter(id -> !id.equals(actual.getId()))
                .toList();

        if (ejecucionesPrevias.isEmpty()) {
            log.info("Limpieza previa: primera ejecucion de la instancia {}, no hay nada que borrar",
                    actual.getJobInstance().getInstanceId());
            return RepeatStatus.FINISHED;
        }

        long borradas = transacciones.deleteByJobExecutionIdIn(ejecucionesPrevias)
                + resumenes.deleteByJobExecutionIdIn(ejecucionesPrevias)
                + cuentas.deleteByJobExecutionIdIn(ejecucionesPrevias)
                + movimientos.deleteByJobExecutionIdIn(ejecucionesPrevias)
                + estados.deleteByJobExecutionIdIn(ejecucionesPrevias)
                + rechazados.deleteByJobExecutionIdIn(ejecucionesPrevias);

        log.warn("Limpieza previa: se reanuda la instancia {} (ejecuciones anteriores {}). "
                        + "Se borraron {} filas del intento anterior para que la migracion sea idempotente.",
                actual.getJobInstance().getInstanceId(), ejecucionesPrevias, borradas);

        contribucion.incrementWriteCount(borradas);
        return RepeatStatus.FINISHED;
    }
}
