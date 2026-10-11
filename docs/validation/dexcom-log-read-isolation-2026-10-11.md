# Lectura de solo consulta y aislamiento del registro Dexcom — 2026-10-11

Base Next: `9871bb4c84f7e2dc26e7c1bc92024d4e582b04f5` (PR #49). Rama
`claude/dexcom-log-read-isolation`. Corrige los dos hallazgos P2 de la revisión
independiente de Codex sobre `403f84e`. Es una precisión técnica de F4 del
[ADR 0021](../adr/0021-dexcom-log-diagnostics-view.md), en su sección
«Seguimiento de la revisión independiente del 2026-10-11». Legacy no se consulta
ni se modifica.

## Qué cambia

- `SqliteDexcomObservationStore.summaryOfExisting()`: comprueba
  `user_version == 1` y el esquema exacto en una conexión `OPEN_READONLY` antes
  de abrir el helper. Si no son los esperados devuelve
  `dexcom_observation.unsupported_schema`, o `corrupt_storage` si el fichero no
  es SQLite, y no inicializa ni reescribe nada. El manejador de errores propio,
  que nunca borra el fichero, se usa también en esa conexión.
- `readDexcomObservationLog(context, name)`: usa `summaryOfExisting()` y acepta
  un nombre sintético para pruebas.
- `DexcomStorageGuard` (nuevo): barrera de instrumentación sobre
  `dexcom-observations.db`, con el mismo patrón que `ProfileStorageGuard`. La
  activa `ProfileIsolationTestRunner` y la comprueban el almacén, en su `init`,
  y el lector de Diagnóstico.
- `NavigationDeviceTest` y `BolusProfileDetailsDeviceTest` inyectan un lector
  sintético (`SyntheticDexcomLog.kt`).
- `verify.ps1`: activación de la barrera en el runner, comprobación de la
  barrera en el almacén y en el lector, lector limitado a `summaryOfExisting()`,
  inyección obligatoria en toda prueba que lance `MainActivity` y visite
  Diagnóstico, y nueva clase en la lista `-e class`.

Sin cambios de esquema, códigos, UI, receiver, motor, perfil ni comidas.

## Pruebas

| Clase | Pruebas | Resultado |
|---|---|---|
| `DexcomLogIsolationDeviceTest` (dispositivo) | 8 | 8 existentes, 8 ejecutadas en el Pixel, 0 fallos |
| `NavigationDeviceTest` (dispositivo, modificada) | 7 | 7 ejecutadas en el Pixel, 0 fallos |
| `BolusProfileDetailsDeviceTest` (dispositivo, modificada) | 8 | 8 ejecutadas en el Pixel, 0 fallos |

Casos de `DexcomLogIsolationDeviceTest`, todos con ficheros sintéticos en una
carpeta temporal y nunca con el fichero de la app:

- el runner activa la barrera;
- el registro «real» de la carpeta se rechaza con
  `dexcom_storage.real_database_denied_in_instrumentation`, desde el lector y
  desde el almacén y con rutas equivalentes, sin cambiar sus bytes ni crear
  nada;
- un registro ausente se lee vacío y no se crea;
- un fichero de cero bytes da `unsupported_schema` y sigue con cero bytes;
- una base vacía v0 da `unsupported_schema`, conserva sus bytes y no recibe
  tablas;
- una tabla ajena o `user_version = 2` dan `unsupported_schema` y conservan sus
  bytes;
- un fichero que no es SQLite da `corrupt_storage` y no se borra;
- un registro v1 válido da los mismos recuentos que
  `ObservationSummary.of(read())`.

## Comandos y resultados (Windows)

```powershell
.\scripts\verify.ps1      # salida 0: BUILD SUCCESSFUL, comprobaciones y autoprueba de aislamiento correctas
git diff --check          # sin salida
```

Prueba negativa de la comprobación estática nueva: en una copia de
`NavigationDeviceTest` sin la línea `dexcomLogReader = {`, el patrón de
`verify.ps1` marca ese fichero.

## Ejecución en dispositivo (2026-10-11)

- SHA probado: `2839a57a7aff616349a889c7d0d0483bfc8a71e7` (rama
  `claude/dexcom-log-read-isolation`; solo documentación cambia después).
- Dispositivo: Pixel 10 Pro Fold (`rango`), Android 17, API 37, build
  `CP3A.260905.009`. Era el único dispositivo en `adb devices`, despierto y
  desbloqueado, sin otra instrumentación en curso.
- Comando: `.\scripts\verify.ps1 -DeviceTests` en Windows. Salida 0, con Gradle
  `BUILD SUCCESSFUL` (205 tareas), comprobaciones de arquitectura y autoprueba
  de aislamiento correctas e instrumentación `OK (154 tests)` (146 anteriores y 8
  nuevas).
- `DexcomLogIsolationDeviceTest` 8/8: `theRunnerActivatesTheGuardBeforeAnyTest`,
  `theRealLogIsRejectedBeforeItIsOpenedOrLookedAt`,
  `aMissingLogReadsAsEmptyWithoutBeingCreated`,
  `aZeroByteFileIsRejectedAndNotInitialised`,
  `anEmptyVersionZeroDatabaseIsRejectedAndNotInitialised`,
  `anUnsupportedSchemaOrVersionIsRejectedWithoutTouchingTheFile`,
  `aFileThatIsNotSqliteIsCorruptionAndIsKept` y
  `aValidLogIsCountedLikeTheReference`.

## Pendiente

- No se ejecutaron las regresiones contra el código anterior para demostrar que
  fallaban. El razonamiento es el siguiente: con `readableDatabase`, los casos
  de cero bytes y v0 pasan por `onCreate`, y las barreras no existían.
