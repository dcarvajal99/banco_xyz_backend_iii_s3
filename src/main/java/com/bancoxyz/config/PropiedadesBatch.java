package com.bancoxyz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Parametros de negocio y de rendimiento de la migracion, externalizados en
 * {@code application.properties} bajo el prefijo {@code banco.batch}.
 *
 * <p>Las tasas de interes y los umbrales de anomalia no deben vivir dentro de los
 * {@code ItemProcessor}: el area de riesgo del banco las ajusta sin recompilar.</p>
 *
 * <p>Lo mismo vale, y con mas razon, para los parametros de escalado. Ajustar el tamano
 * del chunk o la cantidad de hilos es una operacion de <em>tuning</em>: se prueba, se mide
 * en el log y se vuelve a probar. Si estuvieran quemados en el codigo, cada medicion
 * exigiria recompilar y volver a desplegar. Todos se pueden sobrescribir por linea de
 * comandos, por ejemplo {@code --banco.batch.tamano-chunk=50 --banco.batch.hilos-por-paso=1}.</p>
 */
@Component
@ConfigurationProperties(prefix = "banco.batch")
public class PropiedadesBatch {

    /* ------------------------------------------------- Escalado y paralelismo */

    /**
     * Cantidad de items por transaccion (commit interval) en los Steps orientados a chunk.
     * La actividad de la semana 2 fija este valor en 5.
     */
    private int tamanoChunk = 5;

    /**
     * Como se reparte el trabajo de un Step de migracion. La semana 3 usa
     * {@link EstrategiaDeEscalado#PARTICIONADO}; las otras dos se conservan para poder medir
     * las tres con el mismo jar y sostener con datos cual es la configuracion optima.
     */
    private EstrategiaDeEscalado estrategia = EstrategiaDeEscalado.PARTICIONADO;

    /**
     * Cantidad de particiones en que se divide cada archivo ({@code gridSize}).
     *
     * <p>Ocho no es un numero elegido al azar: es el que gano la medicion de la semana sobre
     * 10.000 filas y diez repeticiones por configuracion (ver
     * {@code evidencias/logs/09_medicion_estrategias.log}). Baja la migracion de 2.734 ms
     * —la mejor corrida secuencial de todo el barrido— a 1.094 ms, un 60 % menos.</p>
     *
     * <p>La curva se aplana entre 8 y 12 y <b>empeora</b> con 20: la maquina tiene 10 nucleos y
     * PostgreSQL corre en ella, de modo que pasado ese punto las particiones no encuentran
     * nucleo libre y solo agregan costo —una StepExecution, una transaccion y un lector por
     * cada una— sin nada que ganar. De ahi que el valor por defecto sea 8 y no "cuantas mas
     * mejor".</p>
     */
    private int particiones = 8;

    /**
     * Hilos que ejecutan las particiones en paralelo. Conviene que sea igual o mayor que
     * {@link #particiones}: si fuera menor, algunas particiones esperarian turno y el
     * particionado se volveria parcialmente secuencial sin que se note en la configuracion.
     */
    private int hilosDeParticiones = 8;

    /**
     * Hilos que procesan chunks del mismo Step en paralelo cuando la estrategia es
     * {@link EstrategiaDeEscalado#MULTIHILO}. La semana 2 fijo este valor en 3.
     */
    private int hilosPorPaso = 3;

    /** Tareas (chunks) que pueden quedar en espera cuando los hilos estan ocupados. */
    private int capacidadCola = 25;

    /** Hilos del {@code split} que corre los tres flujos del cierre nocturno a la vez. */
    private int hilosDeFlujos = 3;

    /* --------------------------------------------------- Tolerancia a fallos */

    /** Maximo de filas que se pueden omitir antes de dar la migracion por fallida. */
    private int limiteOmisiones = 1000;

    /** Reintentos ante un error transitorio de base de datos antes de propagar el fallo. */
    private int limiteReintentos = 3;

