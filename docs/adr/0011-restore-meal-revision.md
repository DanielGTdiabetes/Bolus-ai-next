# ADR 0011: restaurar una revisión guardada como nueva revisión de trabajo

- Estado: aceptado para revisión de borradores; no habilita uso clínico.
- Fecha: 2026-09-27.
- Fase y criterio de aceptación: incremento preparatorio de fases 2, 4 y 7,
  posterior a los ADR 0008, 0009 y 0010. Cargar explícitamente una revisión
  anterior en el editor y, al guardar, crear la revisión siguiente registrando de
  qué revisión procede, sin reescribir revisiones ni cambiar la selección de Bolo.
- Responsables de decisión: propietario del proyecto. Decisión de procedencia
  tomada por el propietario el 2026-09-27: registrar la revisión de origen.
- Evidencia clínica requerida: no aplica. No se introduce ninguna regla ni
  parámetro clínico.
- Base Next: `1fe8185` (PR #26 integrada).
- Amplía el ADR 0010, que dejaba la restauración fuera de alcance.

## Contexto

La consulta del ADR 0010 permite ver todas las revisiones, pero recuperar una
anterior obligaba a copiar los textos a mano. Una restauración mal planteada
podría reescribir el historial, saltarse el control de conflicto, cambiar la
selección de Bolo o hacer pasar una edición manual por una restauración.

## Decisión

### Procedencia

Cada revisión puede registrar `restoredFrom`: el número de una revisión anterior
del mismo registro cuyo contenido se cargó como punto de partida. Es un dato de
trazabilidad, no una afirmación de igualdad: el usuario puede modificar los
campos antes de guardar.

- `null` significa «revisión no creada desde una restauración». Todas las filas
  anteriores a esta versión reciben `null`, lo cual es exacto porque la
  restauración no existía.
- Invariante de revisión guardada: `1 <= restoredFrom < revision - 1`. Restaurar
  la última revisión no tiene sentido y se rechaza.
- `MealRecord` exige `restoredFrom in 1 until revision` (en un editor, `revision`
  es la última guardada que va a sustituir). El guardado y la consulta aplican la
  cota estricta de revisión guardada.
- `schemaVersion` del registro sigue en 1: el campo es opcional y su ausencia
  tiene un significado explícito.

### Dominio (`shared/meal-drafts`)

- `MealDrafts.restore(history, revision)` es puro: exige `MealHistory.Loaded` y
  una revisión estrictamente anterior a la última. Devuelve un editor con la
  identidad, clase, copia de origen y número de la última revisión, el contenido
  exacto de la revisión elegida (ausencias y `0` incluidos) y `restoredFrom`. No
  lee ni escribe almacenamiento. Una revisión inexistente o la última devuelve
  `meal.storage.invalid_record`.
- `MealDrafts.change(editor, content, baseline, restoreSource)` aplica una edición:
  mantiene la procedencia solo si el editor empezó en una restauración. Un editor
  abierto sobre una revisión guardada pierde la procedencia heredada al cambiar,
  y si el contenido vuelve al guardado recupera exactamente la revisión guardada.
- El guardado reutiliza `MealRepository.save`: mismo control de conflicto, mismo
  reintento idéntico idempotente. Si otra escritura creó una revisión posterior,
  el guardado falla con `meal.storage.revision_conflict` y el editor conserva los
  campos.

### SQLite v3

`meal_revisions` añade `restored_from INTEGER` con
`CHECK(restored_from IS NULL OR (restored_from > 0 AND restored_from < revision - 1))`.
No hay clave foránea compuesta porque `ALTER TABLE` no puede añadirla. El
repositorio comprueba dentro de la transacción de guardado que la revisión de
origen existe y la consulta exige revisiones contiguas.

Migraciones:

- v1 → v3: añade la columna, valida y decodifica todas las filas y crea
  `meal_selection`.
- v2 → v3: añade la columna y valida todas las filas. La selección se conserva.
- Todo en la transacción de `onUpgrade`; cualquier fila inválida revierte la
  migración y falla cerrado. Versiones desconocidas o degradación siguen fallando
  con `meal.storage.unsupported_schema`.

No se reescribe ninguna revisión. Rollback: la copia de seguridad previa abre con
la versión anterior. Una base v3 no abre con una app anterior (degradación no
soportada), lo cual es fail-closed.

## Presentación

En la consulta de revisiones cada revisión anterior a la última ofrece
«Restaurar esta revisión como nueva revisión…». Pulsarlo muestra una
confirmación en la propia pantalla que indica qué revisión se carga, qué número
tendrá al guardar, que nada guardado ni la selección de Bolo cambian y, si hay un
editor con cambios sin guardar, que se descartarán. Solo el botón de confirmación
carga el editor; cancelar vuelve a la consulta. La confirmación pendiente
sobrevive a rotación y recreación.

El editor restaurado muestra la revisión de partida y el número que se creará.
Hasta guardar no cambia nada almacenado. Al volver a la biblioteca se pide
descartar los cambios. La consulta muestra la procedencia de cada revisión
restaurada.

## Alternativas consideradas

- Sin procedencia ni cambio de esquema: más simple, pero el historial no
  distinguía restauración de edición manual.
- Tabla de eventos de restauración aparte: más flexible, pero duplica identidad y
  añade una escritura adicional sin necesidad actual.
- Seleccionar directamente una revisión antigua en Bolo: contradice el ADR 0009.

## Consecuencias

Local, sin red, sin reloj. La selección de Bolo no se lee ni se escribe al
restaurar; al guardar, una selección de ese registro pasa a obsoleta por la
regla existente del ADR 0009. iOS implementará la misma columna y la misma
invariante en su almacenamiento; sigue sin verificación de plataforma.

## Seguridad y pruebas

Comunes: restaurar con texto exacto, ausencia frente a cero y procedencia;
rechazo de la última revisión y de revisiones inexistentes; regla de edición de
procedencia; invariantes de consulta. SQLite en dispositivo: guardado con
procedencia tras reinicio, reintento idéntico, conflicto con revisión posterior,
procedencia inválida, fallo de escritura con rollback, selección intacta y
migraciones v1 → v3 y v2 → v3 con copia de seguridad. UI en dispositivo:
confirmación explícita, cancelar, descartar editor pendiente, recreación,
conflicto visible y layouts anchos con texto ampliado. Cálculo y confirmación
siguen bloqueados.

## Condiciones para revisar este ADR

Seleccionar revisiones antiguas en Bolo, borrado o retención de revisiones,
historial clínico de ingestas o sincronización de revisiones.
