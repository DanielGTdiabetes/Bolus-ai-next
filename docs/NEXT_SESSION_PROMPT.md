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
`shared/clinical-profile` y base propia `clinical-profile.db` (v1 en main, v2
con la parte 1 del ADR 0014): versiones append-only con triggers, huella
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

Consulta docs/adr/0012-local-clinical-profile.md,
docs/adr/0013-clinical-profile-segment-editor.md,
docs/adr/0014-clinical-profile-confirmation-eligibility.md,
docs/validation/profile-segment-editor-2026-09-28.md y
docs/validation/profile-confirmation-domain-2026-10-03.md.
Comprueba el CI del SHA exacto integrado antes de empezar.
Esta entrega no consultó ni modificó Legacy. Su última auditoría documentada
continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes que ese
SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
El perfil es captura. La confirmación de datos existe en dominio y base local
(parte 1 del ADR 0014), pero no en pantalla, y no aprueba uso clínico: no
alimenta motor, Bolo, IOB ni recomendación, y Bolo sigue mostrando
«Perfil · sin versión confirmada» hasta la parte 2. No hay
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
`MainActivity.profileRepositoryFactory` en memoria o sobre un fichero sintético.
En Windows, los XML de recursos están en CRLF en la copia de trabajo: al editarlos
con scripts conserva los finales de línea o `git diff --check` fallará.

ADR 0014 — CONFIRMACIÓN DE DATOS DEL PERFIL (parte 1 implementada, en revisión)
ADR 0014 (docs/adr/0014-clinical-profile-confirmation-eligibility.md), aceptado
el 2026-10-03 con C1 a C9 según su recomendación (C7 como solución
provisional). Confirmar datos es revisar una versión guardada identificada por
número y huella. No aprueba límites, vigencia ni aptitud para calcular o tratar.

Parte 1 en la rama `claude/profile-confirmation-domain` y su PR, sin integrar
hasta la revisión del propietario. Véase
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
  exclusiva. `AndroidOperationIds` existe, pero ninguna pantalla lo usa aún.
- Pruebas: 87 comunes y `OK (98 tests)` en dispositivo, con
  `ClinicalProfileConfirmationDeviceTest` en `verify.ps1`. Las pruebas que
  manipulan filas recrean el trigger con su texto exacto. Si no, la apertura
  falla con `unsupported_schema`.
La UI no cambia: no hay botones de confirmar ni retirar, Bolo sigue con
«Perfil · sin versión confirmada» y ni Bolo, `ReadOverview` ni el motor leen el
perfil. `input.profile.*` no se implementa ni se conecta.

SIGUIENTE INCREMENTO PROPUESTO (solo con petición explícita del propietario)
Primero, revisión del propietario e integración de la PR de la parte 1, con CI
del SHA exacto en verde. Después, y solo con autorización, la parte 2 del ADR
0014: vista de solo lectura de la última versión con «Revisar y confirmar los
datos de la versión N…» construida desde una lectura nueva, nunca dentro del
editor ni con cambios, textos pendientes, panel abierto o cambio de unidad
pendiente. Confirmar y retirar con `AndroidOperationIds`, con `operation_id` y
carga útil conservados en el estado guardado y resueltos por identidad al
recrear (`ClinicalProfiles.resolvePending`). Textos de la sección 9, siempre con
«Cálculo todavía bloqueado», historial con confirmaciones, retiradas y
superadas, y Bolo con «Perfil · no se usa para calcular» sin leer el perfil.
Pruebas de UI de la sección 10: doble toque, rotación, commit antes de recrear,
E5 a E11, layouts 840×900, 900×840 y 411×914 dp, texto 1,0× y 1,8×, claro y
oscuro. Límites clínicos, DIA, IOB, resolución de franjas en cambios de hora,
`input.profile.*` y la opción B o C de la sección 6.3 siguen siendo decisiones
del propietario.

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
