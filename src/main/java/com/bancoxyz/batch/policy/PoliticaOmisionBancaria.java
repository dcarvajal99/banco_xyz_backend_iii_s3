package com.bancoxyz.batch.policy;

import com.bancoxyz.exception.DatoInvalidoException;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.file.FlatFileParseException;

/**
 * Decide que errores puede tolerar la migracion y cuales deben detenerla.
 *
 * <p>La regla es deliberadamente estrecha: solo se omiten los problemas <em>del dato</em>
 * (una fila mal formada en el CSV o un valor que no pasa las validaciones de negocio).
 * Un fallo de base de datos, de disco o de configuracion no se omite nunca, porque
 * seguir adelante produciria una migracion incompleta que parece exitosa.</p>
 *
 * <p>Ademas hay un tope: si se supera {@code limiteOmisiones}, el archivo de origen esta
 * tan degradado que la corrida completa debe fallar y revisarse a mano.</p>
 */
public class PoliticaOmisionBancaria implements SkipPolicy {

    private final int limiteOmisiones;

    public PoliticaOmisionBancaria(int limiteOmisiones) {
        this.limiteOmisiones = limiteOmisiones;
    }

    @Override
    public boolean shouldSkip(Throwable excepcion, long omisiones) throws SkipLimitExceededException {
        boolean esProblemaDelDato = excepcion instanceof DatoInvalidoException
                || excepcion instanceof FlatFileParseException
                || excepcion.getCause() instanceof DatoInvalidoException;

        if (!esProblemaDelDato) {
            return false;
        }
        if (omisiones >= limiteOmisiones) {
            throw new SkipLimitExceededException(limiteOmisiones, excepcion);
        }
        return true;
    }

    public int getLimiteOmisiones() {
        return limiteOmisiones;
    }
}
