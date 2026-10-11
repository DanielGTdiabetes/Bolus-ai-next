# Receiver Dexcom G7 desactivado por defecto — 2026-10-11

Base Next: `f307b3e00d99b9437ab74b4f1936d72e832060d4` (PR #50). Rama
`claude/optimistic-keller-x7t53l`. Implementa el
[ADR 0022](../adr/0022-dexcom-g7-receiver-disabled.md). Legacy no se consulta
ni se modifica.

Incremento preparatorio. No valida la recepción con Dexcom real: siguen
pendientes la release reproducible del productor, su opt-in de identidad, las
anclas aprobadas y el permiso del productor.

## Pruebas

| Clase | Pruebas | Resultado |
|---|---|---|
| `DexcomG7IntakeTest` (JVM Android) | 11 | ejecutada, 0 fallos |
| `DexcomLogBlockTest` (JVM Android) | 3 | actualizada con las líneas del receiver, ejecutada, 0 fallos |
| `DexcomG7ReceiverDeviceTest` (dispositivo) | 8 | compilada, en la lista de `verify.ps1`; **sin ejecutar**: no hay dispositivo en la sesión |
| `AndroidDexcomSenderEvidenceTest` (dispositivo) | 8 | una prueba actualizada: el único receiver es el de Dexcom, desactivado; **sin ejecutar** |
| `DexcomLogDiagnosticsDeviceTest` (dispositivo) | 3 | dos pruebas actualizadas con las líneas del receiver; **sin ejecutar** |

`DexcomG7ReceiverDeviceTest` usa la APK sintética `org.bolusai.next.senderfixture`
con la orden nueva `TRIGGER_G7`, que reenvía a Next un payload definido por la
prueba con la acción observada. La política aprobada de las pruebas se calcula
con las huellas del propio fixture y solo existe en la prueba. La base es un
fichero sintético (`dexcom-receiver-device-test.db`) borrado antes y después.
El estado del componente se restaura a `COMPONENT_ENABLED_STATE_DEFAULT` al
terminar cada prueba.

## Comandos y resultados (contenedor Linux de la sesión)

```bash
./gradlew --no-daemon clean androidJvmTest allMetadataJar :android:app:testDebugUnitTest \
  :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest \
  :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug \
  :android:sender-fixture:lintDebug          # BUILD SUCCESSFUL, 205 tareas
pwsh blocks.ps1                              # bloques ADR 0018-0022 de verify.ps1 con rutas Linux: OK
pwsh iso.ps1                                 # Assert-InstrumentationIsolation sobre las APK: OK
git diff --check                             # sin salida
```

Maven Central devolvió 429. Gradle se ejecutó con un script de inicialización
local, fuera del repositorio, que usa el espejo de Google de Maven Central.

Las comprobaciones nuevas de `verify.ps1` se probaron también en negativo sobre
copias del manifiesto fusionado de release: receiver activado, con
`android:permission`, `uses-permission` de Dexcom, categoría extra, segundo
receiver, otra acción y segundo `intent-filter`. Las siete fallan con el
mensaje del ADR 0022. Política distinta de `Pending`, subclase del receiver y
`DexcomSenderTrustPolicy.Approved(` en producción también fallan.

## Revisión de Codex en la PR #51

- P2, confirmado: el reloj se leía en el hilo de escritura, de modo que una
  escritura encolada sellaba la hora de proceso y no la de recepción.
  `DexcomG7Intake.accept` lee ahora el reloj dentro de `onReceive`, solo para un
  mensaje autenticado, y `record` usa ese instante fijo. Regresión:
  `the reception instant is captured on accept, not when a queued write runs`.
  Las pruebas de ignorado y rechazo usan un reloj que falla si se lee.
- P1, prueba en modo avión: pendiente de decisión del propietario. Ver la PR.

## Pendiente

- Ejecutar `.\scripts\verify.ps1 -DeviceTests` en el Pixel (propietario).
- Revisar R4 a R9 (propietario).
- Para la validación real: release reproducible, opt-in de identidad, anclas
  aprobadas, permiso del productor y prueba en modo avión, con su propio ADR.
