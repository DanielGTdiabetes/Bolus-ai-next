Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

CONTINUACIÓN ACTIVA — 2026-10-11
El propietario pide reducir fricción y entregar capacidades completas. Aplica
docs/EXECUTION_PLAN.md §3.3: decisiones técnicas reversibles autónomas, ADR solo
cuando corresponda, preguntas necesarias agrupadas y evidencia sin duplicación.
Se mantienen los controles clínicos y las pruebas obligatorias.

DEXCOM INSTALADA: NO MODIFICAR
El propietario confirma que Dexcom ya está modificada, funciona y la utiliza
`bolus_ai`. Next debe aprovechar ese mismo transporte como sustituto de Bolus AI.
No pedir cambios, recompilación, reinstalación ni `setShareIdentityEnabled(true)`
en Dexcom. La adaptación corresponde a Next. Identificar y archivar la APK
instalada es una operación de solo lectura, no una obligación de reconstruirla.
Revisar en el ADR 0022 la política de recepción de Next: la implementada hoy
exige identidad compartida y todavía no es compatible con el envío existente.
No simular esa identidad ni llamar autenticación por mensaje a verificar una
APK instalada. Este cambio de alcance no habilita cálculo ni tratamiento.

PROPUESTA DE RECEPCIÓN PENDIENTE
El ADR 0023 (docs/adr/0023-dexcom-existing-transport-trust.md) evalúa el receiver
protegido por permiso, conjunto exacto de titulares y anclas de la APK instalada.
Es un borrador para decisión del propietario: visibilidad completa, ventana
temporal, apps aprobadas que falsifiquen y emisores privilegiados limitan la
garantía. No atribuye mensajes a Dexcom ni autoriza implementar o calcular.

La PR #53 (ADR 0022 R11) ya está integrada en `origin/main` mediante
`740af25`; no reabrir su trabajo como un paso pendiente. Comprobar el CI del
SHA integrado al continuar. Priorizar ahora una entrega completa de
recepción local Dexcom y presentación offline con cálculo bloqueado, reuniendo
las decisiones pendientes en una sola propuesta. No encadenar nuevas PR de
diagnóstico o endurecimiento sin un hallazgo o riesgo concreto.

El detalle que sigue conserva antecedentes de entregas; el estado de integración
se comprueba en GitHub y origin/main, no se deduce de esos antecedentes.

OBJETIVO DEL PROYECTO
Recrear Bolus AI en móvil con cálculo, perfil, IOB, comidas e historial locales.
El camino crítico debe funcionar sin NAS ni Render. Seguridad > consistencia >
disponibilidad > comodidad.

ESTADO REAL
Mis platos y Comidas permiten crear, editar y recuperar borradores versionados.
Bolo muestra una revisión seleccionada con campos exactos, ausencias, base,
identidad y origen, y permite retirarla. Cada plato o borrador permite consultar
y restaurar revisiones como revisión nueva con `restored_from`. SQLite de comidas
sigue en v3 (ADR 0008 a 0011).

Ajustes → Cálculo captura el perfil clínico local (ADR 0012, aceptado con las
ocho recomendaciones de su sección 12 y la regla de unidades 4.4). Módulo
`shared/clinical-profile` y base propia `clinical-profile.db` v2 (parte 1 del
ADR 0014, migrada desde v1): versiones append-only con triggers, huella
SHA-256 canónica revalidada al leer, origen `manual`/`restored` (`system_proposal_accepted` reservado y rechazado), fecha y
escritor. Unidad de glucosa (mg/dL o mmol/L) y zona IANA declaradas por versión.
`carb_ratio` (g/U), `insulin_sensitivity` y `glucose_target` por franjas
completas en el modelo. Decimal canónico no
negativo, 6 enteros y 3 decimales como límite de representación, no clínico.
«Sin configurar» distinto de `0`. Cambiar de unidad vacía ISF y objetivo y exige
guardar esa versión sin valores dependientes. Nada se convierte. Restaurar crea
una versión nueva sin tocar las anteriores. Conflicto e idempotencia como en
comidas. Auto Backup sigue desactivado y verify.ps1 lo comprueba junto con que el
motor y `ReadOverview` no dependen del perfil.

