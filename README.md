# GlacioTools -- herramientas de terreno para glaciologia

Tres herramientas detras de una pantalla de inicio:

- **Connect to a device** -- se conecta a la placa GlacierTemp por Bluetooth LE y por cable
  (USB OTG), descarga el log en binario --completo o por rango--, lo exporta a CSV, lo
  visualiza y ofrece un front-end para los comandos de configuracion.
- **GPS tools** -- promedia arreglos GNSS para fijar una posicion mejor de lo que permite una
  lectura suelta. Exporta a CSV o GPX.
- **Fieldbook** -- libreta de terreno: notas generales con fotos y audio, mediciones de baliza
  con su tasa de ablacion, puntos GNSS con cronometro y alarma, y muestras dendrocronologicas.

Requiere **firmware 3.0 o posterior** (protocolo 4):
https://github.com/caradag/glaciertemp-firmware

Plan completo: `electronics-kb/plans/app-android-glaciertemp.md` en el KB.

## Compilar

    source ~/.glaciertemp-app-env.sh
    ./gradlew :core:test :transport:test     # 347 tests, sin hardware ni emulador
    ./gradlew :app:assembleDebug             # APK en app/build/outputs/apk/debug/

Las pruebas de punta a punta arrancan `tools/fake_glaciertemp.py`, que habla el mismo
protocolo que la placa: no hacen falta ni el telefono ni el equipo.

## Entorno

    source ~/.glaciertemp-app-env.sh

Fija `JAVA_HOME` a Temurin 21 (el JDK del sistema es el 11 y el JBR de Android Studio
es el 25, que Gradle 8.14 no reconoce), `ANDROID_HOME`, y anade al PATH `adb`,
`emulator`, `sdkmanager` y `gradle`.

## Modulos

| Modulo | Que es | Se prueba |
|---|---|---|
| `:core` | Kotlin JVM puro: protocolo, decodificador del log, CSV | sin nada, en segundos |
| `:transport` | interfaz `Transport`, sus implementaciones de prueba, y la logica de BLE y USB que no necesita Android (`GattProfiles`, `BufferedTransport`) | contra el simulador, sin hardware |
| `:transport-android` | la capa fina que si toca Android: `BleTransport`, `UsbSerialTransport`, escaneo | solo con telefono y hardware |
| `:app` | Jetpack Compose | en el emulador, via `adb` |

La regla: si algo se puede escribir sin tocar una API de Android, va en `:core` o en
`:transport`. Por eso la eleccion del perfil GATT --donde de verdad se equivoca uno-- se
prueba sin radio, y `:transport-android` se queda con el pegamento.

## Comandos

    ./gradlew test              # toda la suite
    ./gradlew :core:test        # solo el nucleo, sin procesos externos

    # simulador de la placa, para probar a mano
    python3 tools/fake_glaciertemp.py --stdio  --log-size 500
    python3 tools/fake_glaciertemp.py --tcp 5555 --mtu 20 --conn-interval 30
    python3 tools/fake_glaciertemp.py --pty    # imprime la ruta del pty

    # recorrido completo en el emulador contra el simulador
    ./tools/e2e.sh              # usa el emulador que ya corra
    ./tools/e2e.sh --boot       # lo arranca si no hay ninguno

    # regenerar los vectores dorados desde decode_logh.py
    python3 tools/make_test_vectors.py core/src/test/resources

## Como se valida sin hardware

1. **Unitario**: `:core` contra vectores fijos.
2. **Oraculo**: el CSV de `:core` se compara con el que produce `decode_logh.py`, que ya
   esta probado en campo. Los vectores cubren cinco configuraciones de `LOG_SIGNATURE`.
3. **Punta a punta**: los tests de `:transport` arrancan `fake_glaciertemp.py --stdio`,
   descargan por `LOGB` reensamblando fragmentos de 20 bytes (el MTU del HM-10) y
   comparan el resultado con lo que la misma placa entrega por `LOGC`.

4. **Visualizacion**: la logica del grafico (reduccion min/max, rangos, marcas de eje)
   vive en `:core` y se prueba sin Android; el Canvas de Compose solo dibuja.
5. **UI en el emulador**: `tools/e2e.sh` levanta el simulador, instala el APK de debug y
   corre el test de Compose, que conecta, escribe una variable, descarga por rango y
   comprueba que el CSV sale bien. Deja una captura en `build/screenshots/`.

Lo unico que esto no cubre es el enlace fisico BLE y USB, que necesita placa y telefono.

## Trampas que ya costaron tiempo

- **Activar notificaciones BLE son DOS pasos**: `setCharacteristicNotification` avisa a la
  pila local, pero ademas hay que ESCRIBIR el descriptor CCCD en el dispositivo. Con solo el
  primero se conecta, se aceptan comandos y no llega nunca nada.
