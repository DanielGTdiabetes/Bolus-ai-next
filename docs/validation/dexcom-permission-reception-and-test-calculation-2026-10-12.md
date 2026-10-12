# Validación: recepción Dexcom por permiso, presentación y cálculo de prueba — 2026-10-12

Entrega del [ADR 0023](../adr/0023-dexcom-existing-transport-trust.md) (plan,
pasos 1 a 6) y del [ADR 0024](../adr/0024-candidate-engine-test-calculation.md).
Este informe describe lo que se ha ejecutado en la nube y en el CI de Windows. El
teléfono no se ha tocado: las comprobaciones del Pixel aparecen como pendientes,
sin afirmar que estén hechas.

## Alcance implementado

| Paso del ADR 0023 | Implementación |
|---|---|
| 1. Evidencia por permiso | `ApprovedPermissionSet` (versión de política y huella de titulares) frente a `AuthenticatedSender`; `parseUnverifiedSender` no compara ni guarda el `packageName`; `dexcom-observations.db` v2 con columna `provenance` y migración solo desde un v1 exacto y vacío. Un v1 con filas falla como `unsupported_schema` sin tocarse. No se reetiqueta nada |
| 2. Conjunto y bloqueo durable | `DexcomPermissionSetPolicy` y `DexcomPermissionGate`: la visibilidad se comprueba primero; después definición y propietario del permiso, conjunto exacto, UID compartido, firmantes, versión exacta solo de Dexcom (I-2b) y UID propio de Next. Las causas durables se guardan en `dexcom_reception_blocks` (append-only) antes de rechazar. Si no se puede leer o escribir el bloqueo, la recepción queda cerrada |
| 3. Manifiesto | El receiver declara `android:permission="com.dexcom.cgm.EXTERNAL_PERMISSION"`, sigue desactivado (I-7) y Next solicita ese permiso (I-6). `QUERY_ALL_PACKAGES` **no** se declara (I-3). `verify.ps1` lo exige |
| 4. Contrato B1 a B5 | Lo aplica la presentación: mg/dL sin conversión, `egvTxTime` conservado sin uso, tendencia solo como literal en detalles y hora de medida «no verificada» |
| 5. Caso de uso puro | `PresentLatestDexcomReading` en `shared/dexcom-g7-payload`, con C2 b, C3, C5 a, C6 y B4 b. Un lote de varias lecturas o un duplicado no identifican «la última recibida» hasta B1 y se muestran como `selection_pending_b1` |
| 6. UI | Inicio y Bolo muestran la última lectura o su causa sin número. Diagnóstico añade las líneas `dexcom_reception.*`. `ReadOverview`, `LocalGlucoseSourcePort` y el motor no leen el registro ni la presentación (`verify.ps1`) |

ADR 0024: motor candidato `legacy-backend-core-candidate-v1` en
`shared/bolus-engine` y bloque «Cálculo de prueba» en Bolo, que no registra
ni sincroniza nada.

## Consecuencia de I-3 en esta versión

En Android 11 o posterior, sin `QUERY_ALL_PACKAGES`, la app no puede demostrar
que ve todos los titulares. Por eso la puerta queda cerrada como
`dexcom_reception.visibility_unproven`, que no es durable. Con este APK en el
Pixel, Inicio y Bolo mostrarán «Recepción pendiente de una comprobación» y no
registrarán mensajes reales. Es la consecuencia literal de la condición I-3 y
no se ha sorteado. Cuando la prueba P2 demuestre que hace falta, basta con
añadir el permiso al manifiesto y ajustar la comprobación de `verify.ps1`.

## Comandos ejecutados en la nube (Linux, JDK 21, Android SDK 36)

