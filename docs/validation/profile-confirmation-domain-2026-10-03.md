# Confirmación de datos del perfil: dominio y persistencia — 2026-10-03

Base Next: `e6ae8fc32e24776e3bcf826768a24fac0dadf557` (PR #31, ADR 0014
aceptado). CI del SHA exacto comprobado antes de editar: Verify en verde
([37102999138](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37102999138),
evento `push`). Implementa la **primera parte** del
[ADR 0014](../adr/0014-clinical-profile-confirmation-eligibility.md): dominio
compartido y persistencia. La interfaz de revisión, confirmación y revocación y
el cambio del texto de Bolo quedan para la segunda entrega. Legacy no se
consulta ni se modifica.

## Qué existe tras esta entrega

- `shared/clinical-profile`:
  - `ProfileCompleteness.kt`: completitud estructural pura con la lista
    completa y determinista de faltas (unidad, zona declarada, zona reconocida
    por la plataforma y cada franja sin valor, en orden de catálogo y de
    inicio). `0` cuenta como configurado. Sin límites clínicos. El máximo de 48
    franjas no interviene.
  - `ProfileConfirmation.kt`: `OperationId` y el puerto `OperationIds`,
    eventos `confirm`/`revoke` separados del contenido, contrato de
    confirmación 1, `ProfileRecord` (versiones y eventos validados juntos) y
    `ConfirmationPolicy` con los pasos 2 a 5 de la sección 7.1.
  - `ProfileGate.kt`: las cuatro dimensiones (`ProfileGateState`), códigos de
    dominio estables, resultado que distingue operación histórica
    (`replayed`) y estado vigente, y resolución de una operación pendiente por
    su identidad (sección 9.3).
  - `ClinicalProfiles`: `readState`, `confirmRequest`, `revokeRequest`,
    `record` y `resolvePending`. El puerto `ClinicalProfileRepository` añade
    `readRecord` y `appendConfirmation`.
  - Nuevos códigos de operación `profile.confirmation.*`: `operation_mismatch`,
    `state_changed`, `stale_version`, `incomplete`, `already_active` y
    `not_active`. Un fallo de commit sigue siendo `profile.storage.save_failed`.
- Android:
  - `clinical-profile.db` v2. `ClinicalProfileSchema.kt` congela el texto v1
    (sin cambios desde `e78563a`, comprobado con `git log` y una huella del
    texto en `ClinicalProfileSchemaTextTest`) y añade la tabla de eventos, dos
    índices y seis triggers del ADR. Verificación por definición: inventario
    exacto de `sqlite_master`, `sql` byte a byte, índices automáticos con
    `PRAGMA index_list`/`index_info` y `android_metadata` con `locale`.
  - Creación limpia de v2 y migración v1 → v2 en la transacción del helper:
    definiciones v1, `foreign_key_check`, `quick_check`, validación completa
    del historial, objetos nuevos y comparación final con v2. Sin
    confirmaciones automáticas ni filas reescritas. Cualquier fallo revierte a
    v1 intacta. Cada apertura compara de nuevo con v2.
  - `ClinicalProfileRows.kt`: códec estricto de filas compartido por el
    adaptador, la migración y el lector v1 congelado de las pruebas.
  - `save`, `readVersions`, `readRecord` y `appendConfirmation` validan versiones
    **y** eventos dentro de una transacción exclusiva antes de responder o
    escribir. `Recorded` solo tras el commit.
  - `AndroidOperationIds`: UUID aleatorios en minúsculas. Todavía no lo usa
    ninguna pantalla.

Bolo, `ReadOverview` y el motor no cambian ni leen el perfil. La UI del perfil no
cambia: sigue mostrando versiones e historial, sin confirmar ni revocar.
`allowsCalculation` y `allowsTreatment` siguen en `false` en todos los
resultados.

## Garantías y evidencia

