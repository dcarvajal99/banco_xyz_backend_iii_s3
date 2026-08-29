package com.bancoxyz.batch.tasklet;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.CuentaInteres;
import com.bancoxyz.repository.CuentaInteresRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Segundo Step del Job 2: exporta el detalle de intereses y consolida por tipo de cuenta.
 *
 * <p>El saldo final ya quedo actualizado en la tabla {@code cuenta_interes} durante el
 * Step anterior; este paso produce el archivo que el area de operaciones concilia contra
 * la liquidacion mensual y deja en el log los totales por producto.</p>
 */
@Component
public class ResumenInteresesTasklet implements Tasklet {

    private static final int TAMANO_PAGINA = 500;
    private static final Logger log = LoggerFactory.getLogger(ResumenInteresesTasklet.class);

    private final CuentaInteresRepository cuentas;

    public ResumenInteresesTasklet(CuentaInteresRepository cuentas) {
        this.cuentas = cuentas;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) throws Exception {
        Long jobExecutionId = contexto.getStepContext().getStepExecution().getJobExecutionId();
        Path carpetaSalida = Path.of(String.valueOf(
                contexto.getStepContext().getJobParameters().get(Constantes.PARAM_SALIDA)));

        List<String> filas = new ArrayList<>();
        Map<String, BigDecimal> interesPorTipo = new LinkedHashMap<>();
        Map<String, Long> cuentasPorTipo = new LinkedHashMap<>();
        BigDecimal interesTotal = BigDecimal.ZERO;

        int pagina = 0;
        Page<CuentaInteres> lote;
        do {
            lote = cuentas.findByJobExecutionId(jobExecutionId,
                    PageRequest.of(pagina++, TAMANO_PAGINA, Sort.by("cuentaId")));
            for (CuentaInteres cuenta : lote) {
                filas.add(EscritorCsv.fila(
                        cuenta.getCuentaId(),
                        cuenta.getNombre(),
                        cuenta.getTipo(),
                        cuenta.getEdad(),
                        EscritorCsv.importe(cuenta.getSaldoInicial()),
                        cuenta.getTasaMensual().toPlainString(),
                        EscritorCsv.importe(cuenta.getInteres()),
                        EscritorCsv.importe(cuenta.getSaldoFinal()),
                        cuenta.isAnomalia() ? "SI" : "NO",
                        cuenta.getObservacion() == null ? "" : cuenta.getObservacion()));
                interesPorTipo.merge(cuenta.getTipo(), cuenta.getInteres(), BigDecimal::add);
                cuentasPorTipo.merge(cuenta.getTipo(), 1L, Long::sum);
                interesTotal = interesTotal.add(cuenta.getInteres());
            }
        } while (lote.hasNext());

        EscritorCsv.escribir(carpetaSalida.resolve(Constantes.SALIDA_INTERESES),
                "cuenta_id,nombre,tipo,edad,saldo_inicial,tasa_mensual,interes,saldo_final,anomalia,observacion",
                filas);

        contribucion.incrementWriteCount(filas.size());
        log.info("Archivo de salida generado: {}",
                carpetaSalida.resolve(Constantes.SALIDA_INTERESES));
        log.info("Interes liquidado: {} cuentas, total {}", filas.size(), interesTotal.toPlainString());
        interesPorTipo.forEach((tipo, total) ->
                log.info("   - {}: {} cuentas, interes {}", tipo, cuentasPorTipo.get(tipo), total.toPlainString()));
        return RepeatStatus.FINISHED;
    }
}
