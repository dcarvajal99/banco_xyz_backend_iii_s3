# Banco XYZ — Escalado por particiones con Spring Batch

Tercera etapa de la migración de los procesos por lotes del sistema legacy del **Banco XYZ**.
La semana 1 reescribió los tres procesos en **Spring Batch 5**; la semana 2 los puso a correr con
varios hilos; esta semana los **particiona**: el archivo se divide en tramos y cada tramo se
procesa como un Step independiente.

> Actividad sumativa — *Optimizando procesos batch para mejorar la resiliencia de procesos*
> Experiencia 1, Semana 3 · **Desarrollo Backend III (PBY2203)** · Duoc UC
> Datos de origen: <https://github.com/KariVillagran/bank_legacy_data>

---

## 1. La decisión de la semana, y por qué

La consigna deja elegir: *«decidir entre implementar un match optimizado para multi-threads o
utilizar particiones»*. Se eligió **particionar**, y la elección está respaldada por una medición,
no por preferencia. Sobre el mismo archivo, con el mismo jar y cambiando solo un parámetro:

| Estrategia | Mediana | Contra la mejor secuencial |
|---|---:|---:|
| Secuencial (mejor de 4 configuraciones) | 2.734 ms | — |
| Multihilo, 3 hilos (lo de la semana 2) | 2.554 ms | **+6,6 %** (1,07×) |
| **Particionado, 8 particiones** | **1.094 ms** | **+60,0 %** (2,50×) |

El multihilo apenas mueve la aguja porque los tres hilos **comparten un único lector**, y ese
lector está sincronizado: la lectura se serializa y solo el procesamiento y la escritura corren
en paralelo. Con particiones cada tramo tiene *su propio* lector, así que también la lectura se
reparte, y desaparece la contención sobre el candado que con tres hilos era un punto caliente en
el camino crítico.

El detalle completo de la medición —12 configuraciones, 10 repeticiones cada una, todas las
muestras publicadas— está en `evidencias/logs/09_medicion_estrategias.log` y se resume en §5.

---

## 2. Qué cambia respecto de la semana 2

| | Semana 2 | Semana 3 |
|---|---|---|
| **Escalado del Step** | 3 hilos sobre un lector compartido | **N particiones, cada una un Step con su lector** |
| **Estrategia** | fija en el código | **propiedad** `banco.batch.estrategia` con tres valores |
| **Lector** | uno solo, sincronizado | **uno por partición**, acotado a su rango de filas |
| **Deduplicación** | campos del `ItemProcessor` `@StepScope` | **registro compartido** por toda la corrida |
| **Métricas** | campos del listener | **por `StepExecution`**, porque el Step corre N veces a la vez |
| **Medición** | chunk × hilos, contra la secuencial del mismo chunk | 3 estrategias × parámetros, contra **la mejor** secuencial |
| **Pruebas** | 135 | **140** (95,5 % de cobertura) |

---

## 3. Arquitectura del particionado

```
                          ┌──────────────────┐
   línea de comandos ────►│   JobLauncher    │──► crea la JobExecution
   (LanzadorDeJobs)       └────────┬─────────┘
                                   │
                          ┌────────▼─────────┐        ┌──────────────────┐
                          │       Job        │◄──────►│  JobRepository   │
                          └────────┬─────────┘        │  (tablas BATCH_*)│
                                   │                  └──────────────────┘
                    ┌──────────────▼───────────────┐
                    │  Step GESTOR (PartitionStep) │  procesarTransaccionesStep
                    └──────────────┬───────────────┘
                                   │
                    ParticionadorPorRangoDeLineas
                    cuenta las filas y devuelve un
                    ExecutionContext por partición
                                   │
        ┌──────────┬───────────────┼───────────────┬──────────┐
        ▼          ▼               ▼               ▼          ▼
   partición0  partición1     partición2      partición3   …hasta N
   [0,1250)    [1250,2500)    [2500,3750)     [3750,5000)
        │          │               │               │
   cada una es un Step trabajador COMPLETO, con su propio
   lector, su chunk, su transacción y sus políticas de
   omisión y reintento — y con un solo hilo dentro
        │          │               │               │
        └──────────┴───────┬───────┴───────────────┘
                           ▼
              el gestor agrega los contadores
              (DefaultStepExecutionAggregator)
```