El editor de franjas (ADR 0013, aceptado con T1 a T5) divide, une solo franjas
con valor exactamente igual, mueve límites compartidos y edita el valor de cada
franja. Cobertura 00:00–24:00 por construcción, 24 h, 1 minuto sin redondeo,
`24:00` solo como fin. Las operaciones puras viven en `ProfileSegments.kt` e
identifican franjas por intervalo (`stale_segment` si ya no existe). Franjas
adyacentes iguales se conservan y solo se unen por acción explícita. Cambiar de
unidad conserva las fronteras de ISF y objetivo, vacía sus valores y bloquea su
estructura hasta guardar. Máximo de 48 franjas solo para dividir. «Modificada»
es estado derivado del editor, fuera de la huella. Paneles en línea que
sobreviven a la recreación. Sin cambios de esquema.

Ajustes → Cálculo permite además revisar en solo lectura la última versión
guardada, confirmar sus datos y retirar esa confirmación como acciones
separadas, con historial de confirmaciones (ADR 0014, partes 1 y 2 integradas).
Confirmar datos no aprueba uso clínico.

Consulta docs/adr/0012-local-clinical-profile.md,
docs/adr/0013-clinical-profile-segment-editor.md,
docs/adr/0014-clinical-profile-confirmation-eligibility.md,
docs/validation/profile-segment-editor-2026-09-28.md,
docs/validation/profile-confirmation-domain-2026-10-03.md y
docs/validation/profile-confirmation-ui-2026-10-03.md.
Comprueba el CI del SHA exacto integrado antes de empezar. Como antecedente,
la PR #42 integró la implementación del ADR 0017 (merge commit
929d5c137feb2329dd3792d33f3653f6a05ff3cb, Verify 37901021591 en verde),
aprobada por el propietario el 2026-10-09 en el HEAD
779b9f25bac31d56ce5594bf8c31403499dc616c. Su registro documental llega en una
PR posterior: comprueba el SHA exacto de `origin/main` y su CI.
Esta entrega no consultó ni modificó Legacy. Su última auditoría documentada
continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes que ese
SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
El perfil es captura. La confirmación de datos existe en dominio, base local
(parte 1 del ADR 0014) y pantalla (parte 2). No aprueba uso clínico: no
alimenta motor, Bolo, IOB ni recomendación, y cálculo y tratamiento siguen
bloqueados en todos los estados. Bolo muestra «Perfil · no se usa para
calcular». Desde el ADR 0017 sus detalles técnicos y los de Diagnóstico leen el
perfil solo para mostrar sus causas, sin modificar versiones, franjas ni
eventos (la apertura de SQLite puede crear o migrar el esquema). No hay
límites clínicos, DIA, curva, redondeo, conversión de unidades, propuestas del
sistema, aprendizaje, exportación/importación, sync ni perfiles alternativos. La
resolución de franjas en cambios de hora queda para el ADR del motor. Los macros
de comida son textos sin validar/convertir; ausencia nunca equivale a cero. No
hay recepción Dexcom, IOB local, catálogo ni historial clínico. iOS sigue
pendiente de verificación. Cálculo y confirmación clínica siguen bloqueados.
Avisa al propietario antes de usar el móvil: otro proceso puede estar probando
en él, y `verify.ps1` exige un único dispositivo en adb. Las pruebas USB necesitan el Pixel desbloqueado y con la pantalla encendida
durante toda la ejecución. Enviar `adb shell input keyevent KEYCODE_WAKEUP` cada
10 s mientras corre verify.ps1 lo evita sin cambiar ajustes. Toda clase nueva de
instrumentación debe añadirse a la lista `-e class` de scripts/verify.ps1.
Toda prueba que lance `MainActivity` (Bolo y Diagnóstico leen el perfil desde
el ADR 0017) o abra Ajustes → Cálculo debe inyectar
`MainActivity.profileRepositoryFactory` en memoria o sobre un fichero sintético
(la fábrica devuelve `ClosableProfileRepository`; `ControlledProfileRepository`
de androidTest falla lecturas o escrituras y retiene escrituras). Toda prueba
que visite Diagnóstico debe inyectar además `MainActivity.dexcomLogReader`
(`emptyDexcomLog` de androidTest): `DexcomStorageGuard` rechaza
`dexcom-observations.db` de la app durante la instrumentación y `verify.ps1`
exige la inyección.
`profileTimeZoneRules` y `profileOperationIds` permiten zonas retiradas y
operaciones contadas en pruebas. Una base con un trigger sintético añadido no
supera la verificación de esquema al abrirse de nuevo: cuenta filas en crudo.
En Windows, los XML de recursos están en CRLF en la copia de trabajo: al editarlos
con scripts conserva los finales de línea o `git diff --check` fallará.

