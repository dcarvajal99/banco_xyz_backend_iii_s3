package com.bancoxyz.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de las entidades destino: accesores, identidad y representacion.
 *
 * <p>Se prueban porque el {@code equals}/{@code hashCode} de estas clases no es
 * decorativo: los Steps de agregacion las usan como claves de mapas y una identidad mal
 * definida produciria estados de cuenta mezclados entre corridas.</p>
 */
class EntidadesDestinoTest {

    @Nested
    @DisplayName("Transaccion")
    class TransaccionTest {

        @Test
        @DisplayName("Guarda y devuelve todos sus campos")
        void guardaSusCampos() {
            Transaccion transaccion = new Transaccion(7L, LocalDate.of(2024, 1, 5),
                    new BigDecimal("700.00"), "debito");
            transaccion.setId(1L);
            transaccion.setAnomalia(true);
            transaccion.setObservacion("Posible duplicado");
            transaccion.setJobExecutionId(9L);
            LocalDateTime momento = LocalDateTime.of(2024, 1, 5, 10, 0);
            transaccion.setProcesadoEn(momento);

            assertThat(transaccion.getId()).isEqualTo(1L);
            assertThat(transaccion.getIdOrigen()).isEqualTo(7L);
            assertThat(transaccion.getFecha()).isEqualTo(LocalDate.of(2024, 1, 5));
            assertThat(transaccion.getMonto()).isEqualByComparingTo("700.00");
            assertThat(transaccion.getTipo()).isEqualTo("debito");
            assertThat(transaccion.isAnomalia()).isTrue();
            assertThat(transaccion.getObservacion()).isEqualTo("Posible duplicado");
            assertThat(transaccion.getJobExecutionId()).isEqualTo(9L);
            assertThat(transaccion.getProcesadoEn()).isEqualTo(momento);
            assertThat(transaccion.toString()).contains("idOrigen=7", "debito");
        }

        @Test
        @DisplayName("Dos transacciones son la misma si comparten origen y ejecucion")
        void identidadPorOrigenYEjecucion() {
            Transaccion una = new Transaccion(7L, LocalDate.of(2024, 1, 5), BigDecimal.TEN, "debito");
            una.setJobExecutionId(9L);
            Transaccion otra = new Transaccion(7L, LocalDate.of(2024, 2, 5), BigDecimal.ONE, "credito");
            otra.setJobExecutionId(9L);
            Transaccion deOtraCorrida = new Transaccion(7L, LocalDate.of(2024, 1, 5), BigDecimal.TEN, "debito");
            deOtraCorrida.setJobExecutionId(10L);

            assertThat(una).isEqualTo(una).isEqualTo(otra).hasSameHashCodeAs(otra);
            assertThat(una).isNotEqualTo(deOtraCorrida).isNotEqualTo("otra cosa").isNotEqualTo(null);
        }

        @Test
        @DisplayName("La observacion se recorta al largo de la columna")
        void recortaLaObservacion() {
            Transaccion transaccion = new Transaccion(1L, LocalDate.now(), BigDecimal.ONE, "debito");
            transaccion.setObservacion("x".repeat(400));

            assertThat(transaccion.getObservacion()).hasSize(255);
        }

        @Test
        @DisplayName("Setters sueltos de la entidad")
        void aceptaSettersSueltos() {
            Transaccion transaccion = new Transaccion();
            transaccion.setIdOrigen(3L);
            transaccion.setFecha(LocalDate.of(2024, 3, 3));
            transaccion.setMonto(new BigDecimal("5"));
            transaccion.setTipo("credito");
            transaccion.setObservacion(null);

            assertThat(transaccion.getIdOrigen()).isEqualTo(3L);
            assertThat(transaccion.getFecha()).isEqualTo(LocalDate.of(2024, 3, 3));
            assertThat(transaccion.getMonto()).isEqualByComparingTo("5");
            assertThat(transaccion.getTipo()).isEqualTo("credito");
            assertThat(transaccion.getObservacion()).isNull();
        }
    }

    @Nested
    @DisplayName("ResumenDiario")
    class ResumenDiarioTest {

