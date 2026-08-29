package com.bancoxyz.batch.reader;

import com.bancoxyz.dto.FilaLegacy;
import com.bancoxyz.dto.InteresCsv;
import com.bancoxyz.dto.MovimientoAnualCsv;
import com.bancoxyz.dto.TransaccionCsv;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.item.support.builder.SynchronizedItemStreamReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;

/**
 * Fabrica de los {@code ItemReader} de los tres archivos legacy.
 *
 * <p>Los tres lectores comparten la misma receta: saltar la cabecera, tokenizar por coma,
 * mapear a un DTO de solo texto y anotar el numero de linea de origen. La tolerancia al
 * dato sucio no vive aqui sino en el {@code ItemProcessor}; el lector solo debe conseguir
 * que la fila entre al pipeline.</p>
 *
 * <h2>Como cambia el lector con cada estrategia de escalado</h2>
 * <p>La semana 2 puso tres hilos a leer el <em>mismo</em> lector, y eso obligo a dos
 * concesiones: envolverlo en {@link SynchronizedItemStreamReader}, porque
 * {@code FlatFileItemReader} no es thread-safe y tres hilos pueden partir una linea por la
 * mitad; y apagar {@code saveState}, porque la posicion que guardaban los tres hilos dejaba de
 * representar un prefijo continuo del archivo.</p>
 *
 * <p>El particionado de la semana 3 <b>deshace las dos concesiones</b>. Cada particion es un
 * Step independiente con su propio lector, que recorre su tramo con un solo hilo: no hay nada
 * que sincronizar y la posicion vuelve a significar exactamente lo que dice. De ahi que el
 * lector se construya con un rango y con {@code guardarEstado} activable: al reanudar, cada
 * particion retoma <em>desde donde quedo</em> en vez de rehacer el archivo entero.</p>
 */
public final class LectoresCsv {

    private LectoresCsv() {
        // Clase de utilidad: no se instancia.
    }

    /**
     * Tramo del archivo que le toca leer a un lector.
     *
     * @param inicio primera fila de datos, base 0 y sin contar la cabecera
     * @param fin fila siguiente a la ultima (limite superior exclusivo)
     * @param guardarEstado si el lector puede persistir su posicion para reanudar. Solo tiene
     *        sentido cuando un unico hilo recorre este lector, que es el caso de una particion
     *        y el de una corrida secuencial, pero no el de un Step multihilo.
     */
    public record Rango(long inicio, long fin) {

        /** El archivo completo: lo que lee un Step secuencial o uno multihilo. */
        public static Rango archivoCompleto() {
            return new Rango(0, Integer.MAX_VALUE);
        }