| Comando | Resultado |
|---|---|
| `./gradlew :shared:bolus-engine:androidJvmTest` | 17/17 `CandidateEngineTest`, incluida la paridad exacta con 12 vectores de Legacy `bcac891` |
| `./gradlew :shared:dexcom-g7-payload:androidJvmTest` | en verde, con 10/10 `LatestReadingPresentationTest` |
| `./gradlew androidJvmTest :android:app:testDebugUnitTest :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:app:processReleaseMainManifest :android:sender-fixture:assembleDebug :android:sender-fixture:lintDebug allMetadataJar` | `BUILD SUCCESSFUL`. 126 pruebas unitarias Android, 0 fallos |
| `python3 -I scripts/legacy-vectors/capture_candidate_vectors.py <copia Legacy> bcac891…` | genera `CandidateLegacyVectors.kt` sin efectos laterales |
| `pwsh` con las comprobaciones estáticas de `verify.ps1` (manifiesto, anclas, consumidores, límites) sobre los manifiestos fusionados reales | `STATIC CHECKS PASSED`. La ejecución completa en Windows queda para el CI |

No se han ejecutado pruebas instrumentadas: no hay dispositivo ni emulador con
KVM. Las pruebas de dispositivo actualizadas (`DexcomG7ReceiverDeviceTest` y
`SqliteDexcomObservationStoreDeviceTest`, con migración v1→v2, procedencia del
conjunto y bloqueos) compilan, pero aún no se han ejecutado.

## Comprobaciones pendientes del Pixel (no realizadas)

Estado histórico de esta entrega. Resultados posteriores y pendientes vigentes
en la [validación del Pixel](pixel-dexcom-and-calculation-2026-10-12.md).

| ID | Comprobación | Evidencia esperada (D2: sin glucosa ni transmisor en claro) |
|---|---|---|
| P1 | `scripts/verify.ps1 -DeviceTests` con el APK de esta PR | Suite instrumentada en verde, incluidas la migración v1→v2, los bloqueos y que un emisor sin permiso no recibe nada |
| P2 | I-3: instalar y activar el receiver (`adb shell pm enable org.bolusai.next/.glucose.dexcom.DexcomG7Receiver`), conceder el permiso (`adb shell pm grant org.bolusai.next com.dexcom.cgm.EXTERNAL_PERMISSION`) y abrir Diagnóstico | Las líneas `dexcom_reception.full_visibility` y `visible_holders` se comparan con los titulares del inventario L1. Si falta alguno, queda demostrado que `QUERY_ALL_PACKAGES` es necesario. Registrar la evidencia antes de añadirlo |
| P3 | Tras P2 y la PR que añada `QUERY_ALL_PACKAGES`: comprobar en Diagnóstico `gate_now=open` con Dexcom 4537, Legacy, reloj y Next | Puerta abierta y huella de titulares, sin bloqueo durable |
| P4 | Recepción real, según D1: Wi-Fi y datos móviles apagados, Bluetooth activo, sin modo avión; `dumpsys connectivity` sin red validada; repetir tras reiniciar | Mensajes `observed` con procedencia `approved_permission_set`; Inicio y Bolo con valor, tiempo desde la recepción, hora de medida no verificada y vigencia sin política |
| P5 | Informe B1: diferencias entre `timestamp` y la recepción en P4, sin umbrales | Lo aprueba el propietario. Hasta entonces la selección sigue por recepción |
| P6 | Cálculo de prueba en Bolo con perfil confirmado, glucosa manual y glucosa Dexcom de P4 | Desglose visible, banner de prueba y nada registrado. Se puede hacer ya con glucosa manual |

## Riesgos y pendientes

- I-3: la recepción real sigue cerrada hasta P2 y P3.
- El bloqueo durable solo se levanta con una versión nueva de la política en el
  código, es decir, con aprobación revisada en PR. No hay botón.
- Sin B1, un lote con varias lecturas o un duplicado no muestra valor.
- Las reglas del motor son candidatas (LC-006 a LC-011 pendientes). Los límites
  de prueba no están en el perfil.
- Legacy, Dexcom y el reloj no se han modificado. Legacy solo se ha leído en
  `bcac891` desde una copia local separada con el push desactivado.
