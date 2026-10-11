# Fallos de la fase síncrona del receiver Dexcom G7 — 2026-10-11

Base Next: `61e5f6f` (merge de la PR #52). Rama
`claude/zealous-lamport-8buvya`. Añade R11 al
[ADR 0022](../adr/0022-dexcom-g7-receiver-disabled.md), aprobada por el
propietario con las decisiones 1a a 5a y los ajustes de alcance y etapas.
Legacy no se consulta ni se modifica.

## Problema

`DexcomG7Intake.accept` corre en el hilo principal dentro de `onReceive` y
dejaba propagar cualquier excepción inesperada de la autenticación, de la
lectura de extras o del parser. En el hilo principal eso cierra el proceso.

El fallo es latente. Con la política de producción `Pending`, la autenticación
rechaza en `DexcomSenderEvidenceSource.kt` antes de llamar a
`attributedIdentity()` o `resolvePackage()`, así que no se consulta
`PackageManager` ni se leen extras. Solo se alcanza con una política
`Approved`, que hoy existe únicamente en la instrumentación sintética. Puntos
que podían lanzar con esa política:

- autenticación: `getPackageInfo` con una `RuntimeException` distinta de
  `NameNotFoundException` y `SecurityException` (por ejemplo, el proceso del
  sistema caído);
- extras: `BundleExtrasView` solo convierte `BadParcelableException` (T3 del
  ADR 0019) y propaga el resto;
- parser: solo su `require` del paquete autenticado, inalcanzable en la
  práctica.

## Cambio

- `accept` recibe una fábrica de la fuente de evidencia en lugar de la fuente.
  El receiver la obtiene de `senderEvidence(context)`, abierto solo para la
  instrumentación, como `trustPolicy()` y `openStore()`.
- Tres límites separados en `accept`: la fábrica y `reception.receive` hasta la
  decisión de autenticación, la llamada a `readPayload` y el parser. Cada uno
  marca la excepción con su etapa y nunca con la de otro. La traducción del
  resultado del parser pertenece a la etapa del parser.
- `DexcomG7Reception` recibe el parser como dependencia (por defecto
  `DexcomG7PayloadParser::parse`), para que `accept` pueda envolverlo.
- Recibo nuevo `DexcomReceipt.IntakeFailed(DexcomIntakeFailure, payloadReads)`
  con `dexcom_receiver.intake_authentication_failed` (glucosa
  `authentication_failed`), `dexcom_receiver.intake_extras_failed` y
  `dexcom_receiver.intake_parser_failed` (glucosa `parse_failed`) y, tras la
  revisión de Codex, `dexcom_receiver.intake_clock_failed` (glucosa
  `clock_anomaly`). Los tres motivos ya existían en `UnavailabilityReason`.
- Sin texto de la excepción ni datos del payload en el recibo. El marcador
  interno de etapa no guarda ni mensaje original ni causa.
- Fallo de autenticación: `payloadReads = 0`, sin almacén ni reloj. Fallo de
  extras o parser: `payloadReads = 1`, sin almacén ni reloj. Nada se guarda en
  el registro y no se reintenta.
- T3 sin cambios: `BundleExtrasView` sigue convirtiendo solo
  `BadParcelableException` en `PayloadUnreadable`. `accept` nunca reetiqueta
  como `payload_unreadable`.
- Solo se captura `Exception`. Un `Error` (incluido un `StackOverflowError` en
  los extras) sigue cerrando el proceso. Solo puede provocarlo un emisor ya
  autenticado: riesgo aceptado por el propietario.
- `goAsync()` y `writer.execute` quedan fuera de R11 y no se capturan: el
  ejecutor serie nunca se cierra y no puede lanzar
  `RejectedExecutionException`.
- Política `Pending`, orden acción → autenticación → extras → parser, motor,
  perfil, Bolo y lógica clínica sin cambios. Sin red.

## Revisión de Codex (PR #53)

Hallazgo P2 sobre `4137866`: `clock.nowEpochMs()` se leía en `accept` pero
fuera de los límites, así que un reloj inyectado que lanzara escapaba de
`onReceive`. El reloj de producción (`System.currentTimeMillis()`) no lanza,
pero R11 promete que ninguna `Exception` escapa de `accept`. El propietario
eligió una cuarta etapa: `dexcom_receiver.intake_clock_failed`, glucosa
`clock_anomaly` (motivo existente), `payloadReads = 1`, sin guardar nada ni
abrir el almacén. El reloj se sigue leyendo después del parser y solo para un
mensaje autenticado. Regresión: sin el límite del reloj fallan
`a clock that throws after authentication is a clock failure and nothing is opened`
y `no receipt makes glucose available` con la excepción escapando.

## Pruebas

| Clase | Pruebas | Resultado |
|---|---|---|
| `DexcomG7IntakeTest` (JVM Android) | 24 | 9 nuevas (1 de la revisión de Codex) y 1 ampliada, ejecutadas, 0 fallos |
| Resto de `org.bolusai.next.glucose.dexcom.*` y `DexcomLogBlockTest` (JVM Android) | 42 | ejecutadas, 0 fallos |
| `DexcomG7ReceiverDeviceTest` (dispositivo) | 11 | 1 nueva, 11 ejecutadas en el Pixel sobre `d1708f4`, 0 fallos |

Pruebas nuevas en JVM:

- `an authentication that throws reads no extras, opens no store and reads no clock`:
  fábrica que lanza, `attributedIdentity()` que lanza y `resolvePackage()` que
  lanza dan `IntakeFailed(AUTHENTICATION, 0)`, sin lector, parser ni reloj, y
  sin el texto de la excepción en el recibo.
- `the pending policy invokes the source factory once and never queries the source`:
  fábrica 1, `attributedIdentity()` 0, `resolvePackage()` 0, `payloadReads` 0.
- `another action never invokes the source factory`.
- `extras that throw fail the extras stage after one read and never reach the parser`:
  `IntakeFailed(EXTRAS, 1)`.
- `a parser that throws fails the parser stage, never the extras stage`:
  `IntakeFailed(PARSER, 1)` con el mismo tipo de excepción que la prueba de
  extras.
- `an unreadable payload is still recorded as unreadable, not as an intake failure`
  (T3).
- `a clock that throws after authentication is a clock failure and nothing is opened`:
  `IntakeFailed(CLOCK_FAILED, 1)` y `clock_anomaly`, sin paso de escritura.
- `jvm errors in the synchronous stages are not swallowed`: un `Error` en la
  fábrica, en la fuente, en los extras, en el parser y en el reloj se propaga
  intacto.
- `intake failure codes are stable`, con su motivo de glucosa (cuatro códigos).
- `no receipt makes glucose available` añade los cuatro `IntakeFailed`.

Mutación de control: sin el límite de extras, la prueba de extras falla con
`expected IntakeFailed(EXTRAS, 1) but was IntakeFailed(PARSER, 1)`. Se restauró
el código antes de seguir.

Prueba nueva en dispositivo:

- `aSenderEvidenceFailureOnTheMainThreadIsIntakeFailedAndTheProcessSurvives`:
  la fábrica de la subclase lanza en `onReceive`, en el hilo principal. Llega
  `IntakeFailed(AUTHENTICATION, 0)`, el almacén no se abre, la base sintética
  no se crea y el mismo proceso autentica, lee y guarda el mensaje siguiente.
  `@After` restaura `COMPONENT_ENABLED_STATE_DEFAULT`.

## Comandos y resultados (contenedor Linux de la sesión)

Android SDK 36 (`platforms;android-36`, `build-tools;36.0.0`) instalado en
`/tmp/android-sdk`. Maven Central devolvió 429: `mirror.gradle` es un script de
inicialización local, fuera del repositorio, que usa el espejo de Google de
Maven Central.

```bash
export ANDROID_HOME=/tmp/android-sdk
./gradlew -I mirror.gradle :android:app:testDebugUnitTest \
  --tests 'org.bolusai.next.glucose.dexcom.*' --tests 'org.bolusai.next.DexcomLogBlockTest' \
  :android:app:compileDebugAndroidTestKotlin          # BUILD SUCCESSFUL, 65 pruebas, 0 fallos
./gradlew -I mirror.gradle androidJvmTest :android:app:testDebugUnitTest :android:app:lintDebug \
  :android:app:assembleDebug :android:app:assembleDebugAndroidTest \
  :android:sender-fixture:assembleDebug               # BUILD SUCCESSFUL, 100 pruebas JVM de la app y 200 de shared, 0 fallos (101 tras la corrección del reloj)
git diff --check                                      # sin salida
```

`verify.ps1` no cambia. Los bloques de R6 y de la lista cerrada de consumidores
del parser se ejecutaron con PowerShell 7.4.6 portátil sobre el árbol real, con
las rutas traducidas a Linux: en verde, y en negativo una subclase de producción
`Rogue : DexcomG7Receiver()` provocó el rechazo de R6. Los ficheros tocados ya
estaban en la lista cerrada.

## Ejecución en dispositivo (2026-10-11)

- SHA probado: `d1708f45ba0ae5008aa49bcbd8465e1353720d50` (rama
  `claude/zealous-lamport-8buvya`; solo documentación cambia después).
- Dispositivo: Pixel 10 Pro Fold (`rango`), Android 17, API 37, build
  `CP3A.260905.009`, único dispositivo autorizado en `adb`.
- Comando: `.\scripts\verify.ps1 -DeviceTests` en Windows, código de salida 0.
  Compilación, pruebas JVM, lint y aislamiento de instrumentación en verde.
- Instrumentación completa: `OK (165 tests)`, sin fallos.
- `DexcomG7ReceiverDeviceTest` 11/11 (incluida la nueva de R11),
  `AndroidDexcomSenderEvidenceTest` 8/8 y `DexcomLogDiagnosticsDeviceTest`
  3/3.
- Las APK sintéticas `org.bolusai.next.senderfixture` y
  `org.bolusai.next.test` se desinstalaron al terminar.

## Pendiente

- Decidir si Diagnóstico muestra un contador duradero de fallos del receiver
  (R10 y R11).
