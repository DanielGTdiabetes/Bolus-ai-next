# ADR 0009: selección local de una revisión para Bolo

- Estado: aceptado para revisión de borradores; no habilita uso clínico.
- Fecha: 2026-09-26.
- Fase: incremento preparatorio de fases 2, 4 y 7, posterior al ADR 0008.
- Base Next: `79f6ee397d13c9f2d3cc74ea109c606b9739717e`.

## Decisión

`shared/meal-drafts` incorpora `MealSelection`, `MealSelectionRepository` y
`ReviewMealSelection`. La selección contiene un snapshot inmutable identificado
por ID y revisión, junto a la última revisión guardada del mismo registro. Los
estados son ausente, revisada y fallo explícito. Una revisión posterior determina
`meal.selection.obsolete`; nunca sustituye el snapshot de forma automática.
Incluso la selección coincidente mantiene `meal.draft.not_clinically_validated`,
`allowsCalculation = false` y `allowsTreatment = false`.

La referencia se guarda durablemente en SQLite antes de presentarla como
seleccionada. La base pasa de v1 a v2 añadiendo `meal_selection`: un único slot de
trabajo que referencia `(id, revision)` de `meal_revisions`. Los registros de
comida mantienen `schemaVersion = 1`. La migración valida la lectura del esquema
anterior y añade la tabla sin modificar las revisiones. La selección es estado de
revisión editable, no historial clínico: cambiar el slot no borra ni reescribe una
comida, ingesta, recomendación o confirmación.

La selección compara el registro completo contra la última revisión dentro de
una transacción. Un editor sin guardar, una identidad inexistente, una revisión
obsoleta o un contenido distinto no pueden quedar seleccionados como actuales.
Un reintento idéntico tiene el mismo efecto. La lectura obtiene referencia,
snapshot y última revisión en una sola transacción; un error no se presenta como
selección ausente ni permite seguir mostrando macros almacenados como actuales.

## Presentación y ciclo de vida

Mis platos y Comidas ofrecen «Usar esta revisión en Bolo» para revisiones
guardadas. Un editor con cambios pendientes no permite seleccionar su contenido.
Bolo muestra los seis campos exactos, las ausencias, base/unidades declaradas,
clase, identidad, revisión y referencia de copia cuando existe. No convierte
texto a número ni infiere unidades.

Al entrar o regresar a Bolo, reanudar la actividad o guardar una revisión se
consulta SQLite. Mientras se lee no se afirma vigencia. Una lectura anterior en
vuelo no reemplaza el resultado de una nueva selección. Rotación y relanzamiento
recuperan la referencia durable. El botón de edición abre explícitamente la última
revisión; guardar no cambia el snapshot seleccionado. Para adoptarla se requiere
volver a pulsar «Usar esta revisión en Bolo».

La obsolescencia se refiere al registro seleccionado. Una copia de plato es un
borrador independiente según ADR 0008; editar el plato de origen no modifica esa
copia ni su referencia de procedencia.

## Límites y portabilidad

No se introduce ninguna regla clínica, validación nutricional, ingesta, reloj,
sincronización ni red. No se consulta ni modifica Legacy. El futuro cliente iOS
consumirá el mismo contrato KMP e implementará el puerto de selección con una
transacción local equivalente. iOS sigue sin soporte verificado en este host.

Quedan pendientes retirar explícitamente la selección, consultar revisiones
históricas desde la UI y las reglas aprobadas para validación nutricional. La
selección actual no autoriza completar ninguna fase clínica.

## Verificación

Pruebas comunes cubren identidad/revisión inconsistente, ausencia frente a cero,
fallos, obsolescencia y bloqueo universal. Instrumentación con datos sintéticos
cubre transacciones, reintento, conflicto, rollback, reinicio, migración v1 → v2 y
reapertura de una copia previa a migración. La UI recorre plato y borrador,
rotación, relanzamiento, edición, obsolescencia, reselección y fallo de lectura.
