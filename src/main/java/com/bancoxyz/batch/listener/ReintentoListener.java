package com.bancoxyz.batch.listener;

import com.bancoxyz.batch.policy.PoliticaReintentoBancaria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;

/**
 * Deja en el log cada reintento, con el hilo que lo provoco y el numero de intento.
 *
 * <p>Sin esto, un reintento es invisible: el chunk falla, se reintenta, funciona, y el Job
 * termina en {@code COMPLETED} sin que nadie sepa que la base de datos estuvo intermitente.
 * El unico rastro seria el contador de rollbacks, que no distingue un reintento de una
 * omision por dato sucio.</p>
 *
 * <p>Se implementa la interfaz de <b>spring-retry</b> ({@code org.springframework.retry.RetryListener}):
 * en Spring Batch 5 no existe una jerarquia propia de listeners de reintento, y es la que
 * acepta {@code FaultTolerantStepBuilder.listener(...)}. Sus cuatro metodos son {@code default},
 * asi que aqui solo se sobrescriben los dos que interesan.</p>
 */
public class ReintentoListener implements RetryListener {

    private static final Logger log = LoggerFactory.getLogger(ReintentoListener.class);

    private final String nombrePaso;

    public ReintentoListener(String nombrePaso) {
        this.nombrePaso = nombrePaso;
    }

    @Override
    public <T, E extends Throwable> void onError(RetryContext contexto,
                                                 RetryCallback<T, E> operacion,
                                                 Throwable error) {
        // Solo se registra lo que de verdad se va a reintentar. Un dato invalido tambien pasa
        // por aqui —Spring Batch comparte el RetryTemplate entre reintentos y omisiones— pero
        // no se reintenta: lo maneja la politica de omision y ya queda en la bitacora.
        if (!PoliticaReintentoBancaria.esReintentable(error)) {
            return;
        }
        log.warn("[{}] reintento {} en el hilo {} tras {}: {}",
                nombrePaso, contexto.getRetryCount(), Thread.currentThread().getName(),
                error.getClass().getSimpleName(), error.getMessage());
    }

    @Override
    public <T, E extends Throwable> void close(RetryContext contexto,
                                               RetryCallback<T, E> operacion,
                                               Throwable error) {
        // getRetryCount() > 0 significa que hubo al menos un fallo antes de cerrar el ciclo.
        // Se filtra igual que en onError: cerrar un ciclo que solo tuvo datos sucios no es
        // "el reintento funciono", es una omision, y decir lo contrario falsea la evidencia.
        Throwable ultimoError = error != null ? error : contexto.getLastThrowable();
        if (contexto.getRetryCount() > 0 && PoliticaReintentoBancaria.esReintentable(ultimoError)) {
            if (error == null) {
                log.info("[{}] el reintento funciono: la operacion se completo tras {} intento(s) fallido(s)",
                        nombrePaso, contexto.getRetryCount());
            } else {
                // Que este ciclo cierre con error no significa que el chunk se pierda. Un fallo
                // de escritura invalida la transaccion, asi que Spring Batch la revierte y vuelve
                // a intentar el chunk en una transaccion nueva, con su propio ciclo de reintento.
                // Si tampoco esa prospera, el Step falla y se ve en el resumen de la corrida.
                log.warn("[{}] chunk revertido tras {} intento(s) en esta transaccion ({}). "
                                + "Se reintenta en una transaccion nueva.",
                        nombrePaso, contexto.getRetryCount(), error.getMessage());
            }
        }
    }
}
