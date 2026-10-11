# Registro local de observaciones Dexcom G7 — 2026-10-11

Base Next: `ec6ea6cd8add390e9937d308cc91f5c3af23feac` (PR #45, ADR 0019). Rama
`claude/brave-pascal-ki8rt0`. Implementa el
[ADR 0020](../adr/0020-dexcom-g7-observation-log.md) con E1 a E4 del
propietario y E5 a E9 técnicas. Legacy no se consulta ni se modifica.

## Qué existe tras esta entrega

- `shared/dexcom-g7-payload/.../ObservationLog.kt`: modelo, política pura y
  puerto del registro.
- `android/.../glucose/dexcom/storage/`: `DexcomObservationSchema`,
  `SqliteDexcomObservationStore` (`dexcom-observations.db` v1),
  `DexcomG7Recorder`, `EpochClock` y `AndroidTransmitterHasher`.
- `verify.ps1`: lista cerrada ampliada y nueva clase de instrumentación.

Sin receiver, permisos, manifiesto, UI ni consumidor. `PendingDexcomSource`
sigue devolviendo `policy_not_approved`.

## Pruebas

| Clase | Pruebas | Resultado |
|---|---|---|
| `ObservationPolicyTest` (JVM común) | 13 | ejecutada, 0 fallos |
| `DexcomG7RecorderTest` (JVM Android) | 5 | ejecutada, 0 fallos |
| `SqliteDexcomObservationStoreDeviceTest` (dispositivo) | 8 | compilada; **pendiente de ejecutar** en el Pixel |

## Comandos y resultados (contenedor Linux de la sesión)

Android SDK 36 en el directorio temporal de la sesión y PowerShell 7.4.6
portátil. Sin KVM: no hay emulador.

```bash
./gradlew --no-daemon clean androidJvmTest allMetadataJar :android:app:testDebugUnitTest \
  :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest \
  :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug \
  :android:sender-fixture:lintDebug          # mismas tareas Gradle que verify.ps1
pwsh check.ps1                               # bloque ADR 0018-0020 de verify.ps1 con rutas Linux
git diff --check                             # sin salida
```

## Pendiente

- `verify.ps1 -DeviceTests` en el Pixel para `SqliteDexcomObservationStoreDeviceTest`
  y `DexcomBundleExtrasDeviceTest`. Las pruebas usan un fichero sintético y no
  abren `dexcom-observations.db` de la app.
- ADR de retención (E4), receiver productivo, anclas, release reproducible,
  vigencia y prueba en modo avión. R-010 y R-014 siguen abiertos.
