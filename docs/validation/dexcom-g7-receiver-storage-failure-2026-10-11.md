# Fallos de almacenamiento del receiver Dexcom G7 — 2026-10-11

Base Next: `ccfb753` (merge de la PR #51). Rama
`claude/loving-maxwell-qu9me8`. Añade R10 al
[ADR 0022](../adr/0022-dexcom-g7-receiver-disabled.md). Legacy no se consulta
ni se modifica.

## Problema

En `DexcomG7Receiver.onReceive`, la escritura encolada ejecutaba
`intake.record(step) { openStore(application) }` dentro de `try/finally` sin
`catch`. Abrir o escribir el registro puede lanzar:
`SQLiteDatabaseCorruptException` (manejador de errores del almacén),
`DexcomObservationSchema.Mismatch` (inventario, definición, upgrade, downgrade,
versión, objetos ajenos), `IllegalStateException` de `DexcomStorageGuard`
(`REAL_DATABASE_DENIED`) o de los `check()` de relectura, y `CorruptRow`.
`SqliteDexcomObservationStore.record` ya convierte en `ObservationWrite.Failed`
lo que lanza dentro de su `try`, pero no la barrera de su constructor, ni el
cierre, ni lo que lance otra implementación del puerto. La excepción llegaba al
`UncaughtExceptionHandler` del hilo del ejecutor y Android cerraba el proceso.
Inalcanzable hoy porque la política de producción es `Pending`.

## Cambio

- `DexcomG7Intake.record` devuelve `DexcomReceipt` y captura por separado
  apertura, escritura y cierre. La primera etapa que falla da
  `DexcomReceipt.StorageFailed(DexcomStorageFailure, payloadReads)`:
  `dexcom_receiver.store_open_failed`, `dexcom_receiver.store_record_failed` o
  `dexcom_receiver.store_close_failed`.
- El recibo no guarda el texto de la excepción ni datos del payload.
- Sin reintento ni descarte silencioso. El almacén abierto se cierra siempre.
  `pending.finish()` sigue en el `finally` del receiver.
- `StorageFailed.glucose` es `input.glucose.persistence_failed`. Ningún recibo
  hace disponible la glucosa.
- Solo se captura `Exception`. Un `Error` de la JVM no se oculta.
- Política y lógica clínica sin cambios.

## Diagnóstico

No se añade línea. Un contador en memoria moriría con el proceso que atendió
el broadcast y Diagnóstico mostraría `0` después de fallos reales. Un contador
duradero necesita otro fichero (el registro que falló no puede guardarlo) y
queda como decisión pendiente del propietario.

## Pruebas

| Clase | Pruebas | Resultado |
|---|---|---|
| `DexcomG7IntakeTest` (JVM Android) | 15 | 4 nuevas y 1 sustituida (`store defects propagate unchanged` contradecía R10), ejecutada, 0 fallos |
| Resto de `org.bolusai.next.glucose.dexcom.*` y `DexcomLogBlockTest` (JVM Android) | 42 | ejecutadas, 0 fallos |
| `DexcomG7ReceiverDeviceTest` (dispositivo) | 10 | 2 nuevas, compilada. **Sin ejecutar**: no hay dispositivo en la sesión |

Pruebas nuevas en JVM:

- `a store that cannot be opened is a storage failure, not an uncaught exception`
  (excepciones del mismo tipo que la barrera, el esquema y E/S)
- `a record that throws is a storage failure and the store is still closed`
- `the first failing stage wins and a failed close is never reported as recorded`
- `storage failure codes are stable`
- `jvm errors are not swallowed`
- `no receipt makes glucose available` amplía su lista con los dos fallos.

Pruebas nuevas en dispositivo:

- `aStoreThatFailsToOpenIsDeliveredAsStorageFailedAndTheProcessSurvives`: el
  almacén sintético lanza al abrir, llega `StorageFailed(OPEN_FAILED)`, no se
  crea la base sintética y el mismo proceso recibe y guarda el mensaje
  siguiente.
- `theBarrierDenyingTheRealLogIsAStorageFailureAndTheRealLogIsUntouched`: la
  sonda pide el fichero real, `DexcomStorageGuard` lo niega antes de SQLite y
  llega `StorageFailed(OPEN_FAILED)` sin crear el registro real.

## Comandos y resultados (contenedor Linux de la sesión)

```bash
./gradlew -I mirror.gradle :android:app:testDebugUnitTest \
  --tests 'org.bolusai.next.glucose.dexcom.*' --tests 'org.bolusai.next.DexcomLogBlockTest' \
  :android:app:compileDebugAndroidTestKotlin          # BUILD SUCCESSFUL
./gradlew -I mirror.gradle androidJvmTest :android:app:testDebugUnitTest :android:app:lintDebug \
  :android:app:assembleDebug :android:app:assembleDebugAndroidTest \
  :android:sender-fixture:assembleDebug               # BUILD SUCCESSFUL, 92 pruebas JVM de la app, 0 fallos
git diff --check                                      # sin salida
```

Maven Central devolvió 429. `mirror.gradle` es un script de inicialización
local, fuera del repositorio, que usa el espejo de Google de Maven Central.
`verify.ps1` no cambia: no hay PowerShell en el contenedor y los bloques del
ADR 0022 no necesitan una comprobación nueva, porque las pruebas JVM, que
`verify.ps1` ya ejecuta, fijan R10.

## Pendiente

- Ejecutar `.\scripts\verify.ps1 -DeviceTests` en el Pixel (propietario):
  `DexcomG7ReceiverDeviceTest`, `AndroidDexcomSenderEvidenceTest` y
  `DexcomLogDiagnosticsDeviceTest` siguen sin ejecutarse desde la PR #51.
- Decidir si Diagnóstico muestra un contador duradero de fallos de
  almacenamiento.
- `DexcomG7Intake.accept` corre en el hilo principal y deja propagar defectos
  inesperados de autenticación o parser (ADR 0019). No forma parte de este
  cambio.