    /** Espera del primer reintento, en milisegundos. */
    private long backoffInicialMs = 200;

    /** Factor por el que se multiplica la espera en cada reintento sucesivo. */
    private double backoffMultiplicador = 2.0;

    /** Tope de la espera entre reintentos, para no dejar la ventana nocturna en pausa. */
    private long backoffMaximoMs = 2000;

    /**
     * Veces que un mismo Step puede arrancar dentro de la misma JobInstance. Si un paso
     * falla tres noches seguidas el problema no es transitorio y hay que intervenir: Spring
     * Batch corta con {@code StartLimitExceededException} en vez de dejar al operador
     * reintentando indefinidamente.
     */
    private int limiteReejecuciones = 3;

    /* ---------------------------------------- Politica de finalizacion (decisor) */

    /**
     * Proporcion de filas omitidas a partir de la cual la corrida se considera degradada:
     * se migra igual, pero se levanta un aviso de cuarentena para el area de datos.
     */
    private double umbralAlertaOmision = 0.10;

    /**
     * Proporcion de filas omitidas a partir de la cual el archivo de origen se considera
     * inservible: el reporte no se publica y la corrida se deriva a revision manual.
     */
    private double umbralRechazoOmision = 0.30;

    /**
     * Filas minimas que se espera leer de cada archivo legacy para dar la corrida por valida.
     *
     * <p>Cubre un agujero que la tasa de omision no puede ver: un archivo <em>truncado</em>, del
     * que solo llego la cabecera. No tiene ninguna fila omitida, asi que su tasa es 0 % y seria
     * la mejor posible; sin este control, la migracion perderia el 100 % de los datos del dia y
     * lo informaria como calidad aceptable. Poner {@code 0} desactiva la comprobacion, para el
     * caso legitimo de un archivo que puede venir vacio.</p>
     */
    private long minimoFilasEsperadas = 1;

    /* ------------------------------------------------------ Reglas de negocio */

    /** Monto sobre el cual una transaccion se marca para revision del area de riesgo. */
    private BigDecimal umbralMontoAtipico = new BigDecimal("2500");

    /** Tasa mensual de las cuentas de ahorro. */
    private BigDecimal tasaAhorro = new BigDecimal("0.00500");

    /** Tasa mensual de los prestamos. */
    private BigDecimal tasaPrestamo = new BigDecimal("0.01500");

    /** Tasa mensual de los creditos hipotecarios. */
    private BigDecimal tasaHipoteca = new BigDecimal("0.00900");

    /** Puntos de tasa adicionales para cuentas de ahorro de titulares de tercera edad. */
    private BigDecimal bonificacionTerceraEdad = new BigDecimal("0.00100");

    /**
     * Activa una falla transitoria simulada en el primer chunk para evidenciar el
     * funcionamiento del RetryPolicy. Solo se enciende en las corridas de demostracion.
     */
    private boolean simularFalloTransitorio = false;

    /* ------------------------------------------------------------- Accesores */

    public int getTamanoChunk() {
        return tamanoChunk;
    }

    public void setTamanoChunk(int tamanoChunk) {
        this.tamanoChunk = tamanoChunk;
    }

    public EstrategiaDeEscalado getEstrategia() {
        return estrategia;
    }

    public void setEstrategia(EstrategiaDeEscalado estrategia) {
        this.estrategia = estrategia;
    }

    public int getParticiones() {
        return particiones;
    }

    public void setParticiones(int particiones) {
        this.particiones = particiones;
    }

    public int getHilosDeParticiones() {
        return hilosDeParticiones;
    }

    public void setHilosDeParticiones(int hilosDeParticiones) {
        this.hilosDeParticiones = hilosDeParticiones;
    }

    public int getHilosPorPaso() {
        return hilosPorPaso;
    }

    public void setHilosPorPaso(int hilosPorPaso) {
        this.hilosPorPaso = hilosPorPaso;
    }

