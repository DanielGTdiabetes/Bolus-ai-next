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

Limitaciones de esa ejecución en la nube: la copia adaptada no se publica.
Maven Central devolvió 429 en algunos intentos y se reintentó. Sin KVM no se
ejecutaron allí pruebas instrumentadas. La evidencia local de Windows y del
Pixel se registra por separado debajo. La paridad de Walsh en iOS sigue sin
comprobarse.

## Comprobaciones del Pixel

`scripts/verify.ps1 -DeviceTests` incluye ya las clases nuevas.

| ID | Comprobación | Evidencia esperada |
|---|---|---|
| A1 | `ClinicalProfileInsulinActionDeviceTest` | migraciones v1, v2 y v3 → v4 con filas y confirmación intactas y sin curva ni valores; persistencia tras reinicio; triggers; filas manipuladas rechazadas |
| A2 | `ClinicalProfileDeviceTest.insulinActionIsDeclaredOnceWithoutDefaultsAndOlderVersionsStayUnknown` | flujo del editor con recreación, pico fraccionario rechazado y versión de esquema 3 guardada |
| A3 | Pruebas existentes actualizadas a v4 (`ClinicalProfileConfirmationDeviceTest`, `ClinicalProfileLimitsDeviceTest`) | verdes con `clinical-profile.db` v4 |
| A4 | Actualización real: abrir la app sobre el `clinical-profile.db` v3 existente del Pixel | el perfil sigue legible y confirmado como antes; acción desconocida; nada rellenado. Hacer copia del archivo antes |

### Ejecución local en Windows y estado real del Pixel — 2026-10-12

Código validado: PR #60, base `675adea910b7b4d1b6ee1069acaa6bc6698da982`
y correcciones de pruebas `ff6c2f0558067286b514e1244053931a17ab2333`,
sobre `origin/main` `5dc73157b38f55a0143f73fd0a5b5877d86cca6e`. Windows,
PowerShell 7.6.5, JDK 21, SDK 36 y Pixel 10 Pro Fold con API 37. El propietario
autorizó la actualización y las pruebas tras aclarar que solo afectan a Next.
Legacy `main` se revalidó en solo lectura:
`bcac891d85e6fb3e84398c118061e6aeae3f73f1`.

La precomprobación contradijo el supuesto de A4: la base instalada era **v2 y
estaba vacía**, sin versiones de perfil ni eventos de confirmación. No había
un perfil real v3 confirmado que se pudiera conservar y comprobar visualmente.
No se crearon valores ni confirmaciones para aparentar esa evidencia.

Se detuvo solo Next y se copiaron la base y los auxiliares realmente presentes
fuera del repositorio, bajo `%USERPROFILE%\bolus-next-backups\profile-pr60-<UTC>`.
Cada archivo coincidió en SHA-256 y tamaño con el original; una copia de
comprobación devolvió `integrity_check = ok` y `user_version = 2`. Se conservó
también la APK anterior y se verificó que su certificado de firma coincidía
con el de la APK nueva antes de `adb install -r`.

La actualización real **v2 vacía → v4** pasó: integridad correcta, todas las
filas originales y sus huellas conservadas y tablas nuevas sin valores
rellenados. Se abrió Ajustes → Cálculo sin editar ni confirmar datos. Esta
prueba no acredita el criterio original de A4 con un perfil real confirmado;
ese caso sigue pendiente. A1 comprueba por separado migraciones con perfiles
y confirmaciones sintéticos desde v1, v2 y v3.

| Comando o comprobación | Resultado |
|---|---|
| `./gradlew.bat --no-daemon :android:app:assembleDebug` | `BUILD SUCCESSFUL`; APK usada para la actualización real |
| `./scripts/verify.ps1` | exit 0; 401 pruebas JVM, 0 fallos, 0 errores y 0 omitidas; lint, compilación, aislamiento, arquitectura y ambos ficheros de vectores de Legacy reproducidos |
| Copia previa y comparación tras actualizar | hashes y tamaños iguales; integridad `ok`; v2 → v4; filas conservadas; sin relleno |
| `./scripts/verify.ps1 -DeviceTests`, tras las correcciones y con el receptor de Next en el estado requerido por el arnés | exit 0; 401 pruebas JVM en 47 suites, 0 fallos, 0 errores y 0 omitidas; `OK (181 tests)` en el Pixel; lint, compilación, aislamiento y vectores incluidos, sin `-SkipLegacyVectors` |
| Comparación de la base real tras las pruebas | integridad `ok`, v4, todas las filas y huellas originales conservadas, sin relleno de límites ni acción |