En los metadatos de Spring Batch esto se ve tal cual: el Step gestor conserva el nombre de
siempre (`procesarTransaccionesStep`) y aparece además una fila por partición, nombrada
`procesarTransaccionesStepWorker:particionN`. Los contadores del gestor son la **suma** de los de
sus particiones.

### 3.1 Por qué se reparte por rangos de filas

| Criterio | Por qué se descartó / eligió |
|---|---|
| Por archivo (`MultiResourcePartitioner`) | **No aplica**: cada Job de este proyecto lee *un* archivo, así que daría una sola partición |
| Por clave de negocio (`cuenta_id % N`) | Agrupa las filas de una cuenta, pero exige leer todo antes de repartir y produce particiones desiguales: en `intereses.csv` hay 1.000 filas en solo 50 cuentas |
| **Por rangos de filas** | Reparte exactamente el mismo número de filas a cada partición. El tiempo total de un Step particionado es el de su partición **más lenta**, así que lo que importa es que estén parejas |

El precio es contar las filas antes de repartir, es decir leer el archivo dos veces. Se acepta a
conciencia: recorrer un CSV sin parsear ni tocar la base es del orden de milisegundos, y es lo
que compra que las particiones queden parejas. En las corridas reales el **desbalance** medido
—cuánto tardó la más lenta respecto del promedio— fue de **1,00×–1,01×**.

### 3.2 Dónde van las políticas

La omisión, el reintento y el backoff se configuran en el Step **trabajador**, no en el gestor.
El gestor no lee ni escribe: solo reparte y espera. Ponerle a él la política de omisión no
tendría efecto —nunca ve un item— y dejaría a las particiones sin tolerancia a fallos, de modo
que la primera fila sucia mataría su partición entera.

`startLimit`, en cambio, va en el **gestor**: el control lo hace `SimpleStepHandler` al recorrer
el flujo del Job, y las particiones no pasan por ahí —las lanza directamente el
`PartitionHandler`—, así que en el trabajador no tendría ningún efecto.

---

## 4. Las cuatro trampas del particionado

Ninguna de las cuatro da error de compilación. Tres de ellas fallan **en silencio**, que es
justamente lo que esta migración existe para eliminar.

**1. `@JobScope` no funciona dentro de una partición.** Lo natural para compartir estado entre
particiones sería un bean `@JobScope`: una instancia por corrida. No funciona. El ámbito `job` se
apoya en un contexto ligado al hilo, y `TaskExecutorPartitionHandler` lanza cada partición en un
hilo del pool al que ese contexto nunca se propaga. El primer item revienta con
`ScopeNotActiveException: Scope 'job' is not active for the current thread`. Lo mismo ocurre en
los hilos del `split` del cierre nocturno completo.
→ `RegistroDeDuplicados` es un **singleton que particiona su estado por `jobExecutionId`** y
libera cada corrida en `afterJob`. Los particionadores son `@StepScope`, que sí está activo en
cualquier hilo que ejecute el Step.

**2. `currentItemCount` no sirve para acotar el rango si `saveState` está apagado.** El camino
"natural" para que cada partición lea su tramo sería `currentItemCount(inicio)` +
`maxItemCount(fin)`. Quien aplica `currentItemCount` es `AbstractItemCountingItemStreamItemReader.open()`,
y esa implementación **empieza con `if (!isSaveState()) return;`** (verificado en el bytecode).
Con `saveState(false)` el salto nunca ocurre: todas las particiones arrancan en la primera fila,
migran el mismo tramo N veces y dejan el resto del archivo sin procesar. No falla ni avisa.
→ El rango se acota con `linesToSkip(1 + inicio)` y `maxItemCount(fin - inicio)`, que se aplican
siempre. Además `linesToSkip` **preserva el número de línea global**, porque el lector cuenta
también las líneas que salta: de ese número dependen la deduplicación y la bitácora de auditoría.

**3. El estado del `ItemProcessor` deja de ser por corrida.** `@StepScope` da una instancia por
`StepExecution`, y cada partición **es** una `StepExecution`. Los detectores de duplicados, que
antes eran campos del procesador, pasarían a existir uno por partición: un duplicado repartido
entre dos particiones —la fila 1 y la fila 200— no se detectaría, y en `intereses.csv` eso
significa liquidar dos veces la misma cuenta.
→ Estado compartido en `RegistroDeDuplicados`, con una prueba
(`EscalamientoParaleloIT.deduplicaEntreParticiones`) que coloca las copias en extremos opuestos
del archivo a propósito.

