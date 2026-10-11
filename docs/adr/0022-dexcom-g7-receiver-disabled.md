# ADR 0022: receiver Dexcom G7 registrado y desactivado por defecto

- Estado: aceptado. El propietario autorizó el 2026-10-11 el receiver
  desactivado por defecto con R1 a R3 (interruptor solo por `adb` con estado en
  Diagnóstico, sin declarar el permiso del productor, rechazo del emisor sin
  leer ni guardar su payload). R4 a R9 son decisiones técnicas del agente,
  revisables en la PR. R10 se añadió tras la revisión de `main` posterior a la
  PR #51 (commit `ccfb753`). R11 la aprobó el propietario el 2026-10-11 sobre
  `61e5f6f` (decisiones 1a a 5a de la sesión), con los ajustes de alcance y
  etapas, y la cuarta etapa del reloj tras la revisión de Codex en la PR #53
- Fecha: 2026-10-11
- Fase y criterio de aceptación: Fase 5, trabajo 2 («adaptador de plataforma
  separado del dominio»). Incremento **preparatorio**: no cumple ningún criterio
  de aceptación de la fase ni valida la recepción con Dexcom real
- Responsables de decisión: propietario
- Evidencia clínica requerida: no aplica; ninguna salida hace disponible la
  glucosa

## Contexto

Los [ADR 0019](0019-dexcom-g7-reception-composition.md),
[0020](0020-dexcom-g7-observation-log.md) y
[0021](0021-dexcom-log-diagnostics-view.md) dejaron autenticación, lectura de
extras, parser, registro local y su vista en Diagnóstico sin ningún punto de
entrada. Falta el componente Android que el sistema invoca al llegar el
broadcast `com.dexcom.cgm.EXTERNAL_BROADCAST`
([contrato observado](../contracts/modified-dexcom-g7-broadcast-observed-v1.md)).

Siguen pendientes, y este ADR **no** las resuelve:

- release reproducible del productor modificado;
- opt-in de identidad (`setShareIdentityEnabled(true)`) en el productor;
- anclas aprobadas (paquete, `versionCode`, SHA-256 de firmantes);
- permiso del productor, semántica de timestamps, tendencia e identidad,
  vigencia y validación con Dexcom real en modo avión.

Sin anclas, la política de confianza es `Pending` y todo mensaje se rechaza.

## Decisión

### Decisiones del propietario

| ID | Pregunta | Decisión |
|---|---|---|
| R1 | Interruptor | Componente declarado con `android:enabled="false"`. Solo se activa con `adb shell pm enable org.bolusai.next/.glucose.dexcom.DexcomG7Receiver` y se vuelve al estado del manifiesto con `pm default-state`. La app no tiene botón. Diagnóstico muestra en solo lectura el estado del componente y de la política |
| R2 | Permiso del productor | No se declara `com.dexcom.cgm.EXTERNAL_PERMISSION`. El productor real no descubre a Next como destinatario: este incremento no puede recibir nada de Dexcom. El permiso entra en el ADR de validación real |
| R3 | Emisor no autenticado | Con la política `Pending`, y ante cualquier rechazo del emisor, no se leen los extras, no se abre el almacén y no se guarda nada (E5 del ADR 0020). Solo los mensajes autenticados llegan al registro |

### Reglas técnicas