- **En el Nordic UART, RX y TX llevan el nombre desde el punto de vista del MODULO**: su RX
  es donde escribe la app. Invertirlos conecta bien y no recibe nada.
- **Sin permiso de escaneo, `startScan` no falla**: devuelve una lista vacia y ningun error.
- **`getBondedDevices()` LANZA sin `BLUETOOTH_CONNECT`**, y no devuelve una lista vacia como
  hace `startScan`. Se llamaba antes de pedir el permiso y desde un `onClick`, donde nadie
  recoge la excepcion: la app se cerraba al pulsar Buscar. Ahora los permisos se comprueban
  ANTES de tocar cualquier API de la radio, y los manejadores de click van envueltos.
- **Conceder permisos con `adb shell pm grant` oculta ese fallo.** Para probar el primer
  arranque hay que instalar SIN `-g` y sin conceder nada a mano, que es como llega la app a
  un telefono de verdad.
- **Una lambda final se ata al ULTIMO parametro.** `AndroidConnectivity(ctx) { ... }` se
  intento atar a `tcpPort: Int`. Con varios parametros con valor por defecto, hay que
  nombrar el argumento.
- **Cambiar de velocidad es una carrera.** El receptor solo sabe que debe cambiar cuando
  termina de LEER la linea que se lo dice, y para entonces el emisor ya esta emitiendo
  distinto. El firmware espera 50 ms tras cada cambio; sin eso, los primeros bytes de cada
  tramo llegan a la velocidad equivocada y el CRC obliga a repetir el bloque cada vez.
- **En Kotlin `-0.0 != 0.0`.** Un `-0.00` en un CSV se lee como cero negativo, y
  `BigDecimal` --que usa el exportador-- no lo tiene, asi que al reexportar sale `0.00` y el
  mismo dato deja de compararse igual consigo mismo tras una ida y vuelta. `CsvImporter`
  normaliza sumando cero.
- **Pedir permisos en `onCreate` rompe los tests de Compose**: el dialogo del sistema tapa
  la Activity y `assertIsDisplayed` falla con "No compose hierarchies found". Se piden al
  pulsar Buscar, que ademas es lo que recomienda Android.

- **El puerto 5555 lo usa adb** para el propio emulador. El simulador va en 5599.
- `Socket().apply { connect(InetSocketAddress(host, port), ...) }` resuelve `port` a la
  propiedad del Socket (0), no al parametro. Nada de `apply` sobre un Socket.
- `Protocol.INFO_HUMAN` ("I") imprime para personas; `Protocol.METADATA` ("INFO") es la
  linea de campos fijos que parsea la app. Atar la constante equivocada da un fallo confuso.
- `parsed?.let { validate(it) } ?: "no es entero"` marca como invalido un valor correcto,
  porque el `let` devuelve null tambien cuando la validacion pasa.
- Un `testTag` en un contenedor no expone el texto de sus hijos a `assertTextContains`.
- Espresso anterior a 3.7 usa reflexion sobre `InputManager.getInstance()`, que ya no
  existe: en API 37 el test instrumentado muere con `NoSuchMethodException`.
- Perder puntos por huecos no es reducir la serie: `isReduced` mira el tamano de bucket,
  no el numero de columnas, o el pie del grafico miente.
- Al insertar codigo al final de un metodo por script, el `}` del metodo queda ANTES del
  bloque nuevo. Comprobar el balance de llaves antes de compilar (paso en falso dos veces).
- Tras `performTextInput` el teclado virtual tapa los botones: hay que cerrarlo y usar
  `performScrollTo()` antes de `performClick()`.

## Fieldbook: la libreta de terreno

Cuatro tipos de entrada, todos con quien, cuando y donde:

| Tipo | Lo que guarda |
|---|---|
| General note | Una SUCESION de anotaciones fechadas: texto, fotos y notas de audio, cada una con su marca |
| Stake measurement | La baliza (nombre, longitud total) y sus lecturas de altura expuesta, con la tasa de ablacion entre consecutivas y una medicion GNSS opcional por lectura |
| GNSS measurement | Un punto de alta precision: receptor, inicio, termino, duracion programada y fotos |
| Dendro sample | Etiqueta fisica, especie, altura de muestreo, perimetro del tronco, notas y fotos |

Las entradas pertenecen a una **campana de terreno**, que se abre sola con la primera
anotacion y se nombra al TERMINARLA -- que es cuando uno sabe como se llamo aquello. Archivar
una campana no mueve ni borra nada: solo la marca con una fecha, y sus entradas dejan de salir
en la lista, que queda vacia para la siguiente. Mover ficheros al archivar habria convertido
una operacion reversible en una que puede perder datos a mitad.