ADR 0014 — CONFIRMACIÓN DE DATOS DEL PERFIL (partes 1 y 2 integradas)
ADR 0014 (docs/adr/0014-clinical-profile-confirmation-eligibility.md), aceptado
el 2026-10-03 con C1 a C9 según su recomendación (C7 como solución
provisional). Confirmar datos es revisar una versión guardada identificada por
número y huella. No aprueba límites, vigencia ni aptitud para calcular o tratar.

Parte 1 integrada en `main` mediante la PR #32 (rama
`claude/profile-confirmation-domain`). Véase
docs/validation/profile-confirmation-domain-2026-10-03.md:
- `shared/clinical-profile`: `ProfileCompleteness.kt` (faltas deterministas,
  `0` configurado, zona comprobada con `TimeZoneRules`, sin límite de 48),
  `ProfileConfirmation.kt` (`OperationId`, puerto `OperationIds`, eventos
  `confirm`/`revoke`, contrato 1, `ProfileRecord.validate`,
  `ConfirmationPolicy`) y `ProfileGate.kt` (`ProfileGateState` E1 a E11,
  `ProfileGateCodes`, `ConfirmationOutcome` con `replayed` y estado vigente,
  `PendingConfirmations.resolve`). `ClinicalProfiles` añade `readState`,
  `confirmRequest`, `revokeRequest`, `record` y `resolvePending`. El puerto añade
  `readRecord` y `appendConfirmation`. Códigos `profile.confirmation.*` en
  `ProfileFailure`.
- Android: `clinical-profile.db` v2. `ClinicalProfileSchema.kt` congela
  `SCHEMA_V1` (huella fijada en `ClinicalProfileSchemaTextTest`) y
  `V2_ADDITIONS`, y verifica por definición al abrir y al migrar.
  `ClinicalProfileRows.kt` es el códec estricto de filas. `save`, lecturas y
  `appendConfirmation` validan versiones y eventos en una transacción
  exclusiva. `AndroidOperationIds` (usado por la pantalla desde la parte 2).
- Pruebas: 87 comunes y `OK (98 tests)` en dispositivo, con
  `ClinicalProfileConfirmationDeviceTest` en `verify.ps1`. Las pruebas que
  manipulan filas recrean el trigger con su texto exacto. Si no, la apertura
  falla con `unsupported_schema`.
Parte 2 integrada en `main` mediante la PR #33 (rama
`claude/profile-confirmation-ui`, merge commit
`89404989e451a3cc1899df309caedc759bdfdc44`, Verify 37131464837 en verde), tras
la revisión del propietario y una corrección. Véase
docs/validation/profile-confirmation-ui-2026-10-03.md:
- `ClinicalProfileModel` lee con `readState()` y presenta `ProfileGateState`
  (E1 a E11). Revisión de solo lectura desde una lectura nueva, ligada a
  versión, huella y último evento, con todas las franjas y los `0` destacados.
  Bloqueada con cambios, textos pendientes, panel abierto o cambio de unidad.
  Confirmar y retirar separados, `operation_id` una vez por intención con
  `OperationIds`, petición completa en el estado guardado, recreación con
  `resolvePending`, `replayed` nunca como reconfirmación, historial con
  confirmadas, retiradas y superadas. Bolo: «Perfil · no se usa para calcular».
- Pruebas: `ClinicalProfileConfirmationUiDeviceTest` (17) en `verify.ps1`.
  Corrección de revisión: al resolver una operación pendiente se borra el aviso
  anterior antes de leer; regresiones de escritura fallida, estado guardado,
  recreación, lectura fallida y recuperación para confirmar y retirar.
  `verify.ps1` comprueba también que `ScreenRenderer` no referencia el perfil.
Ni Bolo, `ReadOverview` ni el motor leen el perfil. La traducción del estado
del perfil a `input.profile.*` existe solo en el módulo del ADR 0015, sin
consumidores. Sin cambios de esquema, serialización ni huellas.