        @Test
        @DisplayName("El saldo neto es creditos menos debitos")
        void calculaElSaldoNeto() {
            ResumenDiario dia = new ResumenDiario(LocalDate.of(2024, 1, 1), 5L);
            dia.setId(2L);
            dia.setCantidadTransacciones(3);
            dia.setTotalDebitos(new BigDecimal("1200.00"));
            dia.setTotalCreditos(new BigDecimal("800.00"));
            dia.setMontoMaximo(new BigDecimal("1200.00"));
            dia.setCantidadAnomalias(1);
            dia.setGeneradoEn(LocalDateTime.of(2024, 1, 2, 0, 0));

            assertThat(dia.getSaldoNeto()).isEqualByComparingTo("-400.00");
            assertThat(dia.getId()).isEqualTo(2L);
            assertThat(dia.getCantidadTransacciones()).isEqualTo(3);
            assertThat(dia.getMontoMaximo()).isEqualByComparingTo("1200.00");
            assertThat(dia.getCantidadAnomalias()).isEqualTo(1);
            assertThat(dia.getJobExecutionId()).isEqualTo(5L);
            assertThat(dia.getGeneradoEn()).isEqualTo(LocalDateTime.of(2024, 1, 2, 0, 0));
            assertThat(dia.toString()).contains("2024-01-01");
        }

        @Test
        @DisplayName("Identidad por fecha y ejecucion")
        void identidadPorFechaYEjecucion() {
            ResumenDiario uno = new ResumenDiario(LocalDate.of(2024, 1, 1), 5L);
            ResumenDiario otro = new ResumenDiario(LocalDate.of(2024, 1, 1), 5L);
            ResumenDiario distinto = new ResumenDiario(LocalDate.of(2024, 1, 2), 5L);
            distinto.setFecha(LocalDate.of(2024, 1, 3));

            assertThat(uno).isEqualTo(otro).hasSameHashCodeAs(otro);
            assertThat(uno).isNotEqualTo(distinto).isNotEqualTo("x");
        }
    }

    @Nested
    @DisplayName("CuentaInteres")
    class CuentaInteresTest {

        @Test
        @DisplayName("Guarda el calculo desglosado para auditoria")
        void guardaElCalculoDesglosado() {
            CuentaInteres cuenta = new CuentaInteres();
            cuenta.setId(4L);
            cuenta.setCuentaId(101L);
            cuenta.setNombre("John Doe");
            cuenta.setTipo("ahorro");
            cuenta.setEdad(30);
            cuenta.setSaldoInicial(new BigDecimal("5000.00"));
            cuenta.setTasaMensual(new BigDecimal("0.00500"));
            cuenta.setInteres(new BigDecimal("25.00"));
            cuenta.setSaldoFinal(new BigDecimal("5025.00"));
            cuenta.setAnomalia(false);
            cuenta.setObservacion("y".repeat(300));
            cuenta.setJobExecutionId(8L);
            cuenta.setProcesadoEn(LocalDateTime.of(2024, 2, 1, 0, 0));

            assertThat(cuenta.getId()).isEqualTo(4L);
            assertThat(cuenta.getCuentaId()).isEqualTo(101L);
            assertThat(cuenta.getNombre()).isEqualTo("John Doe");
            assertThat(cuenta.getTipo()).isEqualTo("ahorro");
            assertThat(cuenta.getEdad()).isEqualTo(30);
            assertThat(cuenta.getSaldoInicial()).isEqualByComparingTo("5000.00");
            assertThat(cuenta.getTasaMensual()).isEqualByComparingTo("0.00500");
            assertThat(cuenta.getInteres()).isEqualByComparingTo("25.00");
            assertThat(cuenta.getSaldoFinal()).isEqualByComparingTo("5025.00");
            assertThat(cuenta.isAnomalia()).isFalse();
            assertThat(cuenta.getObservacion()).hasSize(255);
            assertThat(cuenta.getJobExecutionId()).isEqualTo(8L);
            assertThat(cuenta.getProcesadoEn()).isEqualTo(LocalDateTime.of(2024, 2, 1, 0, 0));
            assertThat(cuenta.toString()).contains("cuentaId=101");
        }