**Las coordenadas salen del GPS del telefono O de un punto ya promediado en GPS tools.** Lo
segundo suele ser mejor dato --minutos de promediado contra una lectura suelta-- y por eso se
ofrece al mismo nivel y no como respaldo. Lo mismo vale ahora para la posicion de una descarga
en *Connect to a device*: se puede sustituir por un punto guardado desde la tarjeta Data, y no
solo cuando el GPS falla.

**Las listas de personas y receptores** se ordenan por uso reciente, y de ahi sale el valor
propuesto en una entrada nueva -- no hay un campo "ultimo usado" aparte que pueda discrepar de
la lista. El boton ⋮ al lado de cada desplegable quita el nombre seleccionado o vacia la lista.
**Quitar un nombre no toca ningun registro**: las entradas guardan el nombre como TEXTO y no
como referencia, asi que una medicion hecha con un receptor sigue mostrandolo despues de que
ese receptor desaparezca del desplegable. Es la razon de que no haya tabla de personas con ids.

**La tasa de ablacion** entre dos lecturas consecutivas es `(altura_actual - altura_anterior) /
dias`, en cm/dia; positiva significa ablacion. Es cambio de superficie en centimetros de hielo
o nieve, NO equivalente en agua. Una lectura sin altura corta la serie en vez de saltarsela:
calcular contra la ultima que si la tenia convertiria un hueco en un intervalo largo sin
decirlo, y una tasa de seis dias mostrada como si fuera de tres es peor que una celda vacia.

### Satelites a la vista

Mientras una medicion GNSS corre, el panel muestra cuantos satelites de cada constelacion ve
el receptor DEL TELEFONO y cuantos esta usando. Sirve para decidir si alargar la ocupacion o
levantar antes, y por eso se separan los visibles de los usados: ver doce y usar cinco no es lo
mismo que ver seis y usar seis.

Dos cosas que la API no dice. La primera: `registerGnssStatusCallback` no enciende el motor
GNSS -- hace falta ademas una peticion de posicion activa, o el callback se registra sin error
y no llega nunca nada. La segunda: eso gasta bateria, asi que el cielo se mira solo mientras el
panel esta EN PANTALLA, no durante las tres horas que dura la ocupacion.

### El cronometro GNSS vive fuera de la app

Una ocupacion de punto dura entre cinco minutos y tres horas, y ese rato el telefono esta en un
bolsillo con la pantalla apagada: Android congela la app, asi que un temporizador dentro del
proceso no salta. El cronometro y la alarma viven en `GnssTimerService`, un servicio en primer
plano con notificacion permanente.

La alarma se programa con `AlarmManager.setAlarmClock` porque es la unica clase de alarma que
Doze no aplaza nunca. El icono de alarma del sistema, que es el precio, resulta util: dice que
hay una medicion corriendo. El sonido lo pone el propio servicio con `USAGE_ALARM`, asi que no
lo silencia el modo silencioso.

**SI hace falta permiso de alarma exacta.** Este README afirmaba lo contrario y era falso:
`setAlarmClock` lanza `SecurityException` sin `USE_EXACT_ALARM` o `SCHEDULE_EXACT_ALARM`
("Caller needs to hold..."), comprobado en el aparato. Con la excepcion dentro de un
`runCatching`, la alarma no existia y nada lo decia -- por eso no sonaba. Ahora se declara
`USE_EXACT_ALARM` (API 33+, se concede al instalar y esta pensado para apps cuya funcion es
avisar a una hora) y `SCHEDULE_EXACT_ALARM` con `maxSdkVersion=32`. Si aun asi falla, se
degrada a `setAndAllowWhileIdle` --inexacta pero exenta de Doze-- y **la notificacion lo
dice**, en vez de prometer una hora que no se va a cumplir.

La leccion general: una alarma que no se pudo poner y una que se puso bien se ven IGUAL --nada--
hasta el momento en que una suena y la otra no, tres horas despues y en el hielo. Un
`runCatching` alrededor de una operacion cuyo unico efecto es futuro es una forma de no
enterarse.

**La alarma no termina la medicion.** Suena, y la medicion sigue hasta que alguien pulsa
*Measurement End*, para que la hora de termino sea el momento real en que se levanto el
receptor y no la hora a la que venia programado levantarlo. El cronometro de la notificacion lo
pinta el SISTEMA (`setUsesChronometer`), no la app: un texto que la app tuviera que refrescar
cada segundo se congelaria en cuanto Android la duerma, que es el 99 % del tiempo.

### Exportar

Un ZIP con:

