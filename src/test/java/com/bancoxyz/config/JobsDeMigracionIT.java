package com.bancoxyz.config;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.CuentaInteres;
import com.bancoxyz.entity.EstadoCuentaAnual;
import com.bancoxyz.entity.MovimientoAnual;
import com.bancoxyz.entity.RegistroRechazado;
import com.bancoxyz.entity.ResumenDiario;
import com.bancoxyz.entity.Transaccion;
import com.bancoxyz.repository.CuentaInteresRepository;
import com.bancoxyz.repository.EstadoCuentaAnualRepository;
import com.bancoxyz.repository.MovimientoAnualRepository;
import com.bancoxyz.repository.RegistroRechazadoRepository;
import com.bancoxyz.repository.ResumenDiarioRepository;
import com.bancoxyz.repository.TransaccionRepository;
import com.bancoxyz.service.RegistroRechazadoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integracion de los tres Jobs de extremo a extremo, sobre H2 en memoria y un
 * juego de datos que reproduce todos los defectos del sistema legacy.
 *
 * <p>Verifican lo que la pauta pide demostrar: que los Jobs y Steps se ejecutan, que los
 * errores se manejan sin detener el proceso y que la salida se genera y se persiste.</p>
 */
@SpringBootTest
class JobsDeMigracionIT {

    private static final Path ENTRADA = Path.of("src", "test", "resources", "data", "prueba");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("reporteTransaccionesDiariasJob")
    private Job jobTransacciones;

    @Autowired
    @Qualifier("calculoInteresesMensualesJob")
    private Job jobIntereses;

    @Autowired
    @Qualifier("estadosCuentaAnualesJob")
    private Job jobEstadosCuenta;

    @Autowired
    @Qualifier("migracionCompletaJob")
    private Job jobMigracionCompleta;

    @Autowired
    private TransaccionRepository transacciones;

    @Autowired
    private ResumenDiarioRepository resumenes;

    @Autowired
    private CuentaInteresRepository cuentas;

    @Autowired
    private MovimientoAnualRepository movimientos;

    @Autowired
    private EstadoCuentaAnualRepository estados;

    @Autowired
    private RegistroRechazadoRepository rechazados;

    private JobParameters parametros(Path salida) {
        return new JobParametersBuilder()
                .addString(Constantes.PARAM_ENTRADA, ENTRADA.toString())
                .addString(Constantes.PARAM_SALIDA, salida.toString())
                .addString(Constantes.PARAM_EJECUCION, LocalDateTime.now() + "-" + UUID.randomUUID())
                .toJobParameters();
    }