La primera ejecución completa con `-DeviceTests` terminó con 181 pruebas y
9 fallos. Cinco compartían un error de la comparación de filas: la tabla nueva
`profile_insulin_curve` tiene dos columnas, pero el helper ordenaba siempre
por tres. Ahora ordena por todas las columnas existentes. Otro caso fijaba v4
como «versión futura», aunque ya es la soportada; ahora usa la versión creada
más uno y sigue exigiendo rechazo sin modificarla.

Los otros tres fallos eran de diagnóstico/receptor: Next tenía su receptor
habilitado para la recepción real, pero estas pruebas esperan el estado
predeterminado del manifiesto. Para repetir se aplicó únicamente a Next
`adb shell run-as org.bolusai.next pm default-state --user 0
org.bolusai.next/.glucose.dexcom.DexcomG7Receiver`. Al terminar se restauró
su estado habilitado anterior con `pm enable`; `dumpsys package` confirmó el
componente en `enabledComponents` y el permiso Dexcom de Next concedido. Esa
restauración también es necesaria si la ejecución falla.
No se cambiaron expectativas de diagnóstico ni código de producción para
hacer pasar estas pruebas.

A1 pasó 4/4 (`ClinicalProfileInsulinActionDeviceTest`); A2 pasó dentro de
`ClinicalProfileDeviceTest` (20/20); A3 pasó con confirmación 14/14 y límites
4/4. Se retiraron los dos paquetes sintéticos de pruebas y se abrió Next de
nuevo. **A4 con perfil real v3 confirmado sigue pendiente**, al igual que la
revisión explícita del propietario y la integración en `main`.

La revisión técnica abarca motor IOB, aritmética de la ventana, perfil/codec,
migraciones, UI y arnés. No equivale a aprobación clínica ni integra la PR.
El IOB de Bolo sigue siendo manual. No se modificaron ni detuvieron Legacy,
Dexcom G7 ni la app del reloj; no se cambiaron Bluetooth ni los ajustes de red.

### Copia verificada antes de A4

Orden: copia verificada → actualización sin desinstalar y A4 → A1 a A3 con
`scripts/verify.ps1 -DeviceTests` → revisión e integración. La copia contiene
datos clínicos reales: va fuera del repositorio y nunca se adjunta a commits,
incidencias ni registros.

Ejecutar en PowerShell 7.4 o posterior, que conserva los bytes al redirigir
`adb exec-out`. Windows PowerShell 5.1 puede corromper la copia.

```powershell
if ($PSVersionTable.PSVersion -lt [version]"7.4") { throw "Usa PowerShell 7.4 o posterior" }
$backup = Join-Path $env:USERPROFILE "bolus-next-backups\profile-$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Path $backup | Out-Null
adb shell am force-stop org.bolusai.next
# Lista real de ficheros: solo se omite un auxiliar cuya ausencia queda confirmada aquí.
$listing = adb shell run-as org.bolusai.next ls databases
if ($LASTEXITCODE -ne 0) { throw "run-as falló: no instalar nada y revisar" }
$files = @("clinical-profile.db", "clinical-profile.db-wal", "clinical-profile.db-shm", "clinical-profile.db-journal") |
    Where-Object { $listing -contains $_ }
if ($files -notcontains "clinical-profile.db") { throw "No existe clinical-profile.db" }
foreach ($file in $files) {
    adb exec-out run-as org.bolusai.next cat "databases/$file" > (Join-Path $backup $file)
    if ($LASTEXITCODE -ne 0) { throw "Copia fallida: $file" }
    $remote = ((adb shell run-as org.bolusai.next sha256sum "databases/$file") -split "\s+")[0]
    $local = (Get-FileHash -Algorithm SHA256 (Join-Path $backup $file)).Hash.ToLower()
    if ($remote -ne $local) { throw "Hash distinto en $file" }
    Write-Output "$file $local $((Get-Item (Join-Path $backup $file)).Length) bytes"
}
# Apertura íntegra sobre una copia de comprobación con sus auxiliares; la copia original no se toca.
$check = "$backup-check"
Copy-Item -Recurse $backup $check
python -I -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); r=c.execute('PRAGMA integrity_check').fetchall(); v=c.execute('PRAGMA user_version').fetchone()[0]; print(r, 'user_version', v); sys.exit(0 if r==[('ok',)] and v==3 else 1)" (Join-Path $check "clinical-profile.db")
if ($LASTEXITCODE -ne 0) { throw "La copia no está íntegra o no es v3: no instalar" }
```

Solo con hashes iguales, `integrity_check` en `ok` y `user_version` 3 se
instala la APK de la PR encima, sin desinstalar. Restaurar es copiar de vuelta
los mismos ficheros con la app detenida.