| Fichero | Que lleva |
|---|---|
| `gnss_measurements.csv` | Una fila por OCUPACION de punto, vengan de una entrada GNSS o de una lectura de baliza: quien abre este fichero quiere todos los puntos, le da igual de donde cuelguen |
| `stake_measurements.csv` | Una fila por LECTURA, con los dias transcurridos y la tasa de ablacion contra la anterior |
| `dendro_samples.csv` | Una fila por muestra |
| `fieldbook.odt` | Todas las notas en orden CRONOLOGICO cruzando los cuatro tipos, con vistas previas de las fotos y una ficha por cada audio |
| `Pictures/<punto>/` | Las fotos originales, en una carpeta por punto o baliza |
| `Audio/<entrada>/` | Los audios originales |

En los CSV la columna `associated_images` lleva el NUMERO de fotos, no sus nombres: una columna
de longitud variable no la sabe usar ninguna hoja de calculo, y se rompe en cuanto una foto se
renombra.

El ODT se escribe a mano --un `.odt` es un ZIP con `content.xml`, `styles.xml`, un manifiesto y
las imagenes en `Pictures/`-- para no meter una dependencia de ODF en `:core`. La unica regla
rara del formato: la entrada `mimetype` tiene que ser la PRIMERA del zip y estar SIN comprimir,
o LibreOffice y Word lo rechazan sin decir por que. Hay un test que lo fija, y la salida se
valido abriendola de verdad con `soffice --convert-to`.

El zip se escribe directamente sobre el flujo de SAF, sin pasar por memoria: una campana con
doscientas fotos son cientos de megabytes, y armarlo como ByteArray mataria la app justo al
final de la campana.

### Como se guarda

Un fichero de texto por entrada en `files/fieldbook/`, mas `files/fieldbook/media/` para fotos
y audios. Cabecera de `clave=valor`, un `---`, y bloques `[nombre]`; una clave repetida dentro
de un bloque es una lista. Mismo criterio que los puntos de GPS: `:core` sin dependencias, y un
dato de terreno que se abre con cualquier cosa dentro de diez anos.

A diferencia de un punto de GPS **no se hace append**: una entrada se reescribe entera, via
temporal y rename. Un punto acumula miles de muestras; una entrada son unos kilobytes y ademas
se EDITA --corregir una hora, cambiar una altura-- que es algo que un formato de solo anadir no
sabe hacer.

Lo estructural --anadir una lectura, arrancar un cronometro, poner una coordenada-- se escribe
en el acto. Lo que se TECLEA se agrupa medio segundo: un `save` por pulsacion es un fichero
reescrito treinta veces por segundo en el hilo principal, y eso en un telefono frio es como se
provoca un ANR escribiendo una nota. El volcado se fuerza al cerrar la entrada, en `ON_STOP` y
al destruirse el ViewModel, que son las tres salidas.

### El banco instrumentado y el permiso de localizacion

`MainActivity.onCreate` pide la localizacion, y `connectedAndroidTest` instala SIN permisos
concedidos: el dialogo del sistema (`GrantPermissionsActivity`) se abre ENCIMA de la app, el
`ComposeTestRule` mira entonces una ventana que no es la suya y todos los tests mueren con
"No compose hierarchies found" antes de ejecutar una sola asercion. Por eso estos tests no
habian corrido nunca aqui. De ahi el `testOptions { installation { installOptions("-g") } }`.

La regla de NO usar `-g` sigue vigente para lo que la motivo --que la app se instale y arranque
como en un telefono de verdad, sin permisos previos--, y ese camino hay que recorrerlo a mano
al menos una vez por entrega, porque este banco ya no lo cubre.

Dos gotchas mas del banco, las dos descubiertas fallando:

- **Cerrar el teclado despues de escribir.** La pantalla lleva `imePadding()`, asi que con el
  teclado abierto el contenido se encoge y lo que estaba abajo sale de la ventana. Un
  `performClick` posterior aterriza sobre el teclado y no sobre la fila, **sin dar ningun
  error**: el clic se ejecuta, pero en otro sitio. `Espresso.closeSoftKeyboard()` despues de
  cada `performTextReplacement`.
- **Las celdas de la tabla de balizas solo existen en el arbol SIN fusionar.** La fila lleva
  `Modifier.clickable`, que fusiona a sus hijos en un unico nodo de semantica: en el arbol
  normal las tres celdas son un solo texto y las etiquetas por celda no existen. Hay que usar
  `useUnmergedTree = true`.

### Dos trampas del ciclo de vida que ya estan puestas

- **El fichero de la foto se crea ANTES de lanzar la camara.** Mientras la camara esta delante,
  Android puede matar este proceso; al volver, el resultado llega a un `remember` recien nacido
  en null y la foto estaria en disco sin que la libreta sepa de ella. Se guarda la RUTA en un
  `rememberSaveable`, que sobrevive en el Bundle.
- **El barrido de medios huerfanos respeta una edad minima de diez minutos.** Sin ella borraria
  justo el fichero en el que la camara esta escribiendo, porque todavia no lo nombra ninguna
  entrada.

## Abrir un CSV ya exportado

