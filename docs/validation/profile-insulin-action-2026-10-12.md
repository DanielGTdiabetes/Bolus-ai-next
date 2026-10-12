# Validación: IOB candidato y acción de la insulina en el perfil — 2026-10-12

Entregas del [ADR 0026](../adr/0026-candidate-iob-from-dose-history.md) (IOB
candidato en el motor, con la corrección de la duración efectiva de las curvas
tabuladas) y del [ADR 0027](../adr/0027-profile-insulin-action.md) (DIA, curva y
pico en el perfil versionado). Legacy se consultó solo en lectura en
`bcac891d85e6fb3e84398c118061e6aeae3f73f1` (`main` remoto resuelto con
`git ls-remote`), en copias temporales sin push. No se modificaron Legacy,
Dexcom ni la app del reloj. Ningún resultado es utilizable para tratamiento.

## Qué se puede probar

1. Ajustes → Cálculo → editar: sección «Acción de la insulina» tras los límites,
   con su aviso de validación pendiente. Si la versión de partida es anterior,
   aparece el aviso de que su acción es desconocida.
2. Elegir una curva, escribir la DIA en horas y el pico en minutos enteros. Un
   pico con decimales muestra `profile.edit.not_whole_number` y no deja guardar.
3. Guardar crea una versión de esquema 3. El perfil actual, el historial y la
   revisión muestran curva, DIA y pico, «Sin configurar» o «desconocido (versión
   sin acción de la insulina)».
4. Confirmar la versión en la revisión, como hasta ahora.
5. Bolo no cambia: el IOB sigue siendo manual. El IOB calculado aún no tiene
   historial de dosis.

## Comandos ejecutados (entorno en la nube, Linux, sin dispositivo)

| Comando | Resultado |
|---|---|
| `git ls-remote https://github.com/DanielGTdiabetes/bolus_ai.git refs/heads/main` | `bcac891d85e6fb3e84398c118061e6aeae3f73f1` |
| `./gradlew androidJvmTest allMetadataJar :android:app:testDebugUnitTest :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug :android:sender-fixture:lintDebug` | `BUILD SUCCESSFUL`. 400 pruebas JVM, 0 fallos: motor 67, perfil 108, parser Dexcom 47, traductor 25, comidas 17 y Android 136 |
| `python3 -I scripts/legacy-vectors/verify_legacy_vectors.py` | los 12 vectores del ADR 0024 y los 19 del ADR 0026 se reproducen byte a byte; con un vector alterado falla |
| Copia de `scripts/verify.ps1` adaptada a Linux con PowerShell 7.5.2 (`./gradlew`, rutas con `/`, `aapt2` sin `.exe`): Gradle con `clean`, aislamiento de instrumentación sobre los APK reales, manifiestos, anclas, consumidores, pureza del motor, resultados no terapéuticos, curvas del perfil y del motor, plantilla del CI y vectores de Legacy | exit 0: `BUILD SUCCESSFUL`, autoprueba y comprobación de aislamiento superadas, vectores reproducidos |
| Captura con `open()` y salida forzadas a cp1252, como en el runner de Windows (fallo del CI en `add10ee`) | el script anterior falla con `UnicodeDecodeError` en `bolus_engine.py`; con UTF-8 explícito reproduce el fichero exacto |
| Regresión del falso cero (DIA 4,99 h con Fiasp) sobre el motor anterior `a4daa93` | falla con `Computed(units=0.0)`; pasa con la corrección |

Limitaciones: la copia adaptada no se publica y la ejecución completa en Windows
la hace el CI de la PR. Maven Central devolvió 429 en algunos intentos y se
reintentó. No hay KVM, así que no se ejecutó ninguna prueba instrumentada ni se
comprobó la paridad de Walsh en iOS.

## Comprobaciones pendientes del Pixel (no realizadas)

`scripts/verify.ps1 -DeviceTests` incluye ya las clases nuevas.

| ID | Comprobación | Evidencia esperada |
|---|---|---|
| A1 | `ClinicalProfileInsulinActionDeviceTest` | migraciones v1, v2 y v3 → v4 con filas y confirmación intactas y sin curva ni valores; persistencia tras reinicio; triggers; filas manipuladas rechazadas |
| A2 | `ClinicalProfileDeviceTest.insulinActionIsDeclaredOnceWithoutDefaultsAndOlderVersionsStayUnknown` | flujo del editor con recreación, pico fraccionario rechazado y versión de esquema 3 guardada |
| A3 | Pruebas existentes actualizadas a v4 (`ClinicalProfileConfirmationDeviceTest`, `ClinicalProfileLimitsDeviceTest`) | verdes con `clinical-profile.db` v4 |
| A4 | Actualización real: abrir la app sobre el `clinical-profile.db` v3 existente del Pixel | el perfil sigue legible y confirmado como antes; acción desconocida; nada rellenado. Hacer copia del archivo antes |
