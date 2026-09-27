Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

OBJETIVO DEL PROYECTO
Recrear Bolus AI en móvil con cálculo, perfil, IOB, comidas e historial locales.
El camino crítico debe funcionar sin NAS ni Render. Seguridad > consistencia >
disponibilidad > comodidad.

ESTADO REAL
Mis platos y Comidas permiten crear, editar y recuperar borradores versionados.
Bolo muestra una revisión seleccionada con campos exactos, ausencias, base,
identidad y origen. La selección persiste en SQLite v2; una corrección posterior
la marca obsoleta y exige reselección explícita. Se puede retirar la selección
desde Bolo sin borrar platos, borradores ni revisiones.
Ahora cada plato o borrador permite consultar todas sus revisiones guardadas en
solo lectura, de la más reciente a la más antigua, desde la biblioteca o el
editor. Fallo, ausencia e historia inconsistente son estados distintos. La
consulta no escribe, no toca la selección de Bolo y vuelve al editor abierto
conservando sus cambios sin guardar.
La UI sigue el tema claro/oscuro del sistema y dispone de pruebas de layouts
anchos y texto ampliado.

Consulta docs/adr/0008-local-meal-drafts.md, docs/adr/0009-local-meal-selection.md,
docs/adr/0010-local-meal-revision-history.md y
docs/validation/local-meal-revision-history-2026-09-27.md.
Comprueba el CI del SHA exacto integrado antes de empezar.
Esta entrega no necesitó consultar ni modificar Legacy. Su última auditoría
documentada continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes
que ese SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
Los macros son textos sin validar/convertir; ausencia nunca equivale a cero. Solo
se muestran gramos cuando el usuario declaró esa base. No hay recepción Dexcom,
perfil confirmado, IOB local, catálogo ni historial clínico. iOS sigue pendiente
de verificación. Seleccionar, retirar o consultar revisiones no crea ingesta,
recomendación, confirmación, sync ni tratamiento. No se pueden seleccionar ni
restaurar revisiones antiguas. Cálculo y confirmación clínica siguen bloqueados.
Las pruebas USB necesitan el Pixel desplegado, desbloqueado y con pantalla
encendida; plegado y apagado falla la medición de la barra inferior.

SIGUIENTE INCREMENTO PROPUESTO
Restaurar explícitamente una revisión antigua como nueva revisión de trabajo.
Cargarla en el editor como contenido de partida, sin reescribir revisiones
existentes ni cambiar la selección de Bolo; guardar crea la revisión siguiente
con el control de conflicto actual. Requiere una ampliación del ADR 0010 que
decida si la nueva revisión registra de qué revisión procede.

TRABAJO
1. Lee AGENTS.md, README, arquitectura, plan y ADRs relacionados. Actualiza origin,
   verifica Git/CI y crea una rama corta desde origin/main.
2. Decide y documenta la procedencia de la restauración antes de programar. Si
   cambia el esquema, versiona la migración v2 → v3 con prueba desde cada versión.
3. Añade la acción desde la consulta de revisiones con confirmación explícita.
   Conserva el editor pendiente (pide descartar si hay cambios) y la selección.
4. Prueba conflicto con una revisión posterior, reintento idéntico, ausencia
   frente a cero, reinicio, fallo de escritura y que la selección no cambia.
5. Mantén UI, caso de uso y SQLite separados. No añadas red, Dexcom, MFP,
   NAS, Render, sync ni autoridad de tratamiento.
6. Ejecuta scripts/verify.ps1 -DeviceTests con un único dispositivo autorizado;
   usa repositorios sintéticos aislados, sin datos personales ni cambios de red.
7. Actualiza documentación, publica mediante PR, integra y verifica CI del SHA
   exacto, ancestralidad en origin/main y árbol limpio.
8. Actualiza este prompt con el estado real y el siguiente incremento directo.

No repitas auditorías ni copies catálogos. Si necesitas Legacy, consulta primero
el mapa de fuentes y fija el SHA remoto; acceso exclusivamente de lectura.