La app carga un CSV suyo --o la salida de `LOGC`-- y lo grafica igual que una descarga, sin
placa ni conexion. La pieza que lo hace posible es que `CsvImporter` **reconstruye el
`LOG_SIGNATURE` a partir de los nombres de la cabecera**: de ahi en adelante un log leido de
fichero y uno recien descargado son indistinguibles, y el grafico, la estimacion de bateria
y la reexportacion son el mismo codigo.

Se comprueba ademas que el ORDEN de las columnas sea el que produce el firmware. Con las
columnas cambiadas de sitio el grafico saldria plausible y equivocado, que es peor que un
error.

Hay un fichero de ejemplo en `samples/glaciotools_ejemplo.csv`, generado por
`tools/make_sample_csv.py`: 45 dias a intervalo de 10 minutos, con ciclo diario, descarga
real de la bateria, lecturas `NaN` y un hueco de seis horas. `SampleCsvTest` lo recorre
entero --importar, graficar, estimar bateria, reexportar-- porque un fichero de prueba que
no se prueba deja sin saber si lo que falla es el fichero o la app.

## Identidad de placa

El W25Q64 entrega 64 bits unicos de fabrica (opcode `0x4B`), pero Winbond **no documenta como
estan compuestos**. Por eso la forma corta se obtiene por **CRC-32 y no recortando bytes**:
en los identificadores de silicio es habitual que los bytes altos codifiquen lote y oblea, y
dos placas compradas juntas serian las mas expuestas a compartirlos -- recortar es mas
arriesgado justo en el caso real. El CRC reparte uniforme sea cual sea la estructura, asi que
el riesgo se calcula: con 32 bits y 100 placas, una colision entre 870.000.

Es el CRC-32 estandar (zip, PNG), reproducible con `zlib.crc32`, y un test lo compara contra
`zlib` en vez de contra un numero copiado a mano.

**Lo calcula la placa**, no la app: deducirlo en los dos extremos serian dos implementaciones
del mismo CRC esperando a divergir. Viaja en `INFO` como `sid=` junto al completo en `id=`,
que sigue siendo la identidad canonica y sirve para desempatar.

## Velocidad de linea

La consola va a **115200**, no a 230400. La razon principal es que los modulos HM-10 y sus
clones no pasan de ahi: a 230400 la placa y el modulo no se entienden y el enlace BLE no
puede funcionar. Ademas es lo que usa el bootloader de MiniCore con este cristal, asi que
carga, consola y Bluetooth hablan todos igual, y no cuesta exactitud (`UBRR=7`, cero error).

**Por cable** el volcado binario puede subir a 230400 mientras duran los bloques:
`LOGB=a,b,230400`. El texto de apertura y cierre se queda despacio, de modo que los dos
extremos siempre tienen una referencia comun. Merece la pena para el volcado completo --12
min frente a 6-- y no para el uso diario, donde son dos segundos.

Quien decide es `DeviceSession`, y solo cuando se dan las DOS condiciones: que la placa lo
declare en `INFO` (`fastbaud=`) y que el transporte implemente `BaudSwitchable`. El
Bluetooth no lo implementa a proposito, asi que nunca se le pide algo que no puede hacer.

La restauracion va en un `finally` y ademas se dispara al recibir el ultimo bloque. Esperar
al `LOGB end` no serviria: ese texto ya viaja despacio y no se leeria desde la velocidad
alta.

## Oraculo contra hardware real

`core/src/test/resources/real_board_logb.bin` y `real_board_logc.csv` salieron de una placa
de verdad (firmware 2.7, id `DF652892D71B4237`, signature `0x1007`): la carga util de
`LOGB=0,99` y las filas que la MISMA placa emitio por `LOGC`. `RealBoardTest` decodifica la
primera y exige que coincida con la segunda.

Es la unica prueba sin margen de interpretacion. Todo lo demas se valida contra el simulador,
y el simulador ya dejo pasar un fallo real: respondia con el codigo del comando en vez del
rotulo, asi que la lectura de configuracion pasaba en verde mientras la app mostraba `?` en
el telefono.

## Leer la configuracion: por el VALOR, nunca por el rotulo

`displayVars()` no imprime el codigo del comando sino el rotulo que el initializer grabo en
la EEPROM: pedir `INT` devuelve `Interval between measurements (sec): 600`. La app buscaba
`INT:`, no encontraba nada y mostraba `?` en todos los campos contra una placa real, aunque
escribir si funcionara.

`VariableSpec.parseValue()` toma el numero tras el `:` de la PRIMERA linea que encaje, y no
mira el rotulo, porque quien llama ya sabe que variable pidio y ese texto vive en la EEPROM de
cada placa.

La primera y no la ultima por lo que contesta una placa real a `INT`:

    Interval between measurements (sec): 2
    Next Wakeup: 2026-09-05 00:03:10 (UTC-3)

