# Restauración de revisiones como nueva revisión — 2026-09-27

Base Next: `1fe8185` (PR #26 integrada). CI de esa base comprobado antes de
editar: [Verify 36329800266](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36329800266),
conclusión `success` para el SHA exacto. Incremento preparatorio de fases 2, 4 y 7
conforme al [ADR 0011](../adr/0011-restore-meal-revision.md). Procedencia decidida
por el propietario: registrar la revisión de origen.

## Comportamiento y límites

En «Ver revisiones guardadas» cada revisión anterior a la última ofrece
«Restaurar como nueva revisión». Pulsarlo solo muestra una confirmación en
pantalla: revisión que se carga, número que tendrá al guardar, que nada guardado
ni la selección de Bolo cambian y, si hay un editor con cambios sin guardar, que
se descartarán. Cancelar vuelve a la consulta. Confirmar carga el editor con los
textos exactos, ausencias y base de esa revisión, y la identidad, clase, copia de
origen y número de la última.

Guardar usa el mismo puerto: control de conflicto, reintento idéntico
idempotente y commit antes de informar. La revisión nueva registra
`restored_from`. La consulta muestra esa procedencia. Tras guardar, la edición
siguiente parte de la revisión guardada y pierde la procedencia heredada.

SQLite pasa a v3: `restored_from` con `CHECK` de revisión estrictamente anterior
a la sustituida. Migración v1 → v3 y v2 → v3 dentro de la transacción de
actualización, validando todas las filas, sin reescribir revisiones y
conservando la selección. Una fila inválida revierte y falla cerrado.

No hay selección de revisiones antiguas en Bolo, borrado, interpretación
nutricional, ingesta, sync ni red. Cálculo y tratamiento permanecen bloqueados.

## Verificación

Comando: `scripts/verify.ps1 -DeviceTests` (clean, compilación, lint con avisos
como errores, pruebas comunes y Android/JVM, manifests, `git diff --check` e
instrumentación USB). Pixel 10 Pro Fold (API 37) desbloqueado con
`KEYCODE_WAKEUP` cada 10 s durante la ejecución, sin cambiar ajustes, red ni modo
avión. `MealRestoreDeviceTest` se añade a la lista de clases de `verify.ps1`.

| Suite | Casos | Evidencia |
|---|---:|---|
| Núcleo KMP/JVM | 11 | Contratos de bloqueo existentes. |
| Borradores/selección/historial/restauración KMP/JVM | 17 | 5 nuevos en `MealRestoreTest`. |
| Android/JVM | 40 | Navegación y fronteras existentes. |
| Instrumentación USB | 52 | 44 existentes, 4 SQLite y 4 UI/modelo nuevos. |

Resultado final: `BUILD SUCCESSFUL`, 68 pruebas JVM sin fallos y `OK (52 tests)`
en USB (46,202 s).

Casos comunes: editor restaurado con identidad y copia de la última, texto
exacto, `0` frente a ausencia y procedencia; rechazo de la última revisión y de
revisiones inexistentes sin leer ni escribir almacenamiento; regla de edición de
procedencia; invariantes de `MealRecord`, `MealRestore.Ready` y consulta; guardado
con conflicto y reintento idéntico.

Casos SQLite: procedencia tras reinicio, reintento idéntico, conflicto de un
editor restaurado obsoleto sin añadir filas, `CHECK` que rechaza procedencia no
anterior, fallo de escritura con rollback y reintento correcto, selección
intacta (solo pasa a obsoleta por la regla del ADR 0009). Migración v2 → v3 con
copia de seguridad: tres revisiones y selección conservadas, `restored_from`
nulo y restauración posterior válida. Migración v1 → v3 actualizada. Una base v2
con fila inválida revierte la migración y queda en v2.

Casos UI: plato con confirmación, recreación con la confirmación pendiente,
cancelar, confirmar, recreación del editor restaurado, nada escrito antes de
guardar, guardado con procedencia visible en la consulta y selección de Bolo
obsoleta con cálculo y confirmación bloqueados. Borrador con editor pendiente:
aviso de descarte, cancelar conserva los cambios y confirmar los sustituye.
Conflicto con una revisión posterior escrita por otra conexión: fallo visible
`meal.storage.revision_conflict`, campos conservados y ninguna sobrescritura.
Layouts 840×900, 900×840 y 411×914 dp con texto normal y 1,8× sin recortes ni
elipsis con la confirmación abierta.

Incidencias durante la entrega: la primera ejecución falló en lint por
`Typos` («procede») y `PluralsCandidate`; se reformularon los textos. La segunda
detectó un error de la propia prueba: el editor «obsoleto» coincidía con la
revisión ya guardada y el repositorio lo trató, correctamente, como reintento
idéntico; la prueba usa ahora un contenido distinto.

## Alcance de la evidencia

Bases sintéticas aisladas y repositorios en memoria; no se lee ni modifica la
biblioteca normal del teléfono. No se consulta ni modifica Legacy. La recreación
se prueba con el `ViewModel` retenido; la muerte del proceso usa el mismo
`Bundle` y no se simula. iOS sigue pendiente de verificación.