        /**
         * Cuantas filas le tocan a esta particion. {@code maxItemCount} es relativo, no absoluto.
         *
         * <p>Nunca devuelve cero: {@code FlatFileItemReader} rechaza {@code maxItemCount(0)} con
         * {@code IllegalArgumentException: count must be greater than zero}. El caso aparece con
         * un archivo vacio, donde el particionador crea una unica particion de cero filas. Pedir
         * una fila no lee ninguna —la cabecera ya se salto y detras no hay nada— y evita que un
         * archivo truncado haga fallar el Step por una excepcion de configuracion en vez de
         * llegar al decisor de calidad, que es quien debe diagnosticarlo.</p>
         */
        public int cantidad() {
            long filas = Math.max(0, fin - inicio);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1, filas));
        }
    }

    /**
     * Arma el rango a partir de lo que traiga el contexto de la particion.
     *
     * <p>Cuando la estrategia no es particionada, esas claves no existen y llegan como
     * {@code null}: el lector recorre entonces el archivo completo. Asi el mismo bean de lector
     * sirve para las tres estrategias sin condicionales repartidos por las configuraciones.</p>
     *
     * @param inicio valor de {@code banco.particion.inicio}, o {@code null} si no hay particion
     * @param fin valor de {@code banco.particion.fin}, o {@code null} si no hay particion
     */
    public static Rango rangoDeParticion(Long inicio, Long fin) {
        return inicio == null || fin == null ? Rango.archivoCompleto() : new Rango(inicio, fin);
    }

    /** Lector de {@code transacciones.csv} (id, fecha, monto, tipo). */
    public static FlatFileItemReader<TransaccionCsv> deTransacciones(Path archivo, Rango rango) {
        return construir("lectorTransacciones", archivo, TransaccionCsv.class,
                new String[]{"id", "fecha", "monto", "tipo"}, rango);
    }

    /** Lector de {@code intereses.csv} (cuenta_id, nombre, saldo, edad, tipo). */
    public static FlatFileItemReader<InteresCsv> deIntereses(Path archivo, Rango rango) {
        return construir("lectorIntereses", archivo, InteresCsv.class,
                new String[]{"cuentaId", "nombre", "saldo", "edad", "tipo"}, rango);
    }

    /** Lector de {@code cuentas_anuales.csv} (cuenta_id, fecha, transaccion, monto, descripcion). */
    public static FlatFileItemReader<MovimientoAnualCsv> deMovimientosAnuales(Path archivo, Rango rango) {
        return construir("lectorMovimientosAnuales", archivo, MovimientoAnualCsv.class,
                new String[]{"cuentaId", "fecha", "transaccion", "monto", "descripcion"}, rango);
    }

    /**
     * Envuelve un lector en un {@link SynchronizedItemStreamReader} para que pueda usarse
     * desde varios hilos.
     *
     * <p>Sincroniza el {@code read()} completo (contador, lectura de la linea y mapeo), que
     * es justo lo minimo que hay que serializar. El {@code process()} y el {@code write()}
     * siguen corriendo en paralelo, que es de donde sale la ganancia del escalado: leer un
     * CSV es barato, validar y persistir es lo caro.</p>
     */
    public static <T> SynchronizedItemStreamReader<T> sincronizado(ItemStreamReader<T> lector) {
        return new SynchronizedItemStreamReaderBuilder<T>()
                .delegate(lector)
                .build();
    }

    /** Filas iniciales que no son datos: los tres archivos legacy traen una cabecera. */
    private static final int FILAS_CABECERA = 1;

    private static <T extends FilaLegacy> FlatFileItemReader<T> construir(String nombre, Path archivo,
                                                                         Class<T> tipo, String[] campos,
                                                                         Rango rango) {
        int filasCabecera = FILAS_CABECERA;
        return new FlatFileItemReaderBuilder<T>()
                .name(nombre)
                // El tramo de esta particion se acota saltando lineas al abrir y limitando
                // cuantas se leen: con [334, 667) se saltan la cabecera mas 334 filas y se leen
                // 333. El numero de linea que anota el LineMapper sigue siendo el del archivo
                // original, porque el lector cuenta tambien las lineas que salta; de eso
                // dependen la deduplicacion y la bitacora de auditoria.
                //
                // El camino "natural" —currentItemCount(inicio) + maxItemCount(fin)— NO sirve
                // aqui, y el motivo es facil de pasar por alto: quien aplica currentItemCount es
                // AbstractItemCountingItemStreamItemReader.open(), y esa implementacion empieza
                // con "if (!isSaveState()) return;". Con saveState apagado, el salto nunca
                // ocurre: todas las particiones arrancan en la primera fila y leen las mismas
                // filas. No falla ni avisa; simplemente migra el mismo tramo N veces y deja el
                // resto del archivo sin procesar.
                .linesToSkip(filasCabecera + (int) Math.min(Integer.MAX_VALUE, rango.inicio()))
                .maxItemCount(rango.cantidad())
                // Guardar la posicion solo es seguro si un unico hilo recorre este lector.
                //
                // En un Step MULTIHILO no lo es: los tres hilos escriben en el mismo
                // ExecutionContext la posicion del lector compartido al cerrar su chunk, y ese
                // numero deja de representar un prefijo continuo del archivo. Si el hilo C ya
                // commiteo la fila 15 y el hilo A falla con las filas 1-5 sin escribir, queda
                // persistido "leidas 15" y al reanudar el Job saltaria a la fila 16: esas filas
                // no se migrarian nunca y el Job terminaria en COMPLETED. Por eso la semana 2
                // tuvo que apagarlo.
                //
                // Con PARTICIONES vuelve a ser tecnicamente seguro —cada particion tiene su
                // lector y un solo hilo— pero se mantiene apagado por el modelo de reanudacion
                // que se eligio: la particion se rehace completa sobre una base limpiada. Ver
                // el Javadoc de la clase.
                .saveState(false)
                .resource(new FileSystemResource(archivo))
                .strict(true)
                .encoding("UTF-8")
                .lineMapper(mapeadorConNumeroDeLinea(tipo, campos))
                .build();
    }

    /**
     * Envuelve el {@code DefaultLineMapper} estandar para dejar registrado en cada DTO la
     * linea del archivo de la que salio. Es la unica forma de que un procesador con estado
     * distinga una fila repetida de una fila reprocesada tras un rollback.
     */
    private static <T extends FilaLegacy> LineMapper<T> mapeadorConNumeroDeLinea(Class<T> tipo, String[] campos) {
        DelimitedLineTokenizer tokenizador = new DelimitedLineTokenizer();
        tokenizador.setDelimiter(",");
        tokenizador.setNames(campos);

        BeanWrapperFieldSetMapper<T> mapeadorDeCampos = new BeanWrapperFieldSetMapper<>();
        mapeadorDeCampos.setTargetType(tipo);

        DefaultLineMapper<T> base = new DefaultLineMapper<>();
        base.setLineTokenizer(tokenizador);
        base.setFieldSetMapper(mapeadorDeCampos);

        return (linea, numeroLinea) -> {
            T fila = base.mapLine(linea, numeroLinea);
            fila.setNumeroLinea(numeroLinea);
            return fila;
        };
    }
}