**4. El listener del Step es un objeto compartido entre las N particiones.** El Step trabajador es
*un* objeto que se ejecuta N veces **a la vez**. Un listener con contadores en campos de
instancia, reiniciados en `beforeStep`, se pisa a sí mismo: el `beforeStep` de una partición borra
lo que llevaba otra.
→ `MedidorDeRendimientoListener` guarda su estado en un mapa indexado por
`stepExecution.getId()`, y hay una prueba que simula dos particiones solapadas.

---

## 5. La medición

`estadosCuentaAnualesJob` sobre `data/volumen_x10` (10.000 filas, 480 sucias = 4,8 %), 10
repeticiones por configuración, las 12 configuraciones **intercaladas** entre repeticiones.

### 5.1 Por qué 10.000 filas y no las 1.000 del repositorio

Con 1.000 filas la corrida dura ~0,6 s y la dispersión entre muestras de una **misma**
configuración llega a 2,5×. A esa escala, diferencias del 20 % son ruido, y ninguna cantidad de
repeticiones lo arregla: hay que subir la señal. `scripts/generar_volumen.py` replica
`data/semana_3` diez veces desplazando el identificador de cada bloque, de modo que conserva
exactamente la mezcla de defectos —4,8 % de omisiones, verificado— sin inventar duplicados entre
bloques que falsearían el contador de filtrados.

### 5.2 El cronómetro

Lo mide `MedidorDeRendimientoListener` entre `beforeStep` y `afterStep`, **no** `time java -jar`.
El arranque de la JVM y del contexto de Spring son ~2 s: sobre corridas de 1–3 s, medir con el
reloj de pared sería medir el arranque.

### 5.3 La línea base honesta

La medición de la semana 2 concluyó *«con chunk 5 el paralelismo gana 31,6 %»*. Es cierto como
comparación **dentro del mismo chunk**, pero engañoso como conclusión de rendimiento: esa
ganancia se midió contra una referencia deliberadamente mala (chunk 5 secuencial, 205 commits).
Con los mismos datos, la mejor configuración paralela de la semana 2 era en realidad un 7 % *más
lenta* que la mejor secuencial.

**Regla de esta semana: toda ganancia se calcula contra la mejor configuración secuencial de todo
el barrido.**

### 5.4 Resultados

| Configuración | Mediana | Ganancia |
|---|---:|---:|
| Particionado, chunk 100, **8 particiones** | **1.094 ms** | **+60,0 %** |
| Particionado, chunk 100, 6 particiones | 1.238 ms | +54,7 % |
| Particionado, chunk 100, 12 particiones | 1.286 ms | +53,0 % |
| Particionado, chunk 100, 4 particiones | 1.370 ms | +49,9 % |
| Particionado, chunk 100, **20 particiones** | 1.454 ms | +46,8 % ← *empeora* |
| Particionado, chunk 100, 2 particiones | 1.770 ms | +35,3 % |
| Multihilo, chunk 100, 3 hilos | 2.554 ms | +6,6 % |
| Secuencial, chunk 500 *(mejor secuencial)* | 2.734 ms | — |
| Particionado, **chunk 5**, 4 particiones | 2.922 ms | −6,9 % ← *peor que secuencial* |

Cuatro lecturas:

1. **El particionado gana, y no por poco**: 2,50× contra 1,07× del multihilo.
2. **Hay un límite y se ve**: la curva mejora hasta 8, se aplana entre 8 y 12 y **empeora con 20**.
   La máquina tiene 10 núcleos y PostgreSQL corre en ella, así que pasado ese punto las
   particiones no encuentran núcleo libre y solo agregan costo —una `StepExecution`, una
   transacción y un lector por cada una—. De ahí que el valor por defecto sea **8** y no
   «cuantas más mejor».
3. **El chunk y el número de particiones son un solo parámetro**: particionar con chunk 5 queda
   *por debajo* de la mejor secuencial. Elegir uno sin mirar el otro lleva a conclusiones falsas.
4. **Las tres estrategias migran exactamente lo mismo**: en las 120 corridas del barrido, los tres
   caminos dieron siempre 10.000 leídas, 9.520 escritas y 480 omitidas. El escalado cambia cuánto
   tarda, no qué migra; si cambiara el resultado no sería una optimización sino un error.

