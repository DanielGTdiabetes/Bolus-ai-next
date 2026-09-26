# Retirada de la selección local de Bolo — 2026-09-26

Base Next: `4bc0cd407c1149a759e9324fcb0baef355f4c6fa` (PR #23 integrada).
CI de esa base comprobado antes de editar:
[Verify 36230394836](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36230394836),
conclusión `success` para el SHA exacto. Incremento preparatorio de fases 2, 4 y 7
conforme a la ampliación del [ADR 0009](../adr/0009-local-meal-selection.md).

## Comportamiento y límites

Desde Bolo se puede retirar una selección coincidente u obsoleta. Solo se elimina
la referencia de trabajo `meal_selection`, dentro de una transacción; se mantiene
SQLite v2 y se conservan platos, borradores y revisiones. Retirar una selección
ausente vuelve a devolver ausencia y no duplica ningún efecto.

El caso de uso compartido devuelve `MealSelection.Missing` tras el commit o
`MealSelection.Failed` con un motivo estable. La UI espera la respuesta durable:
durante la operación muestra guardado en curso; si falla, no muestra macros ni
afirma que la selección esté ausente. Permite releer y volver a retirar
explícitamente. Una lectura anterior en vuelo no puede reponer la selección.
La operación notifica al terminar para restaurar los controles aunque el usuario
haya navegado a una biblioteca durante la escritura.

La ausencia se conserva al recrear/relanzar la actividad y reabrir la base.
Seleccionar de nuevo una revisión guardada requiere una acción explícita. No se
asignan timestamps de ingesta, no se interpretan macros, no se implementan reglas
clínicas ni se habilitan cálculo, confirmación, sync o tratamiento.

## Verificación

Comandos: `scripts/verify.ps1 -DeviceTests`, `git diff --check` y consulta de
resultados XML JVM. El arnés ejecuta clean, compilación, lint, pruebas comunes y
Android/JVM, manifests y pruebas instrumentadas en el Pixel 10 Pro Fold (API 37)
conectado por USB. No cambia red, modo avión ni configuración del teléfono.

| Suite | Casos | Evidencia |
|---|---:|---|
| Núcleo KMP/JVM | 11 | Contratos de bloqueo existentes. |
| Borradores/selección KMP/JVM | 7 | Retirada delegada, éxito/reintento, todos los fallos explícitos y bloqueo clínico. |
| Android/JVM | 40 | Navegación y fronteras existentes. |
| Instrumentación USB | 36 | 8 autenticación, 7 navegación, 11 UI/ciclo de vida, 8 SQLite y 2 temas. |

Casos nuevos SQLite: retirada ausente/coincidente/obsoleta, reintento, reapertura,
reselección y conservación de las revisiones originales (incluidos ausencia,
texto `0`, espacios y referencia de copia). Un trigger sintético `AFTER DELETE`
aborta la escritura: el resultado falla y la selección anterior sigue presente
tras reabrir; al retirar el trigger, el reintento confirma ausencia. Se mantienen
las pruebas de migración v1 → v2 y recuperación de la copia sintética previa.

Casos nuevos UI: retirada coincidente/obsoleta, ausencia visible sin macros,
recreación y relanzamiento, reselección posterior y error de persistencia con
relectura/reintento. Una prueba del modelo con barreras deterministas mantiene
pendiente una lectura, solicita retirada y comprueba que la lectura no repone
el snapshot; tampoco se admiten escrituras duplicadas mientras no termine.
En todos los estados se mantienen cálculo y tratamiento bloqueados.

La primera ejecución pasó 58 pruebas JVM y `OK (36 tests)` en USB (34,876 s).
La revisión posterior ajustó la notificación al finalizar una retirada para
restaurar los controles de cualquier pantalla abierta durante la operación. La
repetición íntegra final terminó con código 0, `BUILD SUCCESSFUL`, 58 pruebas JVM
sin fallos ni errores y `OK (36 tests)` en USB (34,964 s). `git diff --check`
correcto. Los dos paquetes de pruebas se desinstalaron correctamente.

## Alcance de la evidencia

Las bases son sintéticas aisladas; no se lee ni modifica la biblioteca normal del
teléfono. Los APKs de test/emisor se retiran al terminar y Next se actualiza por
instalación conservando sus datos. No se consulta ni modifica Legacy ni se envían
escrituras a servicios externos. Los checks preservan la ausencia del permiso de
Internet y de receivers productivos: este flujo solo usa SQLite local.

Las pruebas de reinicio son cierre/reapertura del repositorio y relanzamiento de
Activity con nuevo modelo; no simulan pérdida física de energía. Las pruebas de
layout conservan los contextos claro/oscuro, anchos y texto ampliado existentes.
iOS sigue pendiente de verificación; no se promociona ningún binding ni capacidad
clínica. El siguiente incremento propuesto es consultar revisiones guardadas en
modo de solo lectura desde la biblioteca.