        @Test
        @DisplayName("Identidad por cuenta y ejecucion")
        void identidadPorCuentaYEjecucion() {
            CuentaInteres una = new CuentaInteres();
            una.setCuentaId(101L);
            una.setJobExecutionId(1L);
            CuentaInteres otra = new CuentaInteres();
            otra.setCuentaId(101L);
            otra.setJobExecutionId(1L);
            CuentaInteres tercera = new CuentaInteres();
            tercera.setCuentaId(102L);
            tercera.setJobExecutionId(1L);

            assertThat(una).isEqualTo(otra).hasSameHashCodeAs(otra);
            assertThat(una).isNotEqualTo(tercera).isNotEqualTo("x");
        }
    }

    @Nested
    @DisplayName("MovimientoAnual")
    class MovimientoAnualTest {

        @Test
        @DisplayName("Al fijar la fecha deriva el ano automaticamente")
        void derivaElAnoDesdeLaFecha() {
            MovimientoAnual movimiento = new MovimientoAnual();
            movimiento.setId(3L);
            movimiento.setCuentaId(101L);
            movimiento.setFecha(LocalDate.of(2024, 5, 22));
            movimiento.setTipoTransaccion("deposito");
            movimiento.setMonto(new BigDecimal("1500.00"));
            movimiento.setDescripcion("Ingreso mensual");
            movimiento.setAnomalia(true);
            movimiento.setObservacion("z".repeat(300));
            movimiento.setJobExecutionId(6L);
            movimiento.setProcesadoEn(LocalDateTime.of(2024, 5, 23, 0, 0));

            assertThat(movimiento.getAnio()).isEqualTo(2024);
            assertThat(movimiento.getId()).isEqualTo(3L);
            assertThat(movimiento.getCuentaId()).isEqualTo(101L);
            assertThat(movimiento.getTipoTransaccion()).isEqualTo("deposito");
            assertThat(movimiento.getMonto()).isEqualByComparingTo("1500.00");
            assertThat(movimiento.getDescripcion()).isEqualTo("Ingreso mensual");
            assertThat(movimiento.isAnomalia()).isTrue();
            assertThat(movimiento.getObservacion()).hasSize(255);
            assertThat(movimiento.getJobExecutionId()).isEqualTo(6L);
            assertThat(movimiento.getProcesadoEn()).isEqualTo(LocalDateTime.of(2024, 5, 23, 0, 0));
            assertThat(movimiento.toString()).contains("cuentaId=101");
        }

        @Test
        @DisplayName("Una fecha nula deja el ano en cero y el ano puede fijarse a mano")
        void toleraFechaNula() {
            MovimientoAnual movimiento = new MovimientoAnual();
            movimiento.setFecha(null);
            assertThat(movimiento.getAnio()).isZero();

            movimiento.setAnio(2023);
            assertThat(movimiento.getAnio()).isEqualTo(2023);
        }

        @Test
        @DisplayName("Identidad por identificador tecnico")
        void identidadPorId() {
            MovimientoAnual uno = new MovimientoAnual();
            uno.setId(1L);
            MovimientoAnual otro = new MovimientoAnual();
            otro.setId(1L);
            MovimientoAnual distinto = new MovimientoAnual();
            distinto.setId(2L);

            assertThat(uno).isEqualTo(otro).hasSameHashCodeAs(otro);
            assertThat(uno).isNotEqualTo(distinto).isNotEqualTo("x");
        }
    }

    @Nested
    @DisplayName("EstadoCuentaAnual")
    class EstadoCuentaAnualTest {

