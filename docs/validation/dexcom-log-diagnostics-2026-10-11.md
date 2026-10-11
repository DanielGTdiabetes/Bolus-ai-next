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
| `DexcomLogDiagnosticsDeviceTest` (dispositivo) | 3 | 3 existentes, 3 ejecutadas en el Pixel, 0 fallos (ver «Ejecución en dispositivo») |

## Comandos y resultados (contenedor Linux de la sesión)

```bash
./gradlew --no-daemon clean androidJvmTest allMetadataJar :android:app:testDebugUnitTest \
  :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest \
  :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug \
  :android:sender-fixture:lintDebug          # rc=0
pwsh check.ps1                               # bloque ADR 0018-0021 de verify.ps1 con rutas Linux: OK
git diff --check                             # sin salida
```

## Ejecución en dispositivo (2026-10-11)

- SHA probado: `cacc8a905f0e3977d9d4fa44f35f7fdb0752dcd3` (merge de la PR #47,
  igual a `origin/main` en ese momento).
- Dispositivo: Pixel 10 Pro Fold (`rango`), Android 17, API 37, build
  `CP3A.260905.009`. Era el único dispositivo en `adb devices`.
- Comando: `.\scripts\verify.ps1 -DeviceTests` en Windows. Salida 0, con Gradle
  `BUILD SUCCESSFUL` (205 tareas), comprobaciones de arquitectura y autoprueba
  de aislamiento de instrumentación correctas e instrumentación `OK (146 tests)`.
- `DexcomLogDiagnosticsDeviceTest`: 3/3 superadas:
  `showsFoldedCountsWithoutAnyGlucoseValue`,
  `onlyDiagnosticsReadsTheLogAndEachEntryReadsAgain` y
  `aFailedReadShowsItsCode`.
- En la misma ejecución, `SqliteDexcomObservationStoreDeviceTest` superó 9/9 y
  `DexcomBundleExtrasDeviceTest` 3/3.

## Pendiente

Revisión visual de la tarjeta en tema claro y oscuro.
