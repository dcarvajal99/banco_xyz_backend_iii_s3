package com.bancoxyz.batch.partition;

import com.bancoxyz.common.Constantes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Divide un archivo legacy en rangos contiguos de filas, uno por particion.
 *
 * <p>Es la pieza que convierte un Step en varios: Spring Batch le pide {@code gridSize}
 * particiones y este devuelve un {@link ExecutionContext} por cada una, con la primera y la
 * ultima fila que le tocan. Cada particion se ejecuta despues como un Step independiente que
 * abre su <em>propio</em> lector sobre el mismo archivo y solo recorre su tramo.</p>
 *
 * <h2>Por que por rangos de filas y no por otro criterio</h2>
 * <ul>
 *   <li><b>Por archivo</b> ({@code MultiResourcePartitioner}) no sirve aqui: cada Job de este
 *       proyecto lee <em>un</em> archivo, asi que particionar por archivo daria una sola
 *       particion y no repartiria nada.</li>
 *   <li><b>Por clave de negocio</b> (por ejemplo {@code cuenta_id % gridSize}) tiene la ventaja
 *       de que todas las filas de una cuenta caen en la misma particion, pero exige leer el
 *       archivo entero antes de repartir y produce particiones desiguales: en
 *       {@code intereses.csv} de {@code semana_3} hay 1.000 filas repartidas en solo 50 cuentas,
 *       de modo que unas particiones quedarian con el triple de trabajo que otras.</li>
 *   <li><b>Por rangos de filas</b> reparte exactamente el mismo numero de filas a cada
 *       particion, que es lo que hace que el tiempo total sea el de la particion mas lenta y no
 *       el de la mas cargada. Es tambien lo que describe la guia de la semana.</li>
 * </ul>
 *
 * <h2>El costo de contar</h2>
 * <p>Para repartir hay que saber cuantas filas hay, y para saberlo hay que recorrer el archivo.
 * Es una lectura secuencial sin parseo ni base de datos —del orden de milisegundos para los
 * volumenes de este caso— y se hace una sola vez por Step, no una por particion. A cambio, las
 * particiones quedan parejas. Si el archivo creciera hasta hacer costoso ese conteo, la
 * alternativa seria repartir por tamano en bytes y ajustar los limites a la siguiente linea.</p>
 *
 * <p>El archivo se lee dos veces en total (una para contar y otra para procesar). Se acepta a
 * conciencia: leer un CSV es barato comparado con validar y persistir, que es lo que de verdad
 * se esta paralelizando.</p>
 */
public class ParticionadorPorRangoDeLineas implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(ParticionadorPorRangoDeLineas.class);

    private final Path archivo;
    private final String nombrePaso;
    private final int filasCabecera;

    /**
     * @param archivo archivo legacy que se va a repartir
     * @param nombrePaso nombre del Step, solo para que el log diga a que proceso corresponde
     * @param filasCabecera filas iniciales que no son datos (1 en los tres archivos del caso)
     */
    public ParticionadorPorRangoDeLineas(Path archivo, String nombrePaso, int filasCabecera) {
        this.archivo = archivo;
        this.nombrePaso = nombrePaso;
        this.filasCabecera = filasCabecera;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        long filas = contarFilas();
        // Nunca mas particiones que filas: una particion vacia es un Step completo (con su
        // transaccion, su registro en BATCH_STEP_EXECUTION y su hilo) que no procesa nada.
        int particiones = (int) Math.max(1, Math.min(gridSize, filas));

        Map<String, ExecutionContext> reparto = new LinkedHashMap<>();
        long base = filas / particiones;
        long resto = filas % particiones;
        long inicio = 0;

        for (int i = 0; i < particiones; i++) {
            // El resto se reparte de a una fila entre las primeras particiones, en vez de
            // amontonarlo en la ultima: con 1.000 filas y 3 particiones quedan 334, 333 y 333,
            // y no 333, 333 y 334. La diferencia importa cuando el desbalance es grande.
            long tamano = base + (i < resto ? 1 : 0);
            long fin = inicio + tamano;

            String nombre = "particion" + i;
            ExecutionContext contexto = new ExecutionContext();
            contexto.putLong(Constantes.PARTICION_INICIO, inicio);
            contexto.putLong(Constantes.PARTICION_FIN, fin);
            contexto.putString(Constantes.PARTICION_NOMBRE, nombre);
            contexto.putInt(Constantes.PARTICION_INDICE, i);
            reparto.put(nombre, contexto);

            inicio = fin;
        }

        log.info("[{}] particionado de {} en {} particion(es) de {} filas: {}",
                nombrePaso, archivo.getFileName(), particiones,
                filas == 0 ? 0 : base + (resto > 0 ? "-" + (base + 1) : ""), rangosLegibles(reparto));
        return reparto;
    }

    /** Cuenta las filas de datos del archivo, sin la cabecera. */
    private long contarFilas() {
        try (Stream<String> lineas = Files.lines(archivo, StandardCharsets.UTF_8)) {
            return Math.max(0, lineas.count() - filasCabecera);
        } catch (IOException e) {
            // Si el archivo no se puede leer, el Step va a fallar igual al abrirlo. Fallar aqui
            // con el motivo claro es mejor que devolver cero particiones y que el Job termine
            // en COMPLETED sin haber procesado nada.
            throw new UncheckedIOException(
                    "No se pudo leer el archivo para repartirlo en particiones: " + archivo, e);
        }
    }

    /** Ejemplo: {@code particion0=[0,334) particion1=[334,667) particion2=[667,1000)}. */
    private static String rangosLegibles(Map<String, ExecutionContext> reparto) {
        StringBuilder sb = new StringBuilder();
        reparto.forEach((nombre, ctx) -> sb.append(nombre)
                .append("=[").append(ctx.getLong(Constantes.PARTICION_INICIO))
                .append(",").append(ctx.getLong(Constantes.PARTICION_FIN)).append(") "));
        return sb.toString().trim();
    }
}
