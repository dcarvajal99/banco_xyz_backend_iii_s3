package com.bancoxyz.batch.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre el caso que motiva la clase: distinguir un duplicado real de una fila que Spring
 * Batch vuelve a procesar despues de revertir un chunk.
 */
class DetectorDeDuplicadosTest {

    @Test
    @DisplayName("La primera aparicion de una clave no es duplicado")
    void primeraAparicionNoEsDuplicado() {
        DetectorDeDuplicados detector = new DetectorDeDuplicados();
        assertThat(detector.esDuplicado("101", 2)).isFalse();
    }

    @Test
    @DisplayName("La misma clave en otra linea si es duplicado")
    void mismaClaveEnOtraLineaEsDuplicado() {
        DetectorDeDuplicados detector = new DetectorDeDuplicados();
        detector.esDuplicado("101", 2);
        assertThat(detector.esDuplicado("101", 9)).isTrue();
    }

    @Test
    @DisplayName("Reprocesar la misma linea tras un rollback NO cuenta como duplicado")
    void reprocesarLaMismaLineaNoEsDuplicado() {
        DetectorDeDuplicados detector = new DetectorDeDuplicados();
        detector.esDuplicado("101", 2);

        // Spring Batch revierte el chunk y vuelve a pasar la fila 2 por el procesador.
        assertThat(detector.esDuplicado("101", 2)).isFalse();
        assertThat(detector.esDuplicado("101", 2)).isFalse();
    }

    @Test
    @DisplayName("Cuenta las claves distintas registradas")
    void cuentaClavesDistintas() {
        DetectorDeDuplicados detector = new DetectorDeDuplicados();
        detector.esDuplicado("101", 2);
        detector.esDuplicado("102", 3);
        detector.esDuplicado("101", 4);
        assertThat(detector.clavesRegistradas()).isEqualTo(2);
    }

    @Test
    @DisplayName("Con varios hilos sobre la misma clave, exactamente uno la ve como nueva")
    void bajoConcurrenciaSoloUnHiloGanaLaClave() throws Exception {
        DetectorDeDuplicados detector = new DetectorDeDuplicados();
        int hilos = 8;
        // Cada hilo trae la MISMA clave desde una linea distinta, que es justo lo que pasa
        // cuando tres chunks con filas repetidas se procesan a la vez. Si el mapa no fuera
        // concurrente, varios hilos podrian ver la clave como nueva y la fila se migraria
        // mas de una vez: un movimiento bancario duplicado.
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        try {
            for (int i = 0; i < hilos; i++) {
                int linea = i + 1;
                resultados.add(pool.submit(() -> {
                    salida.await();
                    return detector.esDuplicado("cuenta-101", linea);
                }));
            }
            salida.countDown();
            long nuevas = 0;
            for (Future<Boolean> resultado : resultados) {
                if (!resultado.get(10, TimeUnit.SECONDS)) {
                    nuevas++;
                }
            }
            assertThat(nuevas).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(detector.clavesRegistradas()).isEqualTo(1);
    }

    @Test
    @DisplayName("Miles de claves distintas en paralelo se registran todas, sin perder ninguna")
    void noPierdeClavesAlCrecerElMapaEnParalelo() throws Exception {
        DetectorDeDuplicados detector = new DetectorDeDuplicados();
        int hilos = 4;
        int clavesPorHilo = 2000;
        // Un HashMap corriente puede perder entradas (o corromper su tabla interna) si dos
        // hilos lo hacen crecer a la vez. Esta prueba lo detectaria: faltarian claves.
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch listos = new CountDownLatch(hilos);
        try {
            for (int h = 0; h < hilos; h++) {
                int base = h * clavesPorHilo;
                pool.submit(() -> {
                    for (int i = 0; i < clavesPorHilo; i++) {
                        detector.esDuplicado("clave-" + (base + i), base + i + 1);
                    }
                    listos.countDown();
                });
            }
            assertThat(listos.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(detector.clavesRegistradas()).isEqualTo(hilos * clavesPorHilo);
    }
}