    private static StepExecution paso(JobExecution ejecucion, String nombre) {
        return ejecucion.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(nombre))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se ejecuto el Step " + nombre));
    }

    @Test
    @DisplayName("Job 1: migra las transacciones validas, omite las sucias y compila el reporte diario")
    void ejecutaElReporteDeTransaccionesDiarias(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones, parametros(salida));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution procesar = paso(ejecucion, Constantes.STEP_PROCESAR_TRANSACCIONES);
        assertThat(procesar.getReadCount()).isEqualTo(8);
        // Se omiten monto vacio, fecha inexistente y tipo desconocido.
        assertThat(procesar.getSkipCount()).isEqualTo(3);
        assertThat(procesar.getWriteCount()).isEqualTo(5);

        List<Transaccion> migradas = transacciones.findByJobExecutionId(ejecucion.getId(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent();
        assertThat(migradas).hasSize(5);
        assertThat(migradas).filteredOn(Transaccion::isAnomalia).hasSize(3);
        assertThat(migradas).extracting(Transaccion::getIdOrigen)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 7L, 8L);

        List<ResumenDiario> reporte = resumenes.findByJobExecutionIdOrderByFechaAsc(ejecucion.getId());
        assertThat(reporte).isNotEmpty();
        ResumenDiario primerDia = reporte.get(0);
        assertThat(primerDia.getFecha()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(primerDia.getCantidadTransacciones()).isEqualTo(2);
        assertThat(primerDia.getTotalDebitos()).isEqualByComparingTo("2000");
        assertThat(primerDia.getCantidadAnomalias()).isEqualTo(1);

        Path archivo = salida.resolve(Constantes.SALIDA_TRANSACCIONES);
        assertThat(archivo).exists();
        List<String> lineas = Files.readAllLines(archivo);
        assertThat(lineas.get(0)).startsWith("fecha,cantidad_transacciones");
        assertThat(lineas).hasSize(reporte.size() + 1);
    }

    @Test
    @DisplayName("Job 2: calcula el interes, actualiza el saldo final y deja el detalle liquidado")
    void ejecutaElCalculoDeInteresesMensuales(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobIntereses, parametros(salida));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution procesar = paso(ejecucion, Constantes.STEP_PROCESAR_INTERESES);
        assertThat(procesar.getReadCount()).isEqualTo(9);
        // Se omiten tipo '-1', saldo vacio y edad 100.
        assertThat(procesar.getSkipCount()).isEqualTo(3);
        // Se filtra la fila 101 repetida identica.
        assertThat(procesar.getFilterCount()).isEqualTo(1);
        assertThat(procesar.getWriteCount()).isEqualTo(5);

        List<CuentaInteres> liquidadas = cuentas.findByJobExecutionId(ejecucion.getId(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent();
        assertThat(liquidadas).hasSize(5);

        CuentaInteres ahorro = liquidadas.stream()
                .filter(cuenta -> cuenta.getCuentaId() == 101L)
                .findFirst().orElseThrow();
        assertThat(ahorro.getSaldoInicial()).isEqualByComparingTo("5000.00");
        assertThat(ahorro.getInteres()).isEqualByComparingTo("25.00");
        assertThat(ahorro.getSaldoFinal()).isEqualByComparingTo("5025.00");

        CuentaInteres terceraEdad = liquidadas.stream()
                .filter(cuenta -> cuenta.getCuentaId() == 108L)
                .findFirst().orElseThrow();
        assertThat(terceraEdad.getTasaMensual()).isEqualByComparingTo("0.00600");
        assertThat(terceraEdad.getNombre()).isEqualTo("Sin identificar");

        assertThat(salida.resolve(Constantes.SALIDA_INTERESES)).exists();
        assertThat(Files.readAllLines(salida.resolve(Constantes.SALIDA_INTERESES))).hasSize(6);
    }

    @Test
    @DisplayName("Job 3: normaliza los movimientos y compila el estado de cuenta anual")
    void ejecutaLosEstadosDeCuentaAnuales(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobEstadosCuenta, parametros(salida));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution procesar = paso(ejecucion, Constantes.STEP_PROCESAR_MOVIMIENTOS);
        assertThat(procesar.getReadCount()).isEqualTo(8);
        // Se omiten el tipo 'transferencia' y el monto vacio.
        assertThat(procesar.getSkipCount()).isEqualTo(2);
        assertThat(procesar.getWriteCount()).isEqualTo(6);

        List<MovimientoAnual> normalizados = movimientos.findByJobExecutionId(ejecucion.getId(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent();
        assertThat(normalizados).allSatisfy(movimiento -> {
            if (Constantes.MOVIMIENTOS_DE_CARGO.contains(movimiento.getTipoTransaccion())) {
                assertThat(movimiento.getMonto()).isLessThanOrEqualTo(BigDecimal.ZERO);
            } else {
                assertThat(movimiento.getMonto()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            }
            assertThat(movimiento.getDescripcion()).isNotBlank();
        });

        List<EstadoCuentaAnual> compilados =
                estados.findByJobExecutionIdOrderByCuentaIdAscAnioAsc(ejecucion.getId());
        EstadoCuentaAnual cuenta101 = compilados.stream()
                .filter(estado -> estado.getCuentaId() == 101L)
                .findFirst().orElseThrow();
        assertThat(cuenta101.getAnio()).isEqualTo(2024);
        assertThat(cuenta101.getCantidadMovimientos()).isEqualTo(2);
        assertThat(cuenta101.getTotalDepositos()).isEqualByComparingTo("1000.00");
        assertThat(cuenta101.getTotalCargos()).isEqualByComparingTo("500.00");
        assertThat(cuenta101.getSaldoNeto()).isEqualByComparingTo("500.00");
        assertThat(cuenta101.getPrimeraFecha()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(cuenta101.getUltimaFecha()).isEqualTo(LocalDate.of(2024, 3, 15));

        assertThat(salida.resolve(Constantes.SALIDA_ESTADOS_CUENTA)).exists();
    }

    @Test
    @DisplayName("Cada fila descartada queda en la bitacora con su linea y su motivo")
    void registraLosRechazosConTrazabilidad(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobTransacciones, parametros(salida));

        List<RegistroRechazado> bitacora = rechazados.findByJobExecutionIdOrderByIdAsc(ejecucion.getId());
        assertThat(bitacora).hasSize(3);
        assertThat(bitacora).allSatisfy(registro -> {
            assertThat(registro.getArchivo()).isEqualTo(Constantes.ARCHIVO_TRANSACCIONES);
            assertThat(registro.getClasificacion()).isEqualTo(RegistroRechazadoService.CLASIFICACION_OMITIDO);
            assertThat(registro.getNumeroLinea()).isPositive();
            assertThat(registro.getMotivo()).isNotBlank();
            assertThat(registro.getContenido()).isNotBlank();
        });
        assertThat(bitacora).extracting(RegistroRechazado::getNumeroLinea)
                .containsExactlyInAnyOrder(5, 6, 7);

        Path archivo = salida.resolve("rechazados_reporteTransaccionesDiarias.csv");
        assertThat(archivo).exists();
        assertThat(Files.readAllLines(archivo)).hasSize(4);
    }

    @Test
    @DisplayName("El Job de migracion completa ejecuta los tres flujos en paralelo y consolida los rechazos")
    void ejecutaLaMigracionCompleta(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = jobLauncher.run(jobMigracionCompleta, parametros(salida));

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(ejecucion.getStepExecutions()).extracting(StepExecution::getStepName)
                .contains(Constantes.STEP_PROCESAR_TRANSACCIONES,
                        Constantes.STEP_PROCESAR_INTERESES,
                        Constantes.STEP_PROCESAR_MOVIMIENTOS,
                        Constantes.STEP_RESUMEN_DIARIO,
                        Constantes.STEP_RESUMEN_INTERESES,
                        Constantes.STEP_ESTADOS_CUENTA,
                        "exportarRechazadosStep");

        // 3 + 3 + 2 omitidos y 1 filtrado, todos en la misma JobExecution.
        assertThat(rechazados.countByJobExecutionId(ejecucion.getId())).isEqualTo(9);
        assertThat(salida.resolve(Constantes.SALIDA_TRANSACCIONES)).exists();
        assertThat(salida.resolve(Constantes.SALIDA_INTERESES)).exists();
        assertThat(salida.resolve(Constantes.SALIDA_ESTADOS_CUENTA)).exists();
        assertThat(salida.resolve("rechazados_migracionCompleta.csv")).exists();
    }
}
