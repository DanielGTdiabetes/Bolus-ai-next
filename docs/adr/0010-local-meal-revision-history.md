# ADR 0010: consulta de solo lectura de revisiones guardadas

- Estado: aceptado para revisión de borradores; no habilita uso clínico.
- Fecha: 2026-09-27.
- Fase y criterio de aceptación: incremento preparatorio de fases 2, 4 y 7,
  posterior a los ADR 0008 y 0009. Mostrar todas las revisiones de un plato o
  borrador con textos exactos, ausencias, identidad y origen, sin escribir.
- Responsables de decisión: propietario del proyecto.
- Evidencia clínica requerida: no aplica. No se introduce ninguna regla ni
  parámetro clínico.
- Base Next: `dcb3a3d` (PR #24 integrada).

## Contexto

`meal_revisions` conserva cada revisión guardada (ADR 0008), pero la UI solo
mostraba la última y el snapshot seleccionado en Bolo (ADR 0009). Revisar qué
cambió exigía confiar en la memoria del usuario. Una consulta mal planteada
podría confundirse con restauración, historial clínico o selección, o presentar
un fallo de lectura como «sin revisiones».

## Decisión

`shared/meal-drafts` incorpora `MealHistory`, `MealHistoryRepository` y
`ReadMealHistory`:

- `Loaded`: todas las revisiones, ordenadas de la más reciente a la más antigua.
  El caso de uso ordena de forma explícita y exige que sean contiguas hasta la 1,
  con la misma identidad, clase y referencia de copia. Huecos, duplicados o deriva
  de identidad devuelven `meal.storage.invalid_record`; nunca se muestra una
  historia parcial como completa.
- `Missing` (`meal.history.missing`): la lectura terminó bien y no existe ninguna
  revisión para ese ID.
- `Failed`: conserva el motivo de almacenamiento. Nunca equivale a ausencia.

Un ID en blanco se rechaza sin tocar el almacenamiento. Todos los estados
mantienen `allowsCalculation = false` y `allowsTreatment = false`; el estado
cargado expone `meal.history.read_only`.

El puerto Android lee `meal_revisions` con una única sentencia y reutiliza el
decodificador existente. No cambia el esquema: SQLite sigue en v2 y no hay
migración.

## Presentación

Mis platos y Comidas ofrecen «Ver revisiones guardadas · solo lectura» en cada
registro de la biblioteca y en el editor de un registro guardado. La vista muestra
recuento, última revisión, identidad, origen y, por revisión, los seis campos
exactos, «Ausente · sin introducir» frente a `0` escrito y la base declarada.
No ofrece seleccionar, restaurar, editar, borrar ni interpretar macros.

El editor abierto permanece en el `ViewModel`: al volver (botón o Atrás) se
recuperan sus cambios sin guardar. Abrir un editor explícitamente cierra la
consulta. El destino de consulta se conserva en rotación y en el estado guardado
de la actividad. Una lectura cerrada o sustituida no puede mostrar su resultado
tardío. La selección de Bolo no se lee ni se modifica.

## Alternativas consideradas

- Añadir la consulta a `MealRepository.read`: mezclaba «últimas revisiones» y
  «todas las revisiones de un registro» en el mismo contrato.
- Permitir seleccionar una revisión antigua: contradice el ADR 0009, que solo
  acepta la última revisión, y requiere su propia decisión.
- Restaurar una revisión como nueva: es una escritura con semántica propia; queda
  fuera de este incremento.

## Consecuencias

Consulta local, sin red, sin reloj y sin cambios de esquema. Rollback: revertir
el commit; no hay datos nuevos que migrar. iOS implementará el mismo puerto sobre
su almacenamiento local; sigue sin verificación de plataforma.

## Seguridad y pruebas

Pruebas comunes: orden, texto exacto, ausencia frente a cero, ausencia frente a
todos los fallos, ID en blanco y rechazo de historias inconsistentes. SQLite en
dispositivo: reinicio, ausencia, clase incorrecta, selección y filas intactas,
tabla perdida y esquema desconocido. UI en dispositivo: plato y borrador,
recreación, Atrás, editor pendiente, reintento explícito, resultado tardío
descartado y layouts anchos con texto ampliado. Cálculo y confirmación siguen
bloqueados.

## Condiciones para revisar este ADR

Aprobación de restaurar o seleccionar revisiones antiguas, un historial clínico
de ingestas o un cambio de esquema de `meal_revisions`.
