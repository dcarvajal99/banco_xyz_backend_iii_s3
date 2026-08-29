package com.bancoxyz.batch.tasklet;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.EstadoCuentaAnual;
import com.bancoxyz.entity.MovimientoAnual;
import com.bancoxyz.repository.EstadoCuentaAnualRepository;
import com.bancoxyz.repository.MovimientoAnualRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Segundo Step del Job 3: compila el estado de cuenta anual para auditoria.
 *
 * <p>Agrupa los movimientos ya normalizados por cuenta y ano. Como el Step anterior dejo
 * el signo del monto alineado al tipo de movimiento, el saldo neto es simplemente la suma
 * de la columna, sin condicionales.</p>
 */
@Component
public class EstadosCuentaAnualesTasklet implements Tasklet {

    private static final int TAMANO_PAGINA = 500;
    private static final Logger log = LoggerFactory.getLogger(EstadosCuentaAnualesTasklet.class);

    private final MovimientoAnualRepository movimientos;
    private final EstadoCuentaAnualRepository estados;

    public EstadosCuentaAnualesTasklet(MovimientoAnualRepository movimientos,
                                       EstadoCuentaAnualRepository estados) {
        this.movimientos = movimientos;
        this.estados = estados;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) throws Exception {
        Long jobExecutionId = contexto.getStepContext().getStepExecution().getJobExecutionId();
        Path carpetaSalida = Path.of(String.valueOf(
                contexto.getStepContext().getJobParameters().get(Constantes.PARAM_SALIDA)));

        Map<String, EstadoCuentaAnual> acumulado = new HashMap<>();
        int pagina = 0;
        Page<MovimientoAnual> lote;
        do {
            lote = movimientos.findByJobExecutionId(jobExecutionId,
                    PageRequest.of(pagina++, TAMANO_PAGINA, Sort.by("id")));
            lote.forEach(movimiento -> acumular(acumulado, movimiento, jobExecutionId));
        } while (lote.hasNext());

        List<EstadoCuentaAnual> resultado = new ArrayList<>(acumulado.values());
        resultado.sort(Comparator.comparing(EstadoCuentaAnual::getCuentaId)
                .thenComparing(EstadoCuentaAnual::getAnio));
        estados.saveAll(resultado);
        exportar(carpetaSalida.resolve(Constantes.SALIDA_ESTADOS_CUENTA), resultado);

        contribucion.incrementWriteCount(resultado.size());
        log.info("Estados de cuenta compilados: {} combinaciones cuenta/ano, {} movimientos",
                resultado.size(),
                resultado.stream().mapToLong(EstadoCuentaAnual::getCantidadMovimientos).sum());
        return RepeatStatus.FINISHED;
    }

    private void acumular(Map<String, EstadoCuentaAnual> acumulado, MovimientoAnual movimiento, Long jobExecutionId) {
        String clave = movimiento.getCuentaId() + "-" + movimiento.getAnio();
        EstadoCuentaAnual estado = acumulado.computeIfAbsent(clave,
                ignorada -> new EstadoCuentaAnual(movimiento.getCuentaId(), movimiento.getAnio(), jobExecutionId));

        estado.setCantidadMovimientos(estado.getCantidadMovimientos() + 1);
        if (movimiento.isAnomalia()) {
            estado.setMovimientosConAnomalia(estado.getMovimientosConAnomalia() + 1);
        }

        BigDecimal monto = movimiento.getMonto();
        if (monto.compareTo(BigDecimal.ZERO) >= 0) {
            estado.setTotalDepositos(estado.getTotalDepositos().add(monto));
        } else {
            estado.setTotalCargos(estado.getTotalCargos().add(monto.abs()));
        }
        estado.setSaldoNeto(estado.getTotalDepositos().subtract(estado.getTotalCargos()));

        if (estado.getPrimeraFecha() == null || movimiento.getFecha().isBefore(estado.getPrimeraFecha())) {
            estado.setPrimeraFecha(movimiento.getFecha());
        }
        if (estado.getUltimaFecha() == null || movimiento.getFecha().isAfter(estado.getUltimaFecha())) {
            estado.setUltimaFecha(movimiento.getFecha());
        }
    }

    private void exportar(Path destino, List<EstadoCuentaAnual> estados) throws Exception {
        List<String> filas = estados.stream()
                .map(estado -> EscritorCsv.fila(
                        estado.getCuentaId(),
                        estado.getAnio(),
                        estado.getCantidadMovimientos(),
                        EscritorCsv.importe(estado.getTotalDepositos()),
                        EscritorCsv.importe(estado.getTotalCargos()),
                        EscritorCsv.importe(estado.getSaldoNeto()),
                        estado.getPrimeraFecha(),
                        estado.getUltimaFecha(),
                        estado.getMovimientosConAnomalia()))
                .toList();
        EscritorCsv.escribir(destino,
                "cuenta_id,anio,cantidad_movimientos,total_depositos,total_cargos,saldo_neto,"
                        + "primera_fecha,ultima_fecha,movimientos_con_anomalia",
                filas);
        log.info("Archivo de salida generado: {}", destino);
    }
}
