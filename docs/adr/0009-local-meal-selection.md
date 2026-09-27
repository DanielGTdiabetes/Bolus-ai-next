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

La reanudación solo refresca la selección si el destino actual es Bolo, manual o
Bolo offline. Las notificaciones de lectura de selección se separan de las del
editor y solo reconstruyen esos destinos, incluso si una lectura termina después
de navegar a otra pantalla. Volver a un editor tras pausar conserva su vista,
texto sin guardar, foco, cursor y scroll.
La posición se captura en `onPause` y se restaura después del dibujo al volver a
destinos ajenos a Bolo, comprobando que no se haya navegado entretanto.

Los Views y el contenedor de scroll se miden según el espacio disponible, sin
fijar un ancho de teléfono ni bloquear orientación. Se verifican Bolo y el editor
en formatos anchos vertical/horizontal y texto ampliado; el estado de borradores
y la selección siguen independientes del tamaño de pantalla. Android recrea la
actividad ante cambios de configuración y recupera los datos con el ViewModel,
estado guardado y referencia SQLite existentes. No se introduce dependencia de
APIs específicas de Pixel; un cliente iOS futuro deberá adaptar su presentación
manteniendo los contratos compartidos.

La obsolescencia se refiere al registro seleccionado. Una copia de plato es un
borrador independiente según ADR 0008; editar el plato de origen no modifica esa
copia ni su referencia de procedencia.

## Límites y portabilidad

No se introduce ninguna regla clínica, validación nutricional, ingesta, reloj,
sincronización ni red. No se consulta ni modifica Legacy. El futuro cliente iOS
consumirá el mismo contrato KMP e implementará el puerto de selección con una
transacción local equivalente. iOS sigue sin soporte verificado en este host.

Quedan pendientes las reglas aprobadas para validación nutricional. La selección
actual no autoriza completar ninguna fase clínica. La consulta de revisiones
guardadas se resolvió en el [ADR 0010](0010-local-meal-revision-history.md), sin
permitir seleccionar revisiones antiguas.

## Ampliación: retirada explícita de la selección — 2026-09-26

`MealSelectionRepository.clearSelection` y `ReviewMealSelection.clear` retiran
únicamente el slot de revisión. La operación es local, transaccional e idempotente:
devuelve `MealSelection.Missing` después del commit, incluso si ya estaba ausente.
Un fallo devuelve `MealSelection.Failed` con el motivo de almacenamiento y no se
presenta como ausencia. Se conserva SQLite v2; no cambia el esquema ni se eliminan
o reescriben filas de `meal_revisions`.

Bolo ofrece «Retirar selección de Bolo · conservar comida guardada» tanto para
selecciones coincidentes como obsoletas. Mientras se persiste muestra guardado en
curso y bloquea otras escrituras del modelo. Invalida lecturas anteriores en
vuelo para que no repongan la selección. Al terminar refresca la pantalla para
restaurar también los controles si se navegó a una biblioteca durante la operación.
Si falla, oculta los macros, muestra el motivo y permite releer el estado durable
antes de volver a intentar la retirada explícitamente. Recreación y relanzamiento
leen el slot persistido; volver a usar una comida exige seleccionarla de nuevo.

Es una modificación reversible de estado de revisión, sin confirmación clínica
ni borrado de historial. iOS deberá implementar la misma operación transaccional
del puerto compartido; continúa sin verificación de plataforma. Véase la
[validación de retirada](../validation/local-meal-selection-removal-2026-09-26.md).

## Verificación

Pruebas comunes cubren identidad/revisión inconsistente, ausencia frente a cero,
fallos, obsolescencia y bloqueo universal. Instrumentación con datos sintéticos
cubre transacciones, reintento, conflicto, rollback, reinicio, migración v1 → v2 y
reapertura de una copia previa a migración. La UI recorre plato y borrador,
rotación, relanzamiento, edición, obsolescencia, reselección y fallo de lectura.
