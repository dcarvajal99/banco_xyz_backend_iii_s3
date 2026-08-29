package com.bancoxyz.exception;

/**
 * Se lanza desde un {@code ItemProcessor} cuando un registro legacy no puede corregirse
 * y debe quedar fuera de la migracion.
 *
 * <p>Es la unica excepcion de negocio que la politica de omision acepta saltar: cualquier
 * otro error (conexion, disco, configuracion) detiene el Step para no dar por buena una
 * migracion parcial.</p>
 */
public class DatoInvalidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Contenido crudo de la fila que provoco el rechazo, para poder auditarla. */
    private final String registroCrudo;

    public DatoInvalidoException(String motivo, String registroCrudo) {
        super(motivo);
        this.registroCrudo = registroCrudo;
    }

    public String getRegistroCrudo() {
        return registroCrudo;
    }
}
