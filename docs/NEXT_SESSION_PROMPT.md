Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

OBJETIVO DEL PROYECTO
Recrear Bolus AI en móvil con cálculo, perfil, IOB, comidas e historial locales.
El camino crítico debe funcionar sin NAS ni Render. Seguridad > consistencia >
disponibilidad > comodidad.

ESTADO REAL
Mis platos y Comidas permiten crear, editar y recuperar borradores versionados.
Bolo muestra una revisión seleccionada con campos exactos, ausencias, base,
identidad y origen. La selección persiste en SQLite v2; una corrección posterior
la marca obsoleta y exige reselección explícita. Ahora se puede retirar la
selección coincidente u obsoleta desde Bolo, sin borrar platos, borradores ni
revisiones. La ausencia se confirma tras el commit y persiste al relanzar. Un
fallo muestra el motivo, permite releer y no aparenta éxito. Las lecturas previas
en vuelo no pueden reponer una selección mientras se retira.
La reanudación conserva el editor, foco y scroll. La UI sigue el tema claro/oscuro
del sistema y dispone de pruebas de layouts anchos y texto ampliado.

Consulta docs/adr/0008-local-meal-drafts.md, docs/adr/0009-local-meal-selection.md
y docs/validation/local-meal-selection-removal-2026-09-26.md.
Comprueba el CI del SHA exacto integrado antes de empezar.
Esta entrega no necesitó consultar ni modificar Legacy. Su última auditoría
documentada continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes
que ese SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
Los macros son textos sin validar/convertir; ausencia nunca equivale a cero. Solo
se muestran gramos cuando el usuario declaró esa base. No hay recepción Dexcom,
perfil confirmado, IOB local, catálogo ni historial clínico. iOS sigue pendiente
de verificación. Seleccionar o retirar no crea ingesta, recomendación, confirmación,
sync ni tratamiento. Cálculo y confirmación clínica siguen bloqueados. Las copias
de platos siguen siendo borradores independientes.

SIGUIENTE INCREMENTO PROPUESTO
Permitir consultar las revisiones guardadas de un plato o borrador desde su
biblioteca. Mostrar sus textos, ausencias, identidad y origen de forma explícita
sin sobrescribir registros ni sustituir automáticamente la selección de Bolo.
Es consulta del historial de borradores, no historial clínico ni restauración.

TRABAJO
1. Lee AGENTS.md, README, arquitectura, plan y ADRs relacionados. Actualiza origin,
   verifica Git/CI y crea una rama corta desde origin/main.
2. Añade una consulta al puerto/caso de uso compartido para leer las revisiones
   de un registro con orden explícito y fallos distinguibles de ausencia.
3. Añade un acceso desde Mis platos y Comidas con presentación de solo lectura.
   Conserva editor pendiente y selección; no habilites selección de revisiones
   históricas, restauración, borrado ni reglas nutricionales.
4. Prueba registro ausente, varias revisiones, campos ausentes frente a cero,
   error de lectura, reinicio y navegación conservando selección y bloqueos.
5. Mantén UI, caso de uso y SQLite separados. No añadas red, Dexcom, MFP,
   NAS, Render, sync ni autoridad de tratamiento.
6. Ejecuta scripts/verify.ps1 y pruebas USB si hay un único dispositivo autorizado;
   usa repositorios sintéticos aislados, sin datos personales ni cambios de red.
7. Actualiza documentación, publica mediante PR, integra y verifica CI del SHA
   exacto, ancestralidad en origin/main y árbol limpio.
8. Actualiza este prompt con el estado real y el siguiente incremento directo.

No repitas auditorías ni copies catálogos. Si necesitas Legacy, consulta primero
el mapa de fuentes y fija el SHA remoto; acceso exclusivamente de lectura.