Esa segunda linea solo se salva de coincidir porque termina en `(UTC-3)`. Sin la zona horaria
terminaria en `:10` y el intervalo pasaria a valer diez.

**El simulador respondia con el codigo** (`INT: 600`) en vez de con el rotulo, asi que la
cadena entera pasaba en verde mientras la app fallaba en el telefono. Ahora imita a
`displayVars()`, y un test fija esa fidelidad. Un simulador que no reproduce el formato real
solo sirve para aprobar codigo roto.

## La firma del formato, en palabras

`0x1007` no le dice nada a quien mira la pantalla. Codifica QUE canales escribieron el log
--por eso el decodificador recupera la disposicion del registro a partir del propio dato--
asi que a una persona hay que darle la lista de columnas:

    Recorded channels
      1.  Battery voltage
      2.  Air temperature (HDC1080)
      3.  Relative humidity (HDC1080)
    3 channels · 10 bytes per record · code 0x1007

## El tamano de tramo se busca solo

El optimo depende del buffer del modulo, que nadie documenta y que cambia con cada marca:
contra un HM-10 real, 16 registros por peticion fallaban y 12 pasaban. Fijarlo de antemano
es adivinar.

`DeviceSession.download` hace una **busqueda binaria ESTRICTA**: sin ningun tamano bueno se
parte por la mitad, y en cuanto hay horquilla se prueba siempre el punto medio. Bajar de tres
en tres cuartos costaba seis intentos fallidos para ir de 256 a 45 (192, 144, 108, 81, 60,
45); partiendo por la mitad son tres (128, 64, 32).

`DeviceSession.download` hace una **busqueda binaria**: guarda el mayor tamano que funciono
y el menor que fallo, y se mueve al punto medio entre ambos --hacia abajo tras un fallo,
hacia arriba tras varios tramos buenos-- hasta que los dos extremos se juntan. Entonces el
tamano queda fijo.

Se guarda el CONJUNTO de tamanos que funcionaron, no solo el mayor. Cuando el mayor empieza
a fallar hay que retroceder al siguiente que funciono, no olvidarlo todo: poniendo el limite
inferior a cero, la busqueda partia por la mitad y caia POR DEBAJO de un valor ya conocido
como bueno --32 funcionaba, 48 fallaba y se probaba 24--.

**Un tamano que funciono y despues falla deja de ser referencia.** Sin eso, `maximoBueno` se
quedaba en 60 mientras 60 acababa de fallar; el punto medio entre 60 y 60 vuelve a dar 60, el
tope de seguridad lo bajaba a 59, y la busqueda degeneraba en un descenso de UNO EN UNO --60,
59, 58 ... 36-- con una peticion fallida en cada escalon.

Antes bajaba y no volvia a subir: contra un modulo real cayo de 81 a 33 y ahi se quedo,
dejando dos tercios de la velocidad sin usar. Y antes de eso tanteaba periodicamente para
siempre, que es provocar un fallo a proposito una y otra vez. La busqueda binaria acorrala
el maximo en unas pocas pruebas y despues para.

Sin ningun tamano bueno todavia se baja un CUARTO y no la mitad. Medido contra un HM-10, 16
registros fallan y 12 pasan: desde 16, la mitad aterriza en 8.

El resumen final muestra **los dos** valores, inicial y final. El final es el ULTIMO que
completo un tramo, no el mayor que alguna vez funciono: si el enlace empeora a mitad de
descarga, el mayor ya no describe como acabo.

Por debajo de `MIN_RECORDS_PER_REQUEST` se rinde: ahi el problema ya no es el tamano.

Importa afinarlo porque el coste fijo por peticion es grande --cabecera, cierre y una ida y
vuelta por radio--: con 12 registros, 120 bytes de datos, ese coste supera al de los datos.
Por eso la app muestra al terminar cuanto tardo y a que ritmo: es lo unico que permite
comparar un ajuste con otro en vez de opinar.

`AdaptiveChunkTest` prueba la convergencia con una placa de mentira, en milisegundos. Contra
el simulador cada reduccion cuesta un ciclo completo de reintentos y la misma prueba tardaba
minutos.

## Comparar contra un texto visible es fragil

`s.source.startsWith("fichero")` decidia si mostrar el boton de cerrar. Al traducir ese texto
a "file", el boton dejo de aparecer y nada aviso: ningun test mira un prefijo escrito a mano.
Ahora hay una bandera `fromFile` y el texto es solo texto.

## Paleta

El logotipo se pinta con dos tonos, pero "Tools" NO puede llevar el marino fijo: en modo
oscuro el fondo ES ese marino y la palabra desaparecia. Se toma de `onBackground`, asi que
sale marino sobre claro y casi blanco sobre oscuro, como hace el logo en sus dos versiones.