> **Sobre la incertidumbre.** Las muestras se publican completas porque la dispersión sigue siendo
> alta: el contenedor de PostgreSQL comparte los núcleos con la JVM. Por eso se usa la mediana y
> no el promedio, y por eso las diferencias dentro del rango 8–12 particiones se leen como
> «equivalentes» y no como un ganador claro. Una medición de capacidad real necesitaría la base en
> otra máquina.

---

## 6. Tolerancia a fallos y re-ejecución

| Mecanismo | Implementación | Efecto |
|---|---|---|
| `SkipPolicy` | `PoliticaOmisionBancaria` | Omite solo el dato sucio, con tope. **Por partición**: una partición degradada no arrastra a las demás |
| `RetryPolicy` | `PoliticaReintentoBancaria` (`SimpleRetryPolicy` + `ExponentialBackOffPolicy`) | Reintenta 5 familias de fallo transitorio con espera creciente (200 ms → 400 → 800) |
| `JobExecutionDecider` | `DecisorCalidadDeDatos` | Decide si el reporte se publica, se publica con aviso, o la corrida se corta |
| Cobertura mínima | `banco.batch.minimo-filas-esperadas` | Detecta el archivo **truncado**, que no tiene omisiones y por tanto daría la mejor tasa posible |
| Re-ejecución | `LimpiezaDeReintentoTasklet` + `allowStartIfComplete(true)` + splitter con el mismo flag | Relanzar la misma corrida deja siempre el mismo resultado que una corrida limpia |
| Listeners | Skip, Retry, Chunk, StepExecution y JobExecution | Cada omisión queda con archivo, línea, motivo, contenido **y la partición que la descartó** |

**El pacto de la reanudación.** O se guarda la posición del lector y se retoma, o se rehace el
paso sobre una base limpiada — pero **nunca las dos**. Este proyecto eligió lo segundo:
`saveState(false)` en los lectores, `allowStartIfComplete(true)` en gestor y trabajador, y un
`SimpleStepExecutionSplitter` construido a mano con ese mismo flag, porque el que deriva el
builder lo deja en `false` y entonces una reanudación se saltaría las particiones ya completas
*después* de que la limpieza borrara sus filas. Mezclar ambas estrategias produce pérdida
silenciosa de datos.

> Con particiones el lector de cada tramo ya es de un solo hilo, así que **técnicamente** podría
> volver a guardar su posición y reanudar el tramo donde quedó. Aprovecharlo exigiría anclar los
> reportes a la `JobInstance` en vez de a la `JobExecution`; queda documentado como el siguiente
> paso natural, no implementado.

---

## 7. Cómo ejecutar el proyecto

### 7.1 Requisitos y base de datos