| ID | Regla |
|---|---|
| R4 | Orden fijo en `onReceive`: acción → autenticación → extras → parser, síncrono, mediante `DexcomG7Reception`. Una acción distinta de la observada se ignora sin autenticar ni leer: el componente es exportado y cualquiera puede enviarle un `Intent` explícito |
| R5 | El almacén solo se construye para un resultado autenticado, se usa para un mensaje y se cierra. La escritura corre fuera del hilo principal con `goAsync()` en un único hilo serie, y `finish()` se llama siempre. El instante de recepción se lee dentro de `onReceive`, antes de encolar la escritura, y solo para un mensaje autenticado (revisión de Codex en la PR #51). Los fallos del almacén en el hilo de escritura siguen R10 |
| R6 | La política de producción es la constante `DexcomReceiverConfiguration.policy = Pending`. `verify.ps1` rechaza cualquier `DexcomSenderTrustPolicy.Approved(` y cualquier subclase de `DexcomG7Receiver` en el código de producción. Solo la instrumentación crea una subclase con una política sintética para el emisor de pruebas |
| R7 | Identificadores de mensaje y observación: UUID aleatorio (`AndroidObservationIds`). Reloj: pared del dispositivo, solo para sellar la recepción (E8 del ADR 0020) |
| R8 | Componente exportado sin `android:permission`: el permiso del productor es `dangerous`, cualquier app puede obtenerlo y no autentica. La autenticidad la da la identidad del emisor (ADR 0005 y 0006). Se documenta con `tools:ignore="ExportedReceiver"` |
| R10 | En el hilo de escritura ninguna `Exception` escapa: en Android mataría el proceso. `DexcomG7Intake.record` captura por separado la apertura, la escritura y el cierre del almacén y devuelve `DexcomReceipt.StorageFailed` con la primera etapa que falló: `dexcom_receiver.store_open_failed`, `dexcom_receiver.store_record_failed` o `dexcom_receiver.store_close_failed`. El recibo no guarda el texto de la excepción ni el payload. No se reintenta ni se descarta en silencio. El almacén se cierra siempre que se abrió y `finish()` se llama siempre. La glucosa queda bloqueada como `persistence_failed`. Tras `store_close_failed` la escritura pudo confirmarse: decide el registro, no el recibo. Los `Error` de la JVM no se capturan. Los fallos que el almacén SQLite ya convierte en `ObservationWrite.Failed` siguen llegando como `Recorded` con `persistence_failed` |
| R11 | En `DexcomG7Intake.accept` ninguna `Exception` escapa, tampoco la del reloj. `goAsync()` y `writer.execute` quedan fuera de R11 y no se capturan: el ejecutor serie nunca se cierra y no puede lanzar `RejectedExecutionException`. `accept` recibe una fábrica de la fuente de evidencia (no un `PackageManager`) y captura cuatro etapas, cada una en su propio límite: la fábrica de la fuente y `reception.receive` hasta la decisión de autenticación, la llamada a `readPayload`, el parser y la lectura del reloj, que ocurre después del parser y solo para un mensaje autenticado. Así una excepción del parser nunca se presenta como `intake_extras_failed` ni la de los extras como `intake_parser_failed`. La traducción del resultado del parser pertenece a la etapa del parser. Cada etapa da `DexcomReceipt.IntakeFailed(DexcomIntakeFailure, payloadReads)` con su código: `dexcom_receiver.intake_authentication_failed`, `dexcom_receiver.intake_extras_failed`, `dexcom_receiver.intake_parser_failed` o `dexcom_receiver.intake_clock_failed`. La glucosa queda bloqueada como `authentication_failed` en la primera etapa, como `parse_failed` en extras y parser y como `clock_anomaly` en el reloj. Los tres motivos ya existen en `UnavailabilityReason`. Un fallo de autenticación falla cerrado como R3: `payloadReads = 0`, no se abre el almacén, no se guarda nada y no se lee el reloj. Un fallo de extras, del parser o del reloj tras autenticar no se guarda en el registro ni abre el almacén: solo se entrega el recibo, como `StorageFailed` en R10. Tras un fallo del reloj `payloadReads = 1`. El reloj solo se lee si las tres etapas anteriores terminan. El recibo no guarda el texto de la excepción ni datos del payload. No se reintenta. El orden acción → autenticación → extras → parser no cambia y la acción ajena sigue ignorándose sin invocar la fábrica. T3 del ADR 0019 no cambia de significado: `BundleExtrasView` sigue convirtiendo solo `BadParcelableException` en `PayloadUnreadable` y propaga cualquier otra excepción. La captura nueva vive solo en `accept`, convierte la excepción en un recibo y nunca la reetiqueta como `payload_unreadable`. Con la política `Pending` la fábrica se invoca, pero la autenticación rechaza antes de llamar a `attributedIdentity()` o `resolvePackage()`, así que no se consulta `PackageManager`. Crear `AndroidDexcomSenderEvidenceSource` solo guarda referencias. El receiver obtiene la fábrica de un método `senderEvidence(context)` abierto solo para que la instrumentación lo sustituya, como `trustPolicy()` y `openStore()` (R6). Los `Error` de la JVM no se capturan: un `StackOverflowError` (por ejemplo, un `Bundle` anidado sin límite) o cualquier otro `Error` en la lectura de extras sigue cerrando el proceso. Solo puede provocarlo un emisor ya autenticado. Es un riesgo aceptado por el propietario, no una omisión |
| R9 | Diagnóstico añade dos líneas al bloque del registro: `dexcom_receiver.component` (`disabled_by_default`, `enabled`, `disabled` o `unknown`) y `dexcom_receiver.policy` (`pending`). Se leen en cada pintado. `ScreenRenderer` sigue sin conocer el receiver |

### Componentes

En `android/app`, paquete `org.bolusai.next.glucose.dexcom`:

- `DexcomG7Receiver.kt`: `DexcomReceiverConfiguration` (acción y política),
  `DexcomReceipt` (`IgnoredAction`, `SenderRejected` con lecturas de extras,
  `Recorded`, `StorageFailed` con `DexcomStorageFailure`, `IntakeFailed` con
  `DexcomIntakeFailure`), `DexcomG7Intake` (lógica sin Android, probada en JVM) y el
  `BroadcastReceiver` `DexcomG7Receiver`, con la costura de pruebas
  `DexcomG7Receiver.receipts`.
- `DexcomReceiverStatus.kt`: estado del componente y de la política para
  Diagnóstico (R9).
- `storage/DexcomG7Recorder.kt`: `AndroidObservationIds` (R7).
- Manifiesto: un único `<receiver>` desactivado y exportado con un único
  `intent-filter` para la acción observada.
- `android/sender-fixture`: el emisor sintético reenvía, con identidad
  compartida o sin ella, un payload que define la prueba. Su acción es propia
  del fixture; nunca envía la acción de Dexcom.

### Lo que no cambia

`PendingDexcomSource`, `ReadOverview`, Bolo, el motor y el perfil siguen igual.
La glucosa sigue bloqueada en todos los resultados. Sin permisos nuevos, red,
sync ni uso clínico. Cálculo y tratamiento siguen bloqueados.

## Alternativas consideradas

- **Receiver registrado en tiempo de ejecución desde la actividad**: solo
  recibe con la app abierta y no sirve para el uso real.
- **Botón en Diagnóstico**: el propietario eligió R1. Con `Pending`, encender
  el receiver no aporta nada visible y añadiría una escritura desde la UI.
- **Declarar ya el permiso del productor**: el propietario eligió R2. Sin
  concederlo en ejecución tampoco nos descubriría el productor.
- **`android:permission` del productor en el receiver**: no autentica (R8) y,
  sin el productor instalado, el permiso no existiría.
- **Escribir en el hilo principal**: `synchronous=FULL` puede bloquear la
  interfaz. Se usa `goAsync()` (R5).
- **Guardar los rechazos del emisor**: prohibido por E5 y R3.
- **Dejar que la excepción del almacén se propague** (redacción original de
  R5): en el hilo del ejecutor llega al `UncaughtExceptionHandler` y cierra el
  proceso. Inalcanzable con `Pending`, pero debía corregirse antes de aprobar
  cualquier política (R10).
- **Dejar que las excepciones de la fase síncrona se propaguen**: en el hilo
  principal cierran el proceso. Inalcanzable con `Pending`, porque la
  autenticación rechaza antes de tocar `PackageManager`, pero debía corregirse
  antes de aprobar cualquier política (R11).
- **Guardar en el registro los fallos de extras o del parser** (R11): como
  `payload_unreadable` reetiquetaría excepciones que T3 manda propagar y haría
  pasar un fallo nuestro por un defecto del productor. Como resultado nuevo
  exige migrar `dexcom-observations.db` a v2 y equivale a decidir el contador
  duradero, pendiente del propietario.
- **Capturar también `StackOverflowError` en los extras** (R11): ocultaría un
  `Error` de la JVM. Riesgo aceptado: solo un emisor autenticado lo provoca.
- **Contador de fallos de almacenamiento en Diagnóstico**: aplazado. Un
  contador en memoria se pierde al morir el proceso que atendió el broadcast,
  así que Diagnóstico mostraría `0` tras fallos reales: un falso «todo bien».
  Uno duradero necesita otro fichero y su propia decisión, porque el registro
  que falló no puede guardarlo.

## Consecuencias

- Activar el receiver por `adb` no cambia nada observable con `Pending`:
  todo mensaje se rechaza sin leerse ni guardarse.
- El día que existan anclas aprobadas, la validación con Dexcom real necesitará
  su propio ADR: permiso del productor, opt-in de identidad, anclas y prueba en
  modo avión. Hasta entonces este receiver no recibe nada del productor.
- Offline: sin efecto, nada usa red.
- iOS: no recibe este broadcast. Necesitará otra fuente local y su ADR.
- Rollback: quitar el componente del manifiesto. El registro no cambia.

## Seguridad y pruebas

- `DexcomG7IntakeTest` (JVM): acción ajena ignorada sin tocar el emisor,
  rechazos del emisor sin leer extras ni abrir el almacén, mensaje autenticado
  leído una vez, almacén abierto una vez y cerrado también si falla, escritura
  fallida como `persistence_failed`, ningún resultado hace disponible la
  glucosa. R10: apertura que lanza (excepciones de barrera, esquema y E/S),
  escritura que lanza y cierre que lanza dan `StorageFailed` con su etapa y el
  almacén cerrado. Códigos estables. Un `Error` no se oculta. R11: una
  autenticación que lanza (también al crear la fuente) da `IntakeFailed` con
  `payloadReads = 0`, sin leer extras, sin abrir el almacén y sin leer el reloj. Una lectura de extras
  que lanza y un parser que lanza dan su etapa con `payloadReads = 1`, sin
  guardar nada, y ninguna se presenta como la otra. Un reloj que lanza da
  `IntakeFailed(CLOCK_FAILED, 1)` y `clock_anomaly` sin abrir el almacén. Con `Pending`
  la fábrica se invoca una vez y la fuente no recibe ninguna llamada. Un
  `Error` se propaga en cada etapa, también en el reloj. Códigos estables. Ningún recibo hace
  disponible la glucosa.
- `DexcomReceiverStatusTest` (JVM): correspondencia de estados del componente
  y de la política.
- `DexcomLogBlockTest` (JVM): líneas del receiver antes de las del registro.
- `DexcomG7ReceiverDeviceTest` (dispositivo, en la lista de `verify.ps1`):
  manifiesto con un único receiver desactivado, exportado, sin permiso y sin
  permisos Dexcom pedidos; desactivado no recibe; activado con `Pending`
  rechaza sin leer extras ni crear la base, e ignora otra acción; con el emisor
  sintético y una política sintética, el mensaje autenticado se guarda en una
  base sintética y sigue bloqueado, sin identidad compartida se rechaza sin
  abrir el almacén y un payload mal formado se guarda solo como rechazo. R10:
  un almacén sintético que falla al abrir y la barrera que niega el registro
  real entregan `StorageFailed` sin tumbar el proceso, que recibe y guarda el
  mensaje siguiente. El registro real no se crea. R11: una subclase cuya
  fábrica de fuente lanza entrega `IntakeFailed(AUTHENTICATION)` sin tumbar el
  proceso, que recibe y guarda el mensaje siguiente en una base sintética.
- Sin red por construcción: la app no declara `android.permission.INTERNET`,
  `verify.ps1` lo impide y `DexcomG7ReceiverDeviceTest` lo comprueba en el
  dispositivo. Las pruebas no activan el modo avión: el propietario lo descartó
  el 2026-10-11 porque desactiva el Bluetooth y corta la recepción del sensor G7
  real en su teléfono (revisión de Codex en la PR #51).
- `verify.ps1`: forma exacta del receiver en los manifiestos de depuración y
  release, ningún permiso `com.dexcom.`, política de producción `Pending`, sin
  subclases ni anclas en producción y lista cerrada de usuarios del registro.

## Condiciones para revisar este ADR

- Release reproducible, opt-in de identidad y anclas aprobadas.
- ADR de validación con Dexcom real o de presentación de lecturas.
- Cambio de la acción o del transporte observado.
