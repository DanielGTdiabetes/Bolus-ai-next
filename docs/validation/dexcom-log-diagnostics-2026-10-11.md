# Vista del registro Dexcom en Diagnóstico — 2026-10-11

Base Next: `39ec9f99bc7e5281c95d49bf99e08272ddb3e080` (PR #46, ADR 0020). Rama
`claude/brave-pascal-ki8rt0`. Implementa el
[ADR 0021](../adr/0021-dexcom-log-diagnostics-view.md). Legacy no se consulta
ni se modifica.

## Pruebas

| Clase | Pruebas | Resultado |
|---|---|---|
| `ObservationSummaryTest` (JVM común) | 3 | ejecutada, 0 fallos |
| `DexcomLogBlockTest` (JVM Android) | 3 | ejecutada, 0 fallos |
| `DexcomLogDiagnosticsDeviceTest` (dispositivo) | 3 | compilada; **pendiente de ejecutar** en el Pixel |

## Comandos y resultados (contenedor Linux de la sesión)

```bash
./gradlew --no-daemon clean androidJvmTest allMetadataJar :android:app:testDebugUnitTest \
  :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest \
  :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug \
  :android:sender-fixture:lintDebug          # rc=0
pwsh check.ps1                               # bloque ADR 0018-0021 de verify.ps1 con rutas Linux: OK
git diff --check                             # sin salida
```

## Pendiente

`verify.ps1 -DeviceTests` en el Pixel: `DexcomLogDiagnosticsDeviceTest`,
`SqliteDexcomObservationStoreDeviceTest` y `DexcomBundleExtrasDeviceTest`.
Revisión visual de la tarjeta en tema claro y oscuro.