    public int getCapacidadCola() {
        return capacidadCola;
    }

    public void setCapacidadCola(int capacidadCola) {
        this.capacidadCola = capacidadCola;
    }

    public int getHilosDeFlujos() {
        return hilosDeFlujos;
    }

    public void setHilosDeFlujos(int hilosDeFlujos) {
        this.hilosDeFlujos = hilosDeFlujos;
    }

    public int getLimiteOmisiones() {
        return limiteOmisiones;
    }

    public void setLimiteOmisiones(int limiteOmisiones) {
        this.limiteOmisiones = limiteOmisiones;
    }

    public int getLimiteReintentos() {
        return limiteReintentos;
    }

    public void setLimiteReintentos(int limiteReintentos) {
        this.limiteReintentos = limiteReintentos;
    }

    public long getBackoffInicialMs() {
        return backoffInicialMs;
    }

    public void setBackoffInicialMs(long backoffInicialMs) {
        this.backoffInicialMs = backoffInicialMs;
    }

    public double getBackoffMultiplicador() {
        return backoffMultiplicador;
    }

    public void setBackoffMultiplicador(double backoffMultiplicador) {
        this.backoffMultiplicador = backoffMultiplicador;
    }

    public long getBackoffMaximoMs() {
        return backoffMaximoMs;
    }

    public void setBackoffMaximoMs(long backoffMaximoMs) {
        this.backoffMaximoMs = backoffMaximoMs;
    }

    public int getLimiteReejecuciones() {
        return limiteReejecuciones;
    }

    public void setLimiteReejecuciones(int limiteReejecuciones) {
        this.limiteReejecuciones = limiteReejecuciones;
    }

    public double getUmbralAlertaOmision() {
        return umbralAlertaOmision;
    }

    public void setUmbralAlertaOmision(double umbralAlertaOmision) {
        this.umbralAlertaOmision = umbralAlertaOmision;
    }

    public double getUmbralRechazoOmision() {
        return umbralRechazoOmision;
    }

    public void setUmbralRechazoOmision(double umbralRechazoOmision) {
        this.umbralRechazoOmision = umbralRechazoOmision;
    }

    public long getMinimoFilasEsperadas() {
        return minimoFilasEsperadas;
    }

    public void setMinimoFilasEsperadas(long minimoFilasEsperadas) {
        this.minimoFilasEsperadas = minimoFilasEsperadas;
    }

    public BigDecimal getUmbralMontoAtipico() {
        return umbralMontoAtipico;
    }

    public void setUmbralMontoAtipico(BigDecimal umbralMontoAtipico) {
        this.umbralMontoAtipico = umbralMontoAtipico;
    }

    public BigDecimal getTasaAhorro() {
        return tasaAhorro;
    }

    public void setTasaAhorro(BigDecimal tasaAhorro) {
        this.tasaAhorro = tasaAhorro;
    }

    public BigDecimal getTasaPrestamo() {
        return tasaPrestamo;
    }

    public void setTasaPrestamo(BigDecimal tasaPrestamo) {
        this.tasaPrestamo = tasaPrestamo;
    }

    public BigDecimal getTasaHipoteca() {
        return tasaHipoteca;
    }

    public void setTasaHipoteca(BigDecimal tasaHipoteca) {
        this.tasaHipoteca = tasaHipoteca;
    }

    public BigDecimal getBonificacionTerceraEdad() {
        return bonificacionTerceraEdad;
    }

    public void setBonificacionTerceraEdad(BigDecimal bonificacionTerceraEdad) {
        this.bonificacionTerceraEdad = bonificacionTerceraEdad;
    }

    public boolean isSimularFalloTransitorio() {
        return simularFalloTransitorio;
    }

    public void setSimularFalloTransitorio(boolean simularFalloTransitorio) {
        this.simularFalloTransitorio = simularFalloTransitorio;
    }
}