`Theme.kt` toma los tres azules del logo CONTANDO los pixeles del icono, no eligiendolos a
ojo: marino `#0B2A4A` de fondo, celeste `#99E5FE` de la montana y azul `#3EB7EF` del
circuito. En claro manda el azul medio --el marino sobre blanco pesa y no se distingue del
texto-- y en oscuro el marino pasa a ser el fondo, que es su papel en el logo.

## No esperar al "LOGB end"

Era el grueso del tiempo de descarga por BLE. El log de un usuario mostraba 41 tramos con

    LOGB 624..635: no end marker after 1/1 blocks

El bloque llegaba, pero el texto de cierre se perdia por radio y el bucle se comia los TRES
segundos completos de silencio antes de darse por vencido. Ahora se termina en cuanto han
llegado todos los bloques que anuncia la cabecera: el "LOGB end" es una cortesia, no un dato.

## El retroceso tiene que ser estrictamente menor

El mismo log terminaba repitiendo sin fin:

    LOGB 420..479: no header; the board answered nothing usable
    Chunk too large for this link; settling on 60 records per request

Al fallar se volvia "al ultimo tamano que funciono", que era el mismo 60 que acababa de
fallar: ni avanzaba ni se rendia. **Bucle infinito.** Ahora el tamano nuevo tiene que ser
estrictamente menor que el que fallo, y si no se puede bajar mas, el error sube.

Un tamano que funciono deja de valer como referencia en cuanto falla una vez: las condiciones
del enlace cambian durante la descarga.

## El terminal escribe segun llega, y con buffer propio

`exchange` acepta un `onChunk` que se llama con cada trozo. Sin el, un `LOG` de miles de
filas tardaba minutos en aparecer y hasta entonces parecia colgado.

Las lineas se acumulan en un `ArrayDeque` y el estado se publica **como mucho cada 150 ms**.
Publicar en cada linea copia la lista entera --cuadratico-- y con BLE, que entrega de veinte
en veinte bytes, un volcado provocaba decenas de miles de recomposiciones: escribir en vivo
salia mas lento que esperar al final, justo lo que se queria evitar.

El resto de linea se guarda entre trozos, porque un fragmento puede cortar una linea por la
mitad y pintarla partida desalinea las columnas.

El tope pasa de 500 a 20.000 lineas: 500 cortaba el volcado justo cuando mas falta hacia
verlo entero.

## El progreso se cuenta en registros

Antes contaba bloques de la peticion en curso. Con tramos cortos cada peticion cabe en un
solo bloque de 256 bytes, asi que el indicador decia **"1/1" de principio a fin**; y el ritmo
se reiniciaba en cada tramo, marcando 0,0 kB/s casi siempre. El registro es ademas la unidad
en la que uno piensa: el bloque es un detalle del protocolo.

## El teclado, con targetSdk 36

Con `targetSdk` 36 Android impone el modo borde a borde y **`windowSoftInputMode="adjustResize"`
ya no encoge la ventana**: la aplicacion tiene que descontar el teclado ella misma. Sin eso el
sistema DESPLAZA la ventana y la cabecera se sale por arriba.

`imePadding()` va en la Column EXTERIOR, una sola vez. Puesto ademas dentro de la pestana se
contaba dos veces y dejaba un hueco en blanco bajo el terminal. Y el contenido de las
pestanas va dentro de un `Box(Modifier.weight(1f))`, para que al encogerse sea el contenido
--no la cabecera-- lo que cede espacio.

Un `--` dentro de un comentario XML es ilegal y rompe la fusion del manifiesto con un
"Error parsing" que no dice cual es la linea.

## Descarga larga: por tramos, no de una vez

Con 3.238 registros la descarga se quedaba en "Downloading..." para siempre. Dos causas
encadenadas:

1. **Un puente serie-BLE recibe de la placa a 11,5 kB/s y solo mueve 1-5 kB/s por radio.**
   Ante una peticion de 38 kB su buffer interno se desborda y DESCARTA bytes sin avisar. Con
   20 registros (240 B) cabe y funciona; con 3.238 se pierde casi todo.
2. **El reintento era silencioso y sin tope util.** 152 bloques perdidos, uno a uno, con
   `quietMs=1500` y dos vueltas: ocho minutos sin decir nada.

Ahora `Transport.recordsPerRequest` deja que cada transporte fije el tamano del tramo y la
placa solo envia lo que se le pide, de modo que la radio se vacia entre peticiones.

**El tamano sale del MTU negociado**, medido contra un HM-10 real: el modulo acepto MTU 23,
o sea 20 bytes por notificacion, y de una peticion de 128 registros (1,3 kB) llego un unico
bloque de cinco. Con ese MTU se piden 16 registros; con uno grande, 256. Y se puede forzar
desde la propia pantalla de descarga, porque el buffer de cada modulo no esta documentado y
darle con el probando es mas rapido que deducirlo. El reintento tiene tope de tiempo, se rinde si hay
mas de ocho bloques perdidos (eso no es un fallo puntual sino un enlace que no da abasto) y
manda lo que va viendo al terminal por `onDiagnostic`.

