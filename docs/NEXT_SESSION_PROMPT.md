Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

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
Comprueba el CI del SHA exacto integrado antes de empezar. La última
integración de código es la PR #36, implementación del ADR 0015 (merge commit
a57d2e5ce6497220a54e3ae632087c688a13f002, Verify 37142410544 en verde). Después se integró una PR solo
documental que registra esa integración: comprueba el SHA exacto de
`origin/main` y su CI.
Esta entrega no consultó ni modificó Legacy. Su última auditoría documentada
continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes que ese
SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
El perfil es captura. La confirmación de datos existe en dominio, base local
(parte 1 del ADR 0014) y pantalla (parte 2). No aprueba uso clínico: no
alimenta motor, Bolo, IOB ni recomendación, y cálculo y tratamiento siguen
bloqueados en todos los estados. Bolo muestra «Perfil · no se usa para
calcular» sin leer el perfil. No hay
límites clínicos, DIA, curva, redondeo, conversión de unidades, propuestas del
sistema, aprendizaje, exportación/importación, sync ni perfiles alternativos. La
resolución de franjas en cambios de hora queda para el ADR del motor. Los macros
de comida son textos sin validar/convertir; ausencia nunca equivale a cero. No
hay recepción Dexcom, IOB local, catálogo ni historial clínico. iOS sigue
pendiente de verificación. Cálculo y confirmación clínica siguen bloqueados.
Las pruebas USB necesitan el Pixel desbloqueado y con la pantalla encendida
durante toda la ejecución. Enviar `adb shell input keyevent KEYCODE_WAKEUP` cada
10 s mientras corre verify.ps1 lo evita sin cambiar ajustes. Toda clase nueva de
instrumentación debe añadirse a la lista `-e class` de scripts/verify.ps1.
Toda prueba que abra Ajustes → Cálculo debe inyectar
`MainActivity.profileRepositoryFactory` en memoria o sobre un fichero sintético
(la fábrica devuelve `ClosableProfileRepository`; `ControlledProfileRepository`
de androidTest falla lecturas o escrituras y retiene escrituras).
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

ADR 0016 — PRIMER CONSUMIDOR DEL INFORME DEL PERFIL (aceptado, A implementada)
docs/adr/0016-profile-unavailability-consumer.md conecta el traductor del ADR
0015 solo con la pantalla del perfil (Ajustes → Cálculo), como bloque técnico
plegado. Aceptado el 2026-10-04 con A1 a A6, A4 precisada (solo se captura el
rechazo conocido del traductor) y B1 solo como planificación: Bolo sigue sin
conectarse. Integrado como documentación mediante la PR #38. La opción A está
implementada en la rama `claude/profile-unavailability-screen`, pendiente de
revisión. Véanse la sección 11 del ADR y
docs/validation/profile-unavailability-screen-2026-10-04.md:
- `ProfileUnavailabilityBlock` (puro) y `ClinicalProfileModel.unavailability`,
  derivada de `gate` en cada acceso. `gate` nulo se traduce como E1.
- `ClinicalProfileScreen` añade el bloque (tags `profile:unavailability` y
  `profile:unavailability:toggle`) en la vista principal. `MainActivity`
  recuerda si está abierto sin guardarlo: se pliega tras recreación.
- `ClinicalProfileUnavailabilityDeviceTest` en la lista de `verify.ps1`.

SIGUIENTE PASO
Los ADR 0014 y 0015 están implementados e integrados. La opción A del ADR 0016
está implementada y espera la revisión del propietario. No la integres ni
empieces otro incremento sin una petición explícita. B1 (Bolo) es solo
planificación: no conectes Bolo sin su ADR. Conectar el traductor con Bolo,
`ReadOverview`, el motor u otro consumidor exige su propio ADR. Siguen
abiertas como decisiones del propietario, y cualquier avance clínico necesita
antes su ADR: límites clínicos, vigencia (sin política aprobada), resolución de
franjas en cambios de hora (P4 del ADR 0012), exportación/importación de copias
(P6 del ADR 0012), DIA e IOB. Cálculo y tratamiento siguen bloqueados.

TRABAJO
1. Lee AGENTS.md, README, arquitectura, plan y ADRs relacionados. Actualiza origin,
   verifica Git/CI y crea una rama corta desde origin/main.
2. Si necesitas Legacy, consulta primero el mapa de fuentes y fija el SHA
   remoto; acceso exclusivamente de lectura.
3. Redacta el ADR antes de programar. Declara incógnitas como decisiones
   pendientes del propietario; no inventes límites.
4. Mantén UI, caso de uso y SQLite separados. No añadas red, Dexcom, MFP, NAS,
   Render, sync ni autoridad de tratamiento.
5. Prueba ausencia frente a cero, franjas incompletas, solapadas o que cruzan
   medianoche, unidad sin reinterpretación, conflicto, reintento idéntico,
   reinicio, fallo de escritura y migraciones si cambian.
6. Ejecuta scripts/verify.ps1 -DeviceTests con un único dispositivo autorizado;
   usa repositorios sintéticos aislados, sin datos personales ni cambios de red.
7. Actualiza documentación, publica mediante PR, integra y verifica CI del SHA
   exacto, ancestralidad en origin/main y árbol limpio.
8. Actualiza este prompt con el estado real y el siguiente incremento directo.

No repitas auditorías ni copies catálogos.