ADR 0015 — CONTRATO DE INDISPONIBILIDAD DEL PERFIL (implementado e integrado)
docs/adr/0015-profile-unavailability-contract.md, aceptado el 2026-10-03 con D1
a D10 (opción C) e integrado como documentación mediante la PR #35. El
propietario autorizó después implementarlo. La implementación está en la rama
`claude/profile-unavailability-v2` (PR #36), aprobada por el propietario e
integrada en `main` mediante el merge commit
`a57d2e5ce6497220a54e3ae632087c688a13f002` (Verify 37142410544 en verde). Véanse
docs/contracts/unavailable-input-v2.md,
docs/validation/profile-unavailability-v2-2026-10-03.md y la sección 13 del ADR:
- `shared/bolus-engine`: contrato v2 aditivo (`UnavailabilityReasonV2`,
  `InputUnavailability`, `InputUnavailabilityReport`,
  `InputUnavailabilityErrors`). 53 combinaciones admitidas, `unconfirmed` solo
  para `profile`, elevación total de las 52 de v1, detalle validado (gramática,
  128 caracteres, 16 detalles), rechazos con identificador estable, copias
  defensivas, unión de detalles sin recorte y `@Throws`. v1 congelado.
- `shared/profile-unavailability`: módulo común nuevo con
  `ProfileUnavailability.report(ProfileGateState)` (secciones 6.1 y 6.2).
  Depende del perfil y del contrato. Su único consumidor es la pantalla del
  perfil (ADR 0016): `verify.ps1` fija la lista cerrada de ficheros de Android
  que pueden usarlo. `Unreadable` con un fallo que no es de
  lectura se rechaza con `profile_unavailability.unreadable_reason_not_supported`
  (decisión D11, aprobada por el propietario, ADR 0015 §13).
- D7: `ReadOverview` emite `input.profile.policy_not_approved` estático, sin
  leer el perfil. Bolo lo muestra en «Detalles técnicos».
- Pruebas: motor 34, perfil 87, comidas 17, traductor 25, Android 44 y
  `OK (115 tests)` en dispositivo. `PublishedContractV2Test` compara las tablas
  del documento del contrato con el código.
- Swift ampliado y sin ejecutar: **iOS no verificado** (ADR 0004).

ADR 0016 — PRIMER CONSUMIDOR DEL INFORME DEL PERFIL (aceptado, A integrada)
docs/adr/0016-profile-unavailability-consumer.md conecta el traductor del ADR
0015 solo con la pantalla del perfil (Ajustes → Cálculo), como bloque técnico
plegado. Aceptado el 2026-10-04 con A1 a A6, A4 precisada (solo se captura el
rechazo conocido del traductor) y B1 solo como planificación: Bolo sigue sin
conectarse. Integrado como documentación mediante la PR #38. La opción A,
aprobada por el propietario, está integrada en `main` mediante la PR #39
(merge commit `c583e6a3cdeb34fb4ddaf75bbcffdc620c629f0b`, Verify 37177820636 en verde). Véanse la sección 11 del ADR y
docs/validation/profile-unavailability-screen-2026-10-04.md:
- `ProfileUnavailabilityBlock` (puro) y `ClinicalProfileModel.unavailability`,
  derivada de `gate` en cada acceso. `gate` nulo se traduce como E1.
- `ClinicalProfileScreen` añade el bloque (tags `profile:unavailability` y
  `profile:unavailability:toggle`) en la vista principal. `MainActivity`
  recuerda si está abierto sin guardarlo: se pliega tras recreación.
- `ClinicalProfileUnavailabilityDeviceTest` en la lista de `verify.ps1`.

ADR 0017 — INFORME DEL PERFIL EN BOLO Y DIAGNÓSTICO (implementado e integrado)
docs/adr/0017-bolo-profile-unavailability-details.md, aceptado el 2026-10-04
con B2 a B10, la ampliación a Diagnóstico y la sección 14: `ensureLoaded()`
solo en las pantallas autorizadas, resolución de pendientes solo de lectura,
cero escrituras del perfil (versiones, franjas y eventos) al abrir Bolo o
Diagnóstico, aunque la apertura de SQLite puede crear o migrar el esquema, y
E1 antes del resultado en un
modelo nuevo. Integrado como documentación mediante la PR #41. Implementación
aprobada por el propietario e integrada mediante la PR #42 (merge commit
`929d5c137feb2329dd3792d33f3653f6a05ff3cb`, Verify 37901021591 en verde).
Véanse la sección 15 del ADR y
docs/validation/bolo-profile-details-2026-10-04.md:
- `ui/BlockingDetails.kt` (puro): un único informe v2 con glucosa, IOB y comida
  elevadas y las causas del perfil. `destinations` = `BOLUS`, `MANUAL`,
  `OFFLINE_BOLUS`, `DIAGNOSTICS`.
