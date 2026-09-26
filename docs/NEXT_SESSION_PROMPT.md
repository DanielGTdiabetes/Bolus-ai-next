Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

OBJETIVO DEL PROYECTO
Recrear Bolus AI en móvil con cálculo, perfil, IOB, comidas e historial locales.
El camino crítico debe funcionar sin NAS ni Render. Seguridad > consistencia >
disponibilidad > comodidad.

ESTADO REAL
Mis platos y Comidas permiten crear, editar y recuperar borradores versionados.
Ahora se puede usar una revisión guardada en Bolo para revisar sus campos exactos,
ausencias, base declarada, ID, revisión y origen. La selección persiste en SQLite
v2 como referencia a una revisión inmutable; meal_revisions conserva schema v1.
Una corrección posterior marca la selección como obsoleta, conserva el snapshot y
exige reselección explícita. Guardar y seleccionar no crean ingesta, recomendación,
confirmación clínica, sync ni tratamiento. Cálculo y confirmación siguen bloqueados.
La reanudación conserva el editor, foco y scroll. La última verificación incluye
el Pixel 10 Pro Fold desplegado y layouts anchos con texto ampliado.
La UI dispone de tema claro/oscuro según el sistema, sin selector propio ni
cambios clínicos. Consulta docs/validation/android-dark-theme-2026-09-26.md.

Consulta docs/adr/0009-local-meal-selection.md y
docs/validation/local-meal-selection-2026-09-26.md, además del ADR 0008.
Comprueba el CI del SHA exacto integrado antes de empezar.
Esta entrega no necesitó consultar ni modificar Legacy. Su última auditoría
documentada continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes
que ese SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
Los macros son textos sin validar/convertir; ausencia nunca equivale a cero. Solo
se muestran gramos cuando el usuario declaró esa base. No hay recepción Dexcom,
perfil confirmado, IOB local, catálogo ni historial clínico. iOS sigue pendiente
de verificación. Una coincidencia con la última revisión no significa validez
nutricional ni clínica. Las copias de platos siguen siendo borradores independientes.

SIGUIENTE INCREMENTO PROPUESTO
Permitir retirar explícitamente la selección de entrada de Bolo, sin borrar ni
reescribir platos, borradores o revisiones. Mostrar claramente que vuelve al
estado sin selección y conservar esa decisión tras reiniciar. Es la continuación
directa del ciclo de revisión local y no requiere inventar reglas nutricionales.

TRABAJO
1. Lee AGENTS.md, README, arquitectura, plan y ADRs relacionados. Actualiza origin,
   verifica Git/CI y crea una rama corta desde origin/main.
2. Añade la operación al puerto/caso de uso compartido. Debe ser local, atómica,
   idempotente y devolver un resultado explícito solo tras persistir.
3. Añade una acción inequívoca en Bolo para retirar la selección. Un fallo conserva
   el bloqueo, muestra el motivo y nunca presenta como completada una escritura
   fallida. No borres ni modifiques las revisiones de comida.
4. Prueba ausencia, selección coincidente/obsoleta, reintento, fallo de escritura,
   reinicio y reselección posterior; verifica que todos los bloqueos se mantienen.
5. Mantén UI, caso de uso y SQLite separados. No añadas reglas clínicas, red,
   Dexcom, MFP, NAS, Render, sync ni autoridad de tratamiento.
6. Ejecuta scripts/verify.ps1 y pruebas USB si hay un único dispositivo autorizado;
   usa repositorios sintéticos aislados, sin datos personales ni cambios de red.
7. Actualiza documentación, publica mediante PR, integra y verifica CI del SHA
   exacto, ancestralidad en origin/main y árbol limpio.
8. Actualiza este prompt con el estado real y el siguiente incremento directo.

No repitas auditorías ni copies catálogos. Si necesitas Legacy, consulta primero
el mapa de fuentes y fija el SHA remoto; acceso exclusivamente de lectura.
