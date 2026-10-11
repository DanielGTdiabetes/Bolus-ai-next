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
| `SqliteDexcomObservationStoreDeviceTest` (dispositivo) | 8 | ejecutadas en el Pixel junto con la novena, 0 fallos (ver «Ejecución en dispositivo») |

Recuento posterior: la PR #47 (ADR 0021, commit `4557c7e`) añadió
`storageSummaryEqualsTheReferenceWithoutLoadingTheHistory` a esta clase. Desde
entonces existen 9 pruebas en `SqliteDexcomObservationStoreDeviceTest`. Las 8 de
la tabla son las de esta entrega.

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

## Ejecución en dispositivo (2026-10-11)

- SHA probado: `cacc8a905f0e3977d9d4fa44f35f7fdb0752dcd3` (merge de la PR #47,
  igual a `origin/main` en ese momento).
- Dispositivo: Pixel 10 Pro Fold (`rango`), Android 17, API 37, build
  `CP3A.260905.009`. Era el único dispositivo en `adb devices`.
- Comando: `.\scripts\verify.ps1 -DeviceTests` en Windows. Salida 0, con Gradle
  `BUILD SUCCESSFUL` (205 tareas), comprobaciones de arquitectura y autoprueba
  de aislamiento de instrumentación correctas e instrumentación `OK (146 tests)`.
- `SqliteDexcomObservationStoreDeviceTest`: 9 existentes, 9 ejecutadas, 9
  superadas. Son las 8 de esta entrega y
  `storageSummaryEqualsTheReferenceWithoutLoadingTheHistory` (ADR 0021). Las
  pruebas usan un fichero sintético y no abren `dexcom-observations.db` de la
  app.

## Pendiente

- ADR de retención (E4), receiver productivo, anclas, release reproducible,
  vigencia y prueba en modo avión. R-010 y R-014 siguen abiertos.