        @Test
        @DisplayName("Guarda los totales y la ventana de fechas del ano")
        void guardaLosTotales() {
            EstadoCuentaAnual estado = new EstadoCuentaAnual(101L, 2024, 3L);
            estado.setId(9L);
            estado.setCantidadMovimientos(2);
            estado.setTotalDepositos(new BigDecimal("1000.00"));
            estado.setTotalCargos(new BigDecimal("500.00"));
            estado.setSaldoNeto(new BigDecimal("500.00"));
            estado.setPrimeraFecha(LocalDate.of(2024, 1, 1));
            estado.setUltimaFecha(LocalDate.of(2024, 3, 15));
            estado.setMovimientosConAnomalia(1);
            estado.setCuentaId(101L);
            estado.setAnio(2024);
            estado.setGeneradoEn(LocalDateTime.of(2025, 1, 1, 0, 0));

            assertThat(estado.getId()).isEqualTo(9L);
            assertThat(estado.getCuentaId()).isEqualTo(101L);
            assertThat(estado.getAnio()).isEqualTo(2024);
            assertThat(estado.getCantidadMovimientos()).isEqualTo(2);
            assertThat(estado.getTotalDepositos()).isEqualByComparingTo("1000.00");
            assertThat(estado.getTotalCargos()).isEqualByComparingTo("500.00");
            assertThat(estado.getSaldoNeto()).isEqualByComparingTo("500.00");
            assertThat(estado.getPrimeraFecha()).isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(estado.getUltimaFecha()).isEqualTo(LocalDate.of(2024, 3, 15));
            assertThat(estado.getMovimientosConAnomalia()).isEqualTo(1);
            assertThat(estado.getJobExecutionId()).isEqualTo(3L);
            assertThat(estado.getGeneradoEn()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0));
            assertThat(estado.toString()).contains("cuentaId=101", "anio=2024");
        }

        @Test
        @DisplayName("Identidad por cuenta, ano y ejecucion")
        void identidadPorCuentaAnoYEjecucion() {
            EstadoCuentaAnual uno = new EstadoCuentaAnual(101L, 2024, 3L);
            EstadoCuentaAnual otro = new EstadoCuentaAnual(101L, 2024, 3L);
            EstadoCuentaAnual distinto = new EstadoCuentaAnual(101L, 2023, 3L);
            EstadoCuentaAnual vacio = new EstadoCuentaAnual();
            vacio.setJobExecutionId(3L);

            assertThat(uno).isEqualTo(otro).hasSameHashCodeAs(otro);
            assertThat(uno).isNotEqualTo(distinto).isNotEqualTo(vacio).isNotEqualTo("x");
        }
    }

    @Nested
    @DisplayName("RegistroRechazado")
    class RegistroRechazadoTest {

        @Test
        @DisplayName("Recorta el contenido y el motivo al largo de sus columnas")
        void recortaContenidoYMotivo() {
            RegistroRechazado registro = new RegistroRechazado("job", "archivo.csv", "OMITIDO", 12,
                    "c".repeat(700), "m".repeat(400), 4L);

            assertThat(registro.getContenido()).hasSize(500);
            assertThat(registro.getMotivo()).hasSize(255);
            assertThat(registro.getNumeroLinea()).isEqualTo(12);
            assertThat(registro.getJobNombre()).isEqualTo("job");
            assertThat(registro.getArchivo()).isEqualTo("archivo.csv");
            assertThat(registro.getClasificacion()).isEqualTo("OMITIDO");
            assertThat(registro.getJobExecutionId()).isEqualTo(4L);
            assertThat(registro.toString()).contains("archivo.csv", "linea=12");
        }

        @Test
        @DisplayName("Setters sueltos e identidad por identificador")
        void aceptaSettersEIdentidad() {
            RegistroRechazado registro = new RegistroRechazado();
            registro.setId(1L);
            registro.setJobNombre("otro");
            registro.setArchivo("x.csv");
            registro.setClasificacion("FILTRADO");
            registro.setNumeroLinea(3);
            registro.setContenido("linea");
            registro.setMotivo("duplicado");
            registro.setJobExecutionId(2L);
            registro.setRegistradoEn(LocalDateTime.of(2024, 1, 1, 0, 0));

            RegistroRechazado mismo = new RegistroRechazado();
            mismo.setId(1L);
            RegistroRechazado distinto = new RegistroRechazado();
            distinto.setId(2L);

            assertThat(registro.getJobNombre()).isEqualTo("otro");
            assertThat(registro.getClasificacion()).isEqualTo("FILTRADO");
            assertThat(registro.getContenido()).isEqualTo("linea");
            assertThat(registro.getMotivo()).isEqualTo("duplicado");
            assertThat(registro.getRegistradoEn()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
            assertThat(registro).isEqualTo(mismo).hasSameHashCodeAs(mismo);
            assertThat(registro).isNotEqualTo(distinto).isNotEqualTo("x");
        }
    }
}