| Área | Garantía | Evidencia |
|---|---|---|
| Completitud y `0` | `0` explícito configurado en cada parámetro, «Sin configurar» como falta por franja, lista completa y ordenada, zona no declarada y no reconocida, más de 48 franjas completas | `ProfileCompletenessTest` |
| Unidad | la versión que cambia desde una unidad declarada es incompleta y no confirmable. Primera declaración y restauración exacta con otra unidad, completas, confirmables con confirmación nueva | `unitChangeFromADeclaredUnit…`, `unitTransitionsFollowTheApprovedRules`, SQLite `newVersionsNeverInherit…` |
| Matriz E1 a E11 | estados, faltas y códigos de dominio, siempre con cálculo y tratamiento bloqueados | `ProfileConfirmationFlowTest` (comprobación `@AfterTest` en cada estado visto) |
| Confirmar, revocar, reconfirmar | hechos append-only separados del contenido. Reconfirmar es una operación nueva | `confirmRevokeAndReconfirm…` (común y SQLite con reinicio) |
| Sin herencia | versión nueva, restauración con la misma huella y cambio de unidad sin confirmación. Las anteriores quedan como «superadas» | `newVersionsNeverInherit…` (común y SQLite) |
| E11 | la zona retirada mantiene la confirmación vigente, permite revocar y después impide confirmar | `retiredTimeZone…`, SQLite `revocationWorksWhenTheZone…` |
| Validación del historial | huecos y duplicados de `seq`, `operation_id` repetido o mal formado, contrato desconocido (`unsupported_schema`), huella o versión inexistente, revocación de revocación, doble revocación, dos confirmaciones vigentes y `observed_event_seq` incoherente fallan cerrados | `ProfileRecordTest`, SQLite `retriesAndSavesFailClosed…` |
| Reintentos | todo reintento valida antes el historial. Una confirmación revocada se devuelve como histórica (`replayed`) con estado vigente «revocada» y no se reactiva. Misma clave con otra carga útil: `operation_mismatch` | `lostResponseFailedCommit…`, `retriesAndSavesNeverSucceedOverAnUnprovableHistory`, SQLite |
| Concurrencia | dos conexiones confirmando a la vez dejan una sola confirmación. Confirmación frente a revocación y versión nueva entre lectura y confirmación fallan sin escribir | `concurrentScreens…`, SQLite `twoConnectionsNever…` |
| Respuesta perdida y recreación | el reintento por otra conexión devuelve el evento confirmado. La operación pendiente se resuelve por identidad aunque el estado observado haya quedado atrás. Lectura no demostrada: sin éxito ni fracaso | `pendingOperationsAreResolvedByIdentityFirst`, SQLite |
| Fallo de escritura | fallo inyectado dentro de la transacción tras aprobar la política: nada insertado y la misma intención se registra después | `lostResponseFailedCommit…`, SQLite `failedWriteRollsBack…` |
| Triggers v2 | `UPDATE`/`DELETE` abortan. Inserción no contigua, estado observado distinto, versión no última, huella distinta, segunda confirmación vigente, revocación de revocación, de confirmación revocada o de otra versión, tipo desconocido, forma incorrecta y `operation_id` repetido se rechazan | SQLite `triggersKeepEventsAppendOnly…` |
| Creación y migración | v2 limpia idéntica a migrar una v1 vacía (`sqlite_master` completo). Migración con historial: filas, huellas, origen, `restored_from`, fechas y escritores idénticos, cero eventos | SQLite `cleanCreationEquals…`, `migrationKeepsEveryRow…` |
| Copia previa (8.6) | copia de una v1 válida legible por el lector v1 congelado tras migrar el original. v1 corrupta (valor, decimal no canónico, huella, unidad): no migra, sigue en v1 con la fila corrupta, y original y copia se siguen rechazando con el mismo motivo | SQLite `priorCopyOfAValidV1…`, `corruptV1IsNeither…` |
| Esquema alterado | v1 con trigger permisivo del mismo nombre, `CHECK` distinto, columna extra, sin clave compuesta, objetos extra, trigger ausente u objetos v2: `unsupported_schema` sin tocar nada. v2 alterada, `user_version` 3 o v2 marcada como 1: rechazadas al abrir | SQLite `alteredV1Definitions…`, `alteredV2Definitions…` |
| Comidas | `meal-drafts.db` sigue en v3 con el mismo esquema y filas tras crear y migrar el perfil | SQLite `mealDatabaseIsUntouched…` |
| Sin uso clínico | motor, Bolo y `ReadOverview` sin dependencia del perfil | `verify.ps1` |