- `MainActivity`: `ensureLoaded()` solo para esos destinos, nunca `retry()`.
  `blockingDetailsOpen` sin guardar. `ScreenRenderer` recibe `BlockingView`.
- `verify.ps1`: usuarios cerrados del modelo y de `ensureLoaded`, destinos
  exactos y toda clase de instrumentación que lance `MainActivity` debe
  inyectar un repositorio de perfil sintético.
- Pruebas: Android JVM 57 y `OK (131 tests)` en dispositivo, con
  `ProfileStorageGuardDeviceTest`. La instrumentación usa
  `ProfileIsolationTestRunner`.
- La primera ejecución en dispositivo abrió la `clinical-profile.db` propia
  de la app en el Pixel desde tres pruebas de comidas. Está vacía y se
  conserva por decisión del propietario. Desde entonces
  `ProfileIsolationTestRunner` activa `ProfileStorageGuard` y la
  instrumentación falla explícitamente si una fábrica falta o apunta a la base
  real (`ProfileStorageGuardDeviceTest`).
- `verify.ps1` lee con `aapt2` el manifiesto empaquetado de `app-debug.apk` y
  del APK de pruebas (resultado fusionado, incluida la variante `src/debug`).
  Rechaza `Application` propia, factoría de componentes distinta de
  `androidx.core.app.CoreComponentFactory` y providers, porque Android los
  crea antes de la barrera. Exige que el APK de pruebas instrumente esa app con
  `ProfileIsolationTestRunner` y no instala si los APK cambian tras comprobarlos.
  Se autoprueba con manifiestos sintéticos en
  `scripts/fixtures/instrumentation-isolation/`. Si una dependencia nueva añade
  un provider, verify falla: no la amplíes sin decidir cómo aislarla.

ADR 0018 — PARSER ESTRUCTURAL DEL PAYLOAD DEXCOM G7 (implementado)
docs/adr/0018-dexcom-g7-payload-parser.md, aceptado el 2026-10-11 con D1
(rechazar el lote entero), D2 (campos desconocidos listados por nombre, no
bloquean), D3 (duplicado idéntico se colapsa, mismo timestamp con otro
contenido es conflicto) y D4 (valor solo estructural, sin rango; el 1..400 del
productor no se copia). Módulo KMP `shared/dexcom-g7-payload`, solo objetivo
JVM: `DexcomG7Payload`/`PayloadValue` (copia tipada sin `Bundle`),
`DexcomG7PayloadParser.parse(payload, authenticatedPackage)`, lecturas siempre
`NOT_APPROVED_FOR_USE` y códigos `dexcom_g7.payload.*`. `verify.ps1` impide
que dependa de motor, perfil, comidas, Android o Java y que nadie fuera del
módulo lo use. 21 pruebas JVM. Véase
docs/validation/dexcom-g7-payload-parser-2026-10-11.md.

ADR 0019 — COMPOSICIÓN DE RECEPCIÓN DEXCOM G7 (implementado)
docs/adr/0019-dexcom-g7-reception-composition.md, autorizado por el
propietario el 2026-10-11 con T1 a T6 como decisiones técnicas revisables.
`glucose/dexcom/DexcomPayloadExtras.kt` (`ExtrasView`, `BundleExtrasView`,
`Intent.readDexcomPayload()`, sin conversión de tipos, `BadParcelableException`
→ mensaje ilegible) y `DexcomG7Reception.kt` (autentica, lee una vez, parsea
con el paquete autenticado; `SenderRejected` → motivo del autenticador,
ilegible o rechazado → `parse_failed`, observado → `policy_not_approved`).
`verify.ps1` fija la lista cerrada de ficheros que usan el parser o la
composición. JVM 12 pruebas nuevas; `DexcomBundleExtrasDeviceTest` (3) en la
lista de instrumentación, 3/3 superadas en el Pixel el 2026-10-11. Véase
docs/validation/dexcom-g7-reception-composition-2026-10-11.md.

