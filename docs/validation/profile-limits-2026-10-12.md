# Validación: límites del cálculo en el perfil — 2026-10-12

Entrega del [ADR 0025](../adr/0025-profile-calculation-limits.md). Legacy se
consultó solo en lectura, en `bcac891d85e6fb3e84398c118061e6aeae3f73f1` (`main`
remoto resuelto con `git ls-remote`), desde un clon parcial separado con el push
deshabilitado. No se modificaron Legacy, Dexcom ni la app del reloj.

## Qué se puede probar

1. Ajustes → Cálculo → editar: sección «Límites del cálculo» con paso de
   redondeo, bolo máximo y corrección máxima en U. Si la versión de partida es
   anterior, aparece el aviso de que sus límites son desconocidos.
2. Guardar crea una versión nueva de esquema 2. El perfil actual, el historial y
   la revisión muestran cada límite, «Sin configurar» o «desconocido (versión sin
   límites)».
3. Confirmar la versión en la revisión, como hasta ahora.
4. Bolo → Cálculo de prueba: ya no hay campos de límites. Se muestran en solo
   lectura con la versión y su confirmación, y el cálculo los usa sin
   teclearlos. Si se cambia un límite en Perfil, el resultado anterior se descarta.

## Comandos ejecutados (entorno en la nube, Linux, sin dispositivo)

| Comando | Resultado |
|---|---|
| `git ls-remote https://github.com/DanielGTdiabetes/bolus_ai.git refs/heads/main` | `bcac891d85e6fb3e84398c118061e6aeae3f73f1` |
| `./gradlew --no-daemon --stacktrace clean androidJvmTest allMetadataJar :android:app:testDebugUnitTest :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug :android:sender-fixture:lintDebug`, la misma lista de `verify.ps1` | `BUILD SUCCESSFUL`. 374 pruebas, 0 fallos: motor 52, perfil 98, parser Dexcom 47, comidas 17, traductor 25 y Android JVM 135 |
| Comprobaciones estáticas de `scripts/verify.ps1` con PowerShell 7.4.6: aislamiento de instrumentación sobre los APK reales, manifiestos, anclas, consumidores y pureza del motor | exit 0 |

Limitaciones del entorno: `verify.ps1` usa `gradlew.bat`, rutas con `\` y
`aapt2.exe`. Se ejecutó una copia temporal adaptada a Linux que no se publica,
con Gradle aparte sobre el mismo árbol. La ejecución completa en Windows la hace
el CI de la PR. Maven Central devolvió 429 y las dependencias se descargaron de
su mirror oficial de Google mediante un init script local fuera del repositorio.
No hay KVM, así que no se ejecutó ninguna prueba instrumentada.

## Comprobaciones pendientes del Pixel (no realizadas)

Estado histórico de esta entrega. Resultados posteriores y pendientes vigentes
en la [validación del Pixel](pixel-dexcom-and-calculation-2026-10-12.md).

Amplían las de la [validación anterior](dexcom-permission-reception-and-test-calculation-2026-10-12.md).

| ID | Comprobación | Evidencia esperada |
|---|---|---|
| P1 | `scripts/verify.ps1 -DeviceTests` con el APK de esta PR | `OK` incluidas `ClinicalProfileLimitsDeviceTest` (migraciones v1 y v2 → v3, persistencia, triggers, filas manipuladas), `ClinicalProfileDeviceTest.limitsAreTypedOnceSavedInANewVersionAndOlderVersionsStayUnknown` y las pruebas de migración y definiciones actualizadas a v3 |
| P6 | Cálculo de prueba en Bolo tras introducir los límites en Perfil y confirmar la versión | Límites en solo lectura con la versión; total calculado sin teclearlos; al cambiar un límite y guardar, el resultado anterior aparece descartado |
| P7 | Actualizar la app instalada sin borrar datos (`adb install -r`) sobre un `clinical-profile.db` v2 existente | El perfil y su confirmación siguen igual. Los límites aparecen como «desconocido (versión sin límites)» y el cálculo de prueba bloquea solo B2, D y E con `profile.*.not_in_schema` |

## Riesgos y pendientes

- Los valores de los límites los decide el usuario. No hay rangos aprobados
  (LC-009) y paso `0` bloquea el redondeo.
- Las reglas del motor siguen siendo candidatas (LC-006 a LC-011), con IOB
  manual. I-3 y B1 siguen pendientes del Pixel y del informe.
- Rollback: restaurar una copia del archivo v2 o v1 anterior. Un build antiguo
  no abre un archivo v3 (falla cerrado).