Las pruebas ADR 0012 que manipulaban filas borrando triggers ahora los
recrean con su texto congelado exacto. Sin ese paso la apertura falla antes por
`unsupported_schema`, que es el comportamiento nuevo y tiene sus propias
pruebas.

## Verificación

Integración: PR #32, integrada en `main` por el flujo normal sin saltar checks.
La cabeza con el código, `a42b2d4`, pasó Verify
([37121897937](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37121897937)).
La cabeza final solo añade esta actualización de documentación y pasó su propio
Verify antes de integrar.

Comandos ejecutados en Windows desde la rama de trabajo:

```powershell
.\gradlew.bat :shared:clinical-profile:androidJvmTest
.\scripts\verify.ps1
.\scripts\verify.ps1 -DeviceTests
git diff --check
```

- `shared/clinical-profile`: 87 pruebas comunes en verde, 26 nuevas
  (`ProfileCompletenessTest` 9, `ProfileRecordTest` 5,
  `ProfileConfirmationFlowTest` 12) y las 61 anteriores sin cambios de
  comportamiento.
- Android JVM: `ClinicalProfileSchemaTextTest` (2) fija la huella del texto v1
  liberado y de las adiciones v2.
- `scripts/verify.ps1`: clean, compilación, lint, pruebas comunes y
  Android/JVM, manifests, exclusiones de backup, independencia del motor y de
  `ReadOverview`, workflow y `git diff --check`. Resultado: correcto.
- `scripts/verify.ps1 -DeviceTests` en Pixel 10 Pro Fold, único dispositivo
  autorizado, API 37, con `KEYCODE_WAKEUP` cada 10 s y sin cambiar ajustes, red
  ni modo avión. Primera ejecución: 97 de 98, con un fallo en
  `ClinicalProfileDeviceTest.restoredEditorCannotSaveWhenTheStoredHistoryFailsToRead`,
  que manipulaba una fila borrando un trigger y ahora recibía
  `unsupported_schema`. Corregida la prueba para recrear el trigger con su
  texto exacto: `OK (98 tests)` en 77,3 s. Son 84 anteriores más 14 nuevas en
  `ClinicalProfileConfirmationDeviceTest`, añadida a la lista `-e class` de
  `verify.ps1`. Los paquetes de prueba se desinstalaron al terminar.

Funcionamiento offline: todo es local. El manifest sigue sin permiso de
Internet (`verify.ps1`) y las pruebas no cambian red, ajustes ni modo avión.
Ninguna prueba usa datos personales. Todas las bases son sintéticas y se borran.

## Riesgos y pendientes

- Riesgo nuevo: interpretar «Datos confirmados» como aprobación clínica.
  Mitigación en esta entrega: `allowsCalculation = false`, bloqueo
  `profile.not_approved_for_calculation` en todos los estados y ninguna UI que lo
  muestre todavía. Los textos de la sección 9 llegan con la segunda entrega
  (después integrada mediante la PR #33).
- Rollback de código tras migrar: un build v1 no abre la base v2 (falla cerrado
  con `unsupported_schema`, sin pérdida). Se recupera con un build compatible.
- La verificación por definición se hace al abrir y al migrar, como fija el
  ADR. Un cambio de esquema hecho por otra conexión mientras el repositorio está
  abierto se detecta en la siguiente apertura. Las filas se revalidan en cada
  lectura y escritura.
- Pendiente del ADR 0014 (segunda entrega, después integrada mediante la PR
  #33; véase [su validación](profile-confirmation-ui-2026-10-03.md)): pantalla
  de revisión desde una lectura nueva, confirmar y retirar con `operation_id`
  conservado en el estado guardado, bloqueo con editor con cambios, resolución
  de la operación pendiente al recrear, historial con confirmaciones y superadas, textos de la sección 9,
  pruebas de UI y layouts, y «Perfil · no se usa para calcular» en Bolo.
- Pendiente fuera de este ADR: correspondencia `input.profile.*` (documentada,
  no implementada ni conectada), opción B o C de la sección 6.3 antes de
  cualquier aprobación clínica, P4 y P6 del ADR 0012, límites, vigencia, DIA e
  IOB.