ADR 0020 — REGISTRO LOCAL DE OBSERVACIONES DEXCOM G7 (implementado)
docs/adr/0020-dexcom-g7-observation-log.md, aceptado el 2026-10-11 con E1
(lecturas y rechazos sin contenido), E2 (conflicto enlazado, nunca
sobrescribir), E3 (huella SHA-256 del transmisor) y E4 (sin borrado; retención
pendiente). E5 a E9 técnicas: rechazos del emisor no se guardan, duplicado =
contenido idéntico a cualquier observación de la clave, prefijo de dominio en
la huella, reloj inyectado solo para sellar, emisor autenticado (paquete,
versión, firmantes) guardado con cada mensaje. `ObservationLog.kt` (común) y
`glucose/dexcom/storage/` (Android, `dexcom-observations.db` v1 con triggers
append-only, índice único parcial y trigger de conflicto). JVM 18 pruebas
nuevas; `SqliteDexcomObservationStoreDeviceTest` (8 en la entrega del ADR 0020;
9 existentes desde la PR #47, que añadió una del ADR 0021), 9/9 superadas en el
Pixel el 2026-10-11. Véase
docs/validation/dexcom-g7-observation-log-2026-10-11.md.

ADR 0021 — REGISTRO DEXCOM EN DIAGNÓSTICO (implementado)
docs/adr/0021-dexcom-log-diagnostics-view.md, aceptado el 2026-10-11 con F1
(solo recuentos y estado, nunca valores de glucosa) y F2 (solo Diagnóstico,
relectura en cada entrada). F3 a F5 técnicas: bloque plegado sin guardar,
resumen agregado en SQLite (`summary()`) sin cargar el historial,
lectura fuera del hilo principal, y la lectura de producción no crea el
fichero si no existe. `ObservationSummary` (común), `ui/DexcomLogBlock`,
`ui/DexcomLogModel`, `DexcomLogReader` y la costura
`MainActivity.dexcomLogReader`. `verify.ps1` impide que `ScreenRenderer`
conozca el registro. JVM 6 pruebas nuevas; `DexcomLogDiagnosticsDeviceTest`
(3) superadas 3/3 en el Pixel el 2026-10-11. Véase
docs/validation/dexcom-log-diagnostics-2026-10-11.md.
Seguimiento de la revisión independiente de Codex del 2026-10-11 (sección del
ADR 0021 y docs/validation/dexcom-log-read-isolation-2026-10-11.md):
Diagnóstico lee con `summaryOfExisting()`, que comprueba versión y esquema en
una conexión de solo lectura. Así un fichero vacío o v0 ya no se inicializa: da
`unsupported_schema` y conserva sus bytes. `DexcomStorageGuard` aísla
`dexcom-observations.db` en la instrumentación. `DexcomLogIsolationDeviceTest`
(8) está en la lista de `verify.ps1`. Superó 8/8 en el Pixel sobre `2839a57`,
con la instrumentación completa en `OK (154 tests)`.

EJECUCIÓN EN DISPOSITIVO DE LOS ADR 0019 A 0021 (2026-10-11)
`.\scripts\verify.ps1 -DeviceTests` sobre
`cacc8a905f0e3977d9d4fa44f35f7fdb0752dcd3` (merge de la PR #47) en el Pixel 10
Pro Fold (`rango`, Android 17, API 37, build `CP3A.260905.009`), único en
`adb devices`. Salida 0, Gradle `BUILD SUCCESSFUL` e instrumentación
`OK (146 tests)`. Pruebas existentes, ejecutadas y superadas:
`DexcomBundleExtrasDeviceTest` 3/3/3, `SqliteDexcomObservationStoreDeviceTest`
9/9/9 (8 del ADR 0020 y 1 del ADR 0021) y `DexcomLogDiagnosticsDeviceTest`
3/3/3. Sigue sin prueba en dispositivo la rama `BadParcelableException`
(ADR 0019).

ADR 0022 — RECEIVER DEXCOM G7 DESACTIVADO (implementado, preparatorio)
docs/adr/0022-dexcom-g7-receiver-disabled.md, aceptado el 2026-10-11 con R1
(componente `android:enabled="false"`, solo `adb shell pm enable`, Diagnóstico
muestra `dexcom_receiver.component` y `dexcom_receiver.policy`), R2 (sin
`com.dexcom.cgm.EXTERNAL_PERMISSION`: el productor real no descubre a Next) y
R3 (rechazo del emisor sin leer extras ni abrir el registro, E5). R4 a R9
técnicas: acción → autenticación → extras → parser síncrono, almacén abierto
solo tras autenticar y escrito con `goAsync()` en un hilo serie, política de
producción constante `Pending`, UUID como identificadores, exportado sin
`android:permission`. `DexcomG7Receiver.kt` (`DexcomReceiverConfiguration`,
`DexcomReceipt`, `DexcomG7Intake`, receiver y costura `receipts`),
`DexcomReceiverStatus.kt` y `AndroidObservationIds`. El fixture sintético
añade la orden `TRIGGER_G7`. `verify.ps1` exige la forma exacta del receiver
en los manifiestos, ningún valor `com.dexcom.` salvo la acción, política
`Pending` y ninguna subclase, ancla aprobada ni observador en producción. JVM
10 pruebas nuevas; `DexcomG7ReceiverDeviceTest` (8) en la lista de
instrumentación. Véase
docs/validation/dexcom-g7-receiver-disabled-2026-10-11.md.
R10: `DexcomG7Intake.record` convierte las excepciones al abrir, escribir o
cerrar el almacén en `DexcomReceipt.StorageFailed` (glucosa
`persistence_failed`) en lugar de cerrar el proceso. Superó en el Pixel, sobre
`a0698d1`, `DexcomG7ReceiverDeviceTest` 10/10,
`AndroidDexcomSenderEvidenceTest` 8/8 y `DexcomLogDiagnosticsDeviceTest` 3/3,
con la instrumentación completa en `OK (164 tests)`. Véase
docs/validation/dexcom-g7-receiver-storage-failure-2026-10-11.md.
R11 (aprobada el 2026-10-11 sobre `61e5f6f`, decisiones 1a a 5a con los
ajustes de alcance y etapas): en `DexcomG7Intake.accept` ninguna `Exception`
escapa. Cuatro límites separados (fábrica de la fuente y autenticación,
lectura de extras, parser y reloj, este último por la revisión de Codex) dan
`DexcomReceipt.IntakeFailed` con `dexcom_receiver.intake_authentication_failed`,
`intake_extras_failed`, `intake_parser_failed` o `intake_clock_failed`
(glucosa `clock_anomaly`), sin guardar nada. Fallo de autenticación sin extras,
almacén ni reloj. `accept` recibe una fábrica de la fuente
(`DexcomG7Receiver.senderEvidence`) y `DexcomG7Reception` el parser. T3 sin
cambios. Los `Error` (incluido `StackOverflowError` en extras, solo provocable
por un emisor autenticado) siguen cerrando el proceso: riesgo aceptado.
`goAsync()` y `writer.execute` fuera de R11. JVM `DexcomG7IntakeTest` 24/24
en el contenedor. `DexcomG7ReceiverDeviceTest` 11/11 en el Pixel sobre
`d1708f4`, con la instrumentación completa en `OK (165 tests)`. Véase
docs/validation/dexcom-g7-receiver-intake-failure-2026-10-11.md.
Toda prueba que active el receiver debe restaurar
`COMPONENT_ENABLED_STATE_DEFAULT` y toda subclase de prueba debe abrir una base
sintética: `DexcomStorageGuard` rechaza la real.

PENDIENTE DEL PROPIETARIO (ADR 0018 a 0022)
- Decidir si Diagnóstico muestra un contador duradero de fallos del receiver
  (almacenamiento R10 y fase síncrona R11).
- Revisar las decisiones técnicas T1 a T6 (ADR 0019), E5 a E9 (ADR 0020), F3
  a F5 (ADR 0021) y R4 a R9 (ADR 0022).
- Para el receiver productivo: identificar la APK instalada mediante solo
  lectura y definir/probar en Next una política compatible con el transporte
  existente y sus limitaciones, sin cambiar Dexcom. No reabrir la elección del
  productor ni presentar su modificación como un requisito pendiente.

FLUJO DE TRABAJO SIMPLIFICADO (2026-10-11)
El propietario sustituye el paso obligatorio «ADR + preguntas + aprobación»
para cada incremento técnico por entregas funcionales revisables según
docs/EXECUTION_PLAN.md §3.3. Un ADR breve cubre cambios estructurales o de
seguridad relevantes; las decisiones rutinarias dentro del alcance autorizado
se resuelven durante la implementación. Preguntar juntos los puntos clínicos
o de alcance pendientes y aprovechar aprobaciones ya registradas.
Conservar PR a `main`, revisión automática de Codex, corrección y regresión de
hallazgos válidos, respuesta y resolución de hilos, y CI «Verify» (Windows)
en verde antes de fusionar. No repetir rondas sin cambios o motivos pendientes.
En sesiones cloud sin SDK: instalar Android SDK 36
(commandlinetools + `platforms;android-36`, `build-tools;36.0.0`) en el
directorio temporal y exportar `ANDROID_HOME`; PowerShell 7 portátil sirve para
probar bloques de `verify.ps1` con rutas Linux. Maven Central puede devolver
429: reintentar Gradle. No hay KVM: las pruebas de dispositivo solo las ejecuta
el propietario.

ANTECEDENTES DE ALCANCE (la prioridad actual está al inicio)
Los ADR 0014, 0015, 0017 a 0022 (con R10 y R11) y la opción A del ADR 0016
están implementados. La recepción real y el uso clínico requieren todavía sus
decisiones específicas; el ajuste de flujo no las aprueba. El
receiver existe desactivado y con política `Pending` (ADR 0022). Antes de la
validación con Dexcom real, Next debe resolver la incompatibilidad de su política
con el productor instalado y registrar sus garantías y limitaciones. Ampliar el
ADR existente para esa entrega (permiso, política compatible y prueba
offline sin cortar el Bluetooth del sensor, según el ADR 0022).
Sin esas puertas, siguen abiertos sin bloqueo externo: el ADR de retención
(E4) y, fuera de Dexcom, P4 (franjas en cambios de hora) y P6
(exportación/importación de copias) del ADR 0012.
La falta de autorización clínica no bloquea trabajo técnico independiente
dentro del objetivo autorizado. Solo decisiones de alcance, clínicas o de
seguridad pendientes requieren aprobación específica. Conectar el traductor con `ReadOverview`, el motor u
otro consumidor exige su propio ADR. Siguen
abiertas como decisiones del propietario, y cualquier avance clínico necesita
antes su ADR: límites clínicos, vigencia (sin política aprobada), resolución de
franjas en cambios de hora (P4 del ADR 0012), exportación/importación de copias
(P6 del ADR 0012), DIA e IOB. Cálculo y tratamiento siguen bloqueados.

TRABAJO
1. Lee AGENTS.md, README, arquitectura, plan y ADRs relacionados. Actualiza origin,
   verifica Git/CI y crea una rama corta desde origin/main.
2. Si necesitas Legacy, consulta primero el mapa de fuentes y fija el SHA
   remoto; acceso exclusivamente de lectura.
3. Define una capacidad completa y sus criterios. Redacta o amplía un ADR si
   cambia arquitectura, contrato relevante o seguridad; agrupa las incógnitas
   que requieran al propietario. No inventes límites ni reabras aprobaciones.
4. Mantén UI, caso de uso y SQLite separados. Conecta Dexcom solo dentro del
   alcance y las puertas aprobadas para el hito. No añadas red, MFP, NAS,
   Render, sync ni autoridad de tratamiento fuera del alcance autorizado.
5. Según el cambio, prueba ausencia frente a cero, franjas incompletas,
   solapadas o que cruzan medianoche, unidad sin reinterpretación, conflicto, reintento idéntico,
   reinicio, fallo de escritura y migraciones si cambian.
6. Ejecuta scripts/verify.ps1 al cerrar la entrega. Añade -DeviceTests cuando
   el comportamiento Android lo requiera, avisando antes de usar el único
   dispositivo autorizado. Usa repositorios sintéticos aislados y no cortes
   la recepción del sensor. No exigir USB para cambios solo documentales.
7. Actualiza documentación, publica mediante PR, integra y verifica CI del SHA
   exacto, ancestralidad en origin/main y árbol limpio.
8. Actualiza la continuación activa con el estado real y el siguiente objetivo,
   enlazando la evidencia sin volver a copiar todo el historial de la entrega.

No repitas auditorías ni copies catálogos.