`fake_glaciertemp.py --module-buffer N --drain-bps B` reproduce el desbordamiento, y un test
exige que una descarga de 3.238 registros salga completa y que un enlace con perdidas falle
en menos de dos minutos con un mensaje que diga por que.

## Huecos: el eje va por TIEMPO, no por indice

Con el eje por indice, una noche entera sin registrar ocupaba en pantalla lo mismo que un
intervalo de muestreo, y la linea cruzaba el hueco como si hubiera medido. Ahora la X sale de
la marca de tiempo de cada muestra, y la serie se corta cuando el salto supera 1,5 veces el
intervalo tipico.

El intervalo tipico es la **mediana** de los saltos y no la media: la media la arruina un solo
hueco largo, y es justo cuando hay huecos cuando hace falta el valor.

`Sampling` deriva de las marcas de tiempo el intervalo tipico, el hueco mayor, las muestras
que faltan y el porcentaje de cobertura. Sale del log y no de la configuracion de la placa,
que pudo cambiar durante el despliegue.

## Zoom sin robarle el gesto a la pagina

El grafico acepta pellizco y arrastre, pero **no puede consumir el arrastre vertical**: si lo
hace, la pagina deja de poder desplazarse mas alla de el. Solo se consume el pellizco --que
siempre lleva dos dedos-- y el arrastre de un dedo cuando ya hay zoom, que es cuando el
usuario espera mover la vista y no la pagina.

## Grafico: que es la linea y que la banda

Con mas registros que pixeles la serie se reduce a columnas de `bucket` registros. De cada
columna se guarda el **minimo y el maximo**, no el promedio: promediar borraria justo los
picos que interesa ver.

- **La banda sombreada** es el rango min-max de los registros de esa columna.
- **La linea** une el punto medio de cada banda, `(min + max) / 2`.

No hay ningun suavizado ni media movil. Cuando no hay reduccion --pocos registros-- la banda
desaparece y la linea pasa por los valores reales.

Ojo con la linea: el punto medio entre los extremos **no es la media** de los registros de la
columna. Coincide con la convencion climatologica `(Tmax + Tmin) / 2`, que es util, pero si
lo que se quiere es la media aritmetica hay que decirlo, porque no es lo que se dibuja.

Debajo del grafico van las estadisticas del canal --minimo, maximo con sus fechas, media,
inicio, termino y duracion--, y esas SI se calculan sobre los registros crudos, no sobre la
serie reducida: la reduccion conserva los extremos pero no cuando ocurrieron.

## Icono

Se genera desde `glaciotools-logo-pack/glaciotools-icon-square-1024.png` con ImageMagick.
Se parte del cuadrado y no del que ya viene redondeado: Android aplica su propia mascara y
un redondeado previo se recortaria dos veces.

De los 108 dp del lienzo adaptativo la mascara solo garantiza los 66 centrales, asi que el
dibujo va al 50% y queda holgado bajo cualquier forma. Al 60% la linea inferior se recortaba.

No se declara capa `monochrome`: Android tinta esa capa de un solo color cuando el usuario
activa los iconos tematicos, y pasarle el dibujo a color da un resultado impredecible.

## Simulador desde un telefono real

`10.0.2.2` es el alias que resuelve el EMULADOR al PC anfitrion. En un telefono no es
ninguna direccion: el intento agota el plazo sin decir por que, asi que la app detecta el
caso y lo explica.

La seccion del simulador **solo se muestra dentro del emulador**, que es donde `10.0.2.2`
significa algo. En un telefono era un boton que siempre falla. El test instrumentado corre
en el emulador, asi que la sigue viendo.

Dos formas de llegar al simulador desde un telefono, si alguna vez hace falta:

1. **Por USB, y funciona siempre** -- no depende de la Wi-Fi ni de que la red permita
   trafico entre clientes:

        adb reverse tcp:5599 tcp:5599

   y en la app poner `127.0.0.1:5599`. Las conexiones al puerto 5599 del telefono salen por
   el del PC.

2. **Por red local**: poner la IP del PC (`ip -4 -o addr show`) y comprobar que telefono y
   PC estan en la MISMA subred. Muchas redes institucionales aislan los clientes entre si y
   entonces esto no funciona por mucho que la IP sea correcta.

## Nota sobre `TcpTransport`

`TcpTransport` existe para que la app corra en el emulador contra el simulador
(`10.0.2.2`). Los tests usan `PipeTransport` en su lugar: no necesita red, es
determinista y no hay puerto que colisione.
