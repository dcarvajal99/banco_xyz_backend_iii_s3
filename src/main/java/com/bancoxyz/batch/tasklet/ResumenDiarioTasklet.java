package com.bancoxyz.batch.tasklet;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.ResumenDiario;
import com.bancoxyz.entity.Transaccion;
import com.bancoxyz.repository.ResumenDiarioRepository;
import com.bancoxyz.repository.TransaccionRepository;
import com.bancoxyz.util.EscritorCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Segundo Step del Job 1: compila el reporte diario de transacciones.
 *
 * <p>Es un {@code Tasklet} y no un Step orientado a chunk porque la operacion es una
 * agregacion sobre lo que ya quedo persistido, no un flujo item a item. Aun asi lee de
 * forma <b>paginada</b>: cargar el dia completo en una sola lista funcionaria con mil
 * filas, pero no con el volumen real del Banco XYZ.</p>
 *
 * <p>Criterio de negocio: las transacciones con monto no positivo se cuentan como
 * anomalia pero <em>no</em> suman a los totales del dia, porque no representan un
 * movimiento real de dinero.</p>
 */
@Component
public class ResumenDiarioTasklet implements Tasklet {

    private static final int TAMANO_PAGINA = 500;
    private static final Logger log = LoggerFactory.getLogger(ResumenDiarioTasklet.class);

    private final TransaccionRepository transacciones;
    private final ResumenDiarioRepository resumenes;

    public ResumenDiarioTasklet(TransaccionRepository transacciones, ResumenDiarioRepository resumenes) {
        this.transacciones = transacciones;
        this.resumenes = resumenes;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) throws Exception {
        Long jobExecutionId = contexto.getStepContext().getStepExecution().getJobExecutionId();
        Path carpetaSalida = Path.of(String.valueOf(
                contexto.getStepContext().getJobParameters().get(Constantes.PARAM_SALIDA)));

        Map<LocalDate, ResumenDiario> porDia = new TreeMap<>();
        int pagina = 0;
        Page<Transaccion> lote;
        do {
            lote = transacciones.findByJobExecutionId(jobExecutionId,
                    PageRequest.of(pagina++, TAMANO_PAGINA, Sort.by("id")));
            lote.forEach(transaccion -> acumular(porDia, transaccion, jobExecutionId));
        } while (lote.hasNext());

        List<ResumenDiario> resultado = new ArrayList<>(porDia.values());
        resumenes.saveAll(resultado);
        exportar(carpetaSalida.resolve(Constantes.SALIDA_TRANSACCIONES), resultado);

        contribucion.incrementWriteCount(resultado.size());
        log.info("Reporte diario compilado: {} dias, {} transacciones, {} anomalias",
                resultado.size(),
                resultado.stream().mapToLong(ResumenDiario::getCantidadTransacciones).sum(),
                resultado.stream().mapToLong(ResumenDiario::getCantidadAnomalias).sum());
        return RepeatStatus.FINISHED;
    }

    private void acumular(Map<LocalDate, ResumenDiario> porDia, Transaccion transaccion, Long jobExecutionId) {
        ResumenDiario dia = porDia.computeIfAbsent(transaccion.getFecha(),
                fecha -> new ResumenDiario(fecha, jobExecutionId));

        dia.setCantidadTransacciones(dia.getCantidadTransacciones() + 1);
        if (transaccion.isAnomalia()) {
            dia.setCantidadAnomalias(dia.getCantidadAnomalias() + 1);
        }

        BigDecimal monto = transaccion.getMonto();
        if (monto.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if ("debito".equals(transaccion.getTipo())) {
            dia.setTotalDebitos(dia.getTotalDebitos().add(monto));
        } else {
            dia.setTotalCreditos(dia.getTotalCreditos().add(monto));
        }
        if (monto.compareTo(dia.getMontoMaximo()) > 0) {
            dia.setMontoMaximo(monto);
        }
    }

    private void exportar(Path destino, List<ResumenDiario> resumen) throws Exception {
        List<String> filas = resumen.stream()
                .map(dia -> EscritorCsv.fila(
                        dia.getFecha(),
                        dia.getCantidadTransacciones(),
                        EscritorCsv.importe(dia.getTotalDebitos()),
                        EscritorCsv.importe(dia.getTotalCreditos()),
                        EscritorCsv.importe(dia.getSaldoNeto()),
                        EscritorCsv.importe(dia.getMontoMaximo()),
                        dia.getCantidadAnomalias()))
                .toList();
        EscritorCsv.escribir(destino,
                "fecha,cantidad_transacciones,total_debitos,total_creditos,saldo_neto,monto_maximo,anomalias",
                filas);
        log.info("Archivo de salida generado: {}", destino);
    }
}
