# Composición de recepción Dexcom G7 — 2026-10-11

Base Next: `85f051d0e38f1e9c68418f95ddc16937b47699af` (PR #44, ADR 0018). Rama
`claude/brave-pascal-ki8rt0`. Implementa el
[ADR 0019](../adr/0019-dexcom-g7-reception-composition.md). Legacy no se
consulta ni se modifica.

## Qué existe tras esta entrega

- `glucose/dexcom/DexcomPayloadExtras.kt`: `ExtrasView`, `DexcomExtrasRead`,
  `DexcomPayloadExtras`, `BundleExtrasView` e `Intent.readDexcomPayload()`.
- `glucose/dexcom/DexcomG7Reception.kt`: `DexcomG7Reception` y
  `DexcomG7ReceptionOutcome`.
- `android/app/build.gradle.kts`: dependencia de `:shared:dexcom-g7-payload`.
- `verify.ps1`: lista cerrada de usuarios del parser y de la composición, y la
  nueva clase de instrumentación.

Sin receiver, permisos, manifiestos, SQLite, UI, motor, perfil ni comidas
nuevos. `PendingDexcomSource` sigue devolviendo `policy_not_approved`.

## Pruebas

| Clase | Pruebas | Resultado |
|---|---|---|
| `DexcomPayloadExtrasTest` (JVM Android) | 5 | ejecutada, 0 fallos |
| `DexcomG7ReceptionTest` (JVM Android) | 7 | ejecutada, 0 fallos |
| `DexcomBundleExtrasDeviceTest` (dispositivo) | 3 | compilada; **pendiente de ejecutar** en el Pixel |

## Comandos y resultados (contenedor Linux de la sesión)

Android SDK 36 instalado en el directorio temporal de la sesión y PowerShell
7.4.6 portátil.

```bash
./gradlew --no-daemon clean androidJvmTest allMetadataJar :android:app:testDebugUnitTest \
  :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest \
  :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug \
  :android:sender-fixture:lintDebug          # mismas tareas Gradle que verify.ps1
pwsh check.ps1                               # bloque ADR 0018/0019 de verify.ps1 con rutas Linux
git diff --check                             # sin salida
```

El bloque de comprobaciones de arquitectura se ejecutó con rutas adaptadas a
Linux: pasa con el árbol actual, falla si `MainActivity` menciona el parser y
falla si el módulo del parser importa `android.`. `verify.ps1` completo corre
en el CI de Windows de la PR.

## Pendiente

- `verify.ps1 -DeviceTests` en el Pixel del propietario para
  `DexcomBundleExtrasDeviceTest`. La APK solo añade clases sin uso: no cambia
  pantallas ni manifiesto.
- Prueba en dispositivo de `BadParcelableException` (ver ADR 0019).
- Receiver productivo, anclas, release reproducible, persistencia, vigencia y
  prueba en modo avión. R-010 y R-014 siguen abiertos.
