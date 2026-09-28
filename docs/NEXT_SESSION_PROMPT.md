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
`shared/clinical-profile` y base propia `clinical-profile.db` v1: versiones
append-only con triggers, huella SHA-256 canónica revalidada al leer, origen
`manual`/`restored` (`system_proposal_accepted` reservado y rechazado), fecha y
escritor. Unidad de glucosa (mg/dL o mmol/L) y zona IANA declaradas por versión.
`carb_ratio` (g/U), `insulin_sensitivity` y `glucose_target` por franjas
completas en el modelo. La UI edita solo un valor de día completo por parámetro.
Las versiones con varias franjas se muestran en solo lectura. Decimal canónico no
negativo, 6 enteros y 3 decimales como límite de representación, no clínico.
«Sin configurar» distinto de `0`. Cambiar de unidad vacía ISF y objetivo y exige
guardar esa versión sin valores dependientes. Nada se convierte. Restaurar crea
una versión nueva sin tocar las anteriores. Conflicto e idempotencia como en
comidas. Auto Backup sigue desactivado y verify.ps1 lo comprueba junto con que el
motor y `ReadOverview` no dependen del perfil.

Consulta docs/adr/0012-local-clinical-profile.md y
docs/validation/local-clinical-profile-2026-09-27.md.
Comprueba el CI del SHA exacto integrado antes de empezar.
Esta entrega no consultó ni modificó Legacy. Su última auditoría documentada
continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes que ese
SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
El perfil es captura: no está «confirmado», no alimenta motor, Bolo, IOB ni
recomendación, y Bolo sigue mostrando «Perfil · sin versión confirmada». No hay
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

REVISIÓN PENDIENTE
El propietario revisa los resultados de la PR del perfil clínico antes de
empezar otro bloque funcional. No avances sin esa revisión.

SIGUIENTE INCREMENTO PROPUESTO (tras la revisión)
Editor de franjas horarias del perfil: crear, dividir, unir y editar franjas
de un parámetro sobre el modelo ya existente, con validación estructural
(cobertura completa, sin solapes ni cruce de medianoche), vista previa canónica y
las mismas garantías de versión, unidad, conflicto e idempotencia. Sin esquema
nuevo ni valores por defecto. Antes, un ADR breve que fije la interacción y cómo
se muestran franjas adyacentes iguales. El significado de «confirmado», los
límites aprobados, DIA e IOB siguen siendo decisiones del propietario.

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