- JDK 17 o superior (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` en macOS)
- Docker

```bash
docker compose up -d      # banco-xyz-db-s3, PostgreSQL 17 en el puerto 5435
./mvnw clean package      # 140 pruebas + informe JaCoCo
```

Se usa el puerto 5435 para no tocar los contenedores de las semanas 1 (5433) y 2 (5434): cada
entrega corre contra su propia base.

### 7.2 Ejecutar los Jobs

```bash
# Un proceso a la vez
java -jar target/banco-xyz-batch-1.0.0.jar --job=transacciones --dataset=semana_1
java -jar target/banco-xyz-batch-1.0.0.jar --job=intereses     --dataset=semana_1
java -jar target/banco-xyz-batch-1.0.0.jar --job=estados       --dataset=semana_1

# Cierre nocturno completo (los tres archivos en paralelo, cada uno particionado)
java -jar target/banco-xyz-batch-1.0.0.jar --job=completa --dataset=semana_3

# Comparar las tres estrategias sobre el mismo archivo
for E in SECUENCIAL MULTIHILO PARTICIONADO; do
  java -jar target/banco-xyz-batch-1.0.0.jar --job=estados --entrada=data/volumen_x10 \
       --banco.batch.estrategia=$E --banco.batch.tamano-chunk=100
done

# Reanudar una corrida fallida (misma etiqueta = misma JobInstance)
java -jar target/banco-xyz-batch-1.0.0.jar --job=transacciones --dataset=semana_3 \
     --corrida=cierre-agosto
```

| Parámetro | Por defecto | Para qué |
|---|---|---|
| `--job` | `completa` | `transacciones`, `intereses`, `estados`, `completa` |
| `--dataset` / `--entrada` | `semana_1` | Carpeta con los CSV |
| `--corrida` | marca de tiempo | Etiqueta; repetirla reanuda la misma instancia |
| `--banco.batch.estrategia` | `PARTICIONADO` | `SECUENCIAL`, `MULTIHILO`, `PARTICIONADO` |
| `--banco.batch.particiones` | `8` | `gridSize` |
| `--banco.batch.tamano-chunk` | `5` | Ítems por transacción |
| `--banco.batch.umbral-rechazo-omision` | `0.30` | Umbral del decisor de calidad |

Código de salida **0** si el Job terminó `COMPLETED`, **1** si falló y **2** si los argumentos son
inválidos, de modo que un planificador (cron, Control-M) pueda encadenar o alertar.

### 7.3 Datos de volumen

```bash
python3 scripts/generar_volumen.py 10 40    # genera data/volumen_x10 y data/volumen_x40
```

---

## 8. Estructura del código (`com.bancoxyz`)

```
batch/
  decider/    DecisorCalidadDeDatos            control de finalización del Job
  listener/   MedidorDeRendimientoListener     ítems/s, chunks y reparto (por StepExecution)
              ResumenDeParticionesListener   ★ reparto y desbalance entre particiones
              ReintentoListener                rastro de cada reintento real
              RegistroRechazadoSkipListener    cada omisión → bitácora auditable (+ hilo)
              ResumenJobListener               cuadro de control, con las particiones indentadas
  partition/  ParticionadorPorRangoDeLineas  ★ reparte el archivo en tramos contiguos
  policy/     PoliticaOmisionBancaria · PoliticaReintentoBancaria
  processor/  TransaccionItemProcessor · InteresItemProcessor · MovimientoAnualItemProcessor
              RegistroDeDuplicados           ★ deduplicación compartida entre particiones
              DetectorDeDuplicados             detección concurrente, segura ante reprocesos
  reader/     LectoresCsv                      un lector por partición, acotado a su rango
  tasklet/    CuarentenaCalidadTasklet · LimpiezaDeReintentoTasklet
              ResumenDiarioTasklet · ResumenInteresesTasklet
              EstadosCuentaAnualesTasklet · ExportarRechazadosTasklet
  writer/     EscritorConFalloSimulado         para evidenciar el RetryPolicy
common/       Constantes
config/       EstrategiaDeEscalado           ★ las tres estrategias, detrás de una propiedad
              ConstructorDePasos               arma el Step según la estrategia
              ConfiguracionEjecutores          los tres pools + serializador JSON
              ArmadorDeJobs · PasosComunesConfig · PropiedadesBatch · LanzadorDeJobs
              …JobConfig (uno por Job)
dto/ entity/ exception/ repository/ service/ util/
```

★ = nuevo en la semana 3.

---

## 9. Resultados de las corridas

Once ejecuciones sobre diez instancias, todas en `evidencias/logs/`:

| # | Corrida | Dataset | Leídas | Escritas | Omitidas | Veredicto | Estado |
|---|---|---|---:|---:|---:|---|---|
| 1 | Job 1 transacciones | `semana_1` | 10 | 10 | 0 | `ACEPTABLE` | `COMPLETED` |
| 2 | Job 2 intereses | `semana_1` | 8 | 8 | 0 | `ACEPTABLE` | `COMPLETED` |
| 3 | Job 3 estados | `semana_1` | 9 | 9 | 0 | `ACEPTABLE` | `COMPLETED` |
| 4 | Cierre completo | `semana_2` | 27 | 22 | 5 | `DEGRADADA` | `COMPLETED` + aviso |
| 5 | Cierre completo | `semana_3` | 3.000 | 1.770 | 1.221 | `INACEPTABLE` | `FAILED` (corte) |
| 6 | Cierre completo, contingencia | `semana_3` | 3.000 | 1.770 | 1.221 | `DEGRADADA` | `COMPLETED` + aviso |
| 7 | Re-ejecución, 1.er intento | `semana_3` | 1.000 | 491 | 509 | `INACEPTABLE` | `FAILED` |
| 8 | Re-ejecución, 2.º intento | `semana_3` | 1.000 | 491 | 509 | `INACEPTABLE` | `FAILED`, sin duplicar |
| 9 | Reintento simulado | `semana_1` | 10 | 10 | 0 | `ACEPTABLE` | `COMPLETED` |
| 10 | Archivo truncado | solo cabecera | 0 | 0 | 0 | `INACEPTABLE` (cobertura) | `FAILED` |
| 11 | **Volumen, 8 particiones** | `volumen_x40` | **40.000** | 38.080 | 1.920 | `ACEPTABLE` | `COMPLETED` en 11 s |

La corrida 11 es la que muestra el particionado a escala: 8 particiones de 5.000 filas cada una,
todas entre 8.676 y 8.762 ms — un desbalance de 1,01×.

**Salidas en base de datos:** `transaccion`, `resumen_diario`, `cuenta_interes`,
`movimiento_anual`, `estado_cuenta_anual`, `registro_rechazado`, más las tablas `BATCH_*`.
**Salidas en archivos (`salida/`):** los tres reportes CSV, `rechazados_<job>.csv` y
`cuarentena_calidad.txt`.

---

## 10. Pruebas

**140 pruebas, 95,5 % de cobertura de instrucciones** (`./mvnw clean test`).

| Clase | Qué vigila |
|---|---|
| `EscalamientoParaleloIT` ★ | Que el archivo se reparta en las particiones configuradas; que **ninguna fila se pierda ni se duplique en los límites**; que la deduplicación vea el archivo completo aunque las copias caigan en particiones distintas; que el resultado no dependa del reparto |
| `DecisorCalidadDeDatosTest` | Los umbrales y sus bordes; que las particiones **no se cuenten dos veces**; que la cobertura mínima se exija al archivo y no a cada tramo |
| `MedidorDeRendimientoListenerTest` | Que dos particiones solapadas no se pisen los contadores |
| `PoliticasDeFinalizacionIT` | Las tres ramas del decisor, el archivo truncado y la idempotencia de la re-ejecución |
| `PoliticaReintentoBancariaTest` | Qué se reintenta y qué no; los parámetros del backoff |
| `JobsDeMigracionIT` · `ReintentoAnteFalloTransitorioIT` | Los cuatro Jobs de extremo a extremo y el reintento |
| `…ItemProcessorTest`, `Parseador…Test`, `EntidadesDestinoTest`, … | Reglas de negocio y utilidades |

★ = reescrita para el modelo particionado.

---

## 11. Decisiones de diseño y sus motivos

| Decisión | Motivo |
|---|---|
| Particiones y no multihilo | La medición: 2,50× contra 1,07× sobre el mismo dato |
| Reparto por rangos de filas | Particiones parejas; el tiempo total es el de la más lenta |
| Contar las filas antes de repartir | Una lectura barata compra un reparto equilibrado (desbalance medido: 1,01×) |
| Un solo hilo dentro de cada partición | El paralelismo ya lo aporta el gestor; sumar hilos dentro devolvería los problemas que el particionado resuelve |
| `RegistroDeDuplicados` singleton, no `@JobScope` | El ámbito `job` está ligado al hilo y no existe en los hilos de partición ni del `split` |
| `linesToSkip` en vez de `currentItemCount` | `open()` retorna antes de saltar cuando `saveState` está apagado |
| Estado del medidor por `StepExecution` | El Step trabajador es un objeto que se ejecuta N veces a la vez |
| Políticas en el trabajador, `startLimit` en el gestor | Cada una donde de verdad tiene efecto |
| Las tres estrategias detrás de una propiedad | Comparar exige el mismo jar y el mismo dato; si cada una viviera en una rama, las mediciones no serían comparables |
| `particiones = 8` | Es el resultado de la medición, y 20 empeora: la máquina tiene 10 núcleos |
| Base propia en el puerto 5435 | No pisar la evidencia de las semanas anteriores |

---

## 12. Estructura del repositorio

```
banco-xyz-batch/
├── data/                     CSV legacy (semana_1/2/3) + volumen_x10 y volumen_x40 generados
├── docker-compose.yml        PostgreSQL 17 en el puerto 5435
├── scripts/generar_volumen.py
├── src/main/java/            Código de la migración
├── src/test/java/            140 pruebas
├── salida/                   CSV generados + aviso de cuarentena
└── evidencias/
    ├── logs/                 14 archivos: 11 corridas, la medición, la comparación y el SQL
    ├── img/                  24 capturas
    └── consultas_evidencia.sql
```

---

## 13. Autor

**Diego Carvajal** — Analista Programador, Duoc UC
Desarrollo Backend III (PBY2203) · Experiencia 1, Semana 3 (evaluación sumativa individual)
Repositorio: <https://github.com/dcarvajal99/banco_xyz_backend_iii_s3>
