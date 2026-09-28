# Editor de franjas del perfil clínico — 2026-09-28

Base Next: `3500695` (PR #29, ADR 0013, integrada). CI del SHA exacto comprobado
antes de integrar: Verify en verde sobre la cabeza de la PR `f789ff4`
([36448222003](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36448222003)).
Implementa el [ADR 0013](../adr/0013-clinical-profile-segment-editor.md) con las
decisiones T1 a T5 del propietario. Legacy no se consulta ni se modifica.

## Comportamiento

El editor del perfil muestra, por parámetro, sus franjas en orden `HH:MM–HH:MM`
con un campo de valor propio y su unidad. Acciones explícitas por franja:

- **Dividir…**: pide una hora interior (24 h, `H:MM` o `HH:MM`, 1 minuto) y
  muestra el resultado antes de aplicar. Las dos franjas heredan el valor exacto.
- **Cambiar hora de inicio… / de fin…**: mueve el límite compartido con la
  vecina. `00:00` inicial y `24:00` final no se pueden mover.
- **Unir con la siguiente…**: visible solo si las dos franjas tienen exactamente
  el mismo valor (`Sin configurar` y `0` son distintos). Pide confirmación.
- **Valor**: vacío es `Sin configurar`, nunca `0`.

Nada se aplica hasta pulsar «Aplicar» en el panel y nada se guarda hasta
«Guardar como versión N». Con un panel abierto no se puede guardar. Las franjas
adyacentes iguales se conservan. `Modificada` marca las franjas cuyo intervalo o
valor difiere del contenido de partida. Es estado del editor, no se guarda ni
entra en la huella.

Cambiar de unidad pasa todas las franjas de sensibilidad y objetivo a
`Sin configurar` conservando sus horas, bloquea valores y estructura de esos dos
parámetros hasta guardar y avisa de cuántas franjas se vacían. Nada se convierte
ni se une.

Con 48 franjas o más, «Dividir» desaparece y se explica el límite. Leer,
restaurar, editar valores, mover límites y unir franjas iguales siguen
disponibles. Nada exige reducir una versión existente.

Bolo sigue sin leer el perfil y con cálculo y confirmación bloqueados. Sin
esquema nuevo, migración, red, límites clínicos, DIA, IOB, conversión ni sync.

## Garantías y evidencia

| Área | Garantía | Evidencia |
|---|---|---|
| Cobertura | 00:00–24:00 sin huecos, solapes ni desorden tras cualquier secuencia de operaciones | `randomOperationSequencesNeverBreakCoverage` (4 000 operaciones con semilla fija) |
| Dividir | hora interior, herencia exacta de valor, `0` y `Sin configurar`, bordes y fuera de rango rechazados | `splitAtAnInteriorMinute…`, `splitOnAnEdgeOrOutside…`, UI `splitMoveMerge…` |
| Unir (T4) | solo valores idénticos, `Sin configurar` + `0` rechazado, última y primera no adyacentes | `mergeOnlyJoinsExactlyEqualValues`, `lastAndFirstSegments…` |
| Mover límite | mueve los dos lados, no colapsa, no salta, extremos fijos | `movingABoundary…`, `boundaryCannotCollapse…` |
| Acciones repetidas | identidad por intervalo: repetir o actuar sobre un intervalo inexistente da `stale_segment` sin cambios. Doble toque en «Aplicar» no duplica | pruebas comunes, UI `splitMoveMerge…` |
| Horas (T1) | 24 h, 1 minuto, sin redondeo. `24:00`, `600`, `6`, `6.00`, AM/PM y segundos rechazados. `00:00` nunca es límite | `timesAre24HourWithOneMinuteResolution`, `midnightIsNeverAValidBoundary`, UI |
| Adyacentes iguales | se conservan, cambian la huella y un cambio solo estructural es versión nueva | `adjacentEqualSegments…` (común, UI y SQLite) |
| Unidad (T3) | ejemplo 40/45/50 mg/dL/U → tres franjas `Sin configurar` en mmol/L, sin fusión, fronteras no guardadas incluidas, estructura bloqueada (`schedule_locked`), `carb_ratio` intacto | `unitChangeKeepsEveryBoundary…`, `unitChangeLocksDependentStructure…`, UI `unitChangeKeepsEveryBoundary…`, SQLite `editedSegmentStructures…` |
| Límite 48 (T2) | solo afecta a dividir. 60 franjas distintas se leen, editan, mueven y guardan sin reducir. Modelo, política y SQLite no lo aplican | `splitLimitAppliesOnlyToSplitting`, `moreThan48…`, UI `splitLimitOnly…`, SQLite |
| Modificada (T5) | derivada de `start` y `content`, desaparece al deshacer, fuera de huella y origen | `modifiedIndicatorIsDerivedAndNeverPartOfContent`, UI |
| Restauración | copia exacta con varias franjas. Dividir y unir vuelve a `restored` con la misma huella | `splitThenMergeReturnsToAnExactRestoration`, UI `restoredMultiSegment…` |
| Concurrencia | conflicto conserva todas las franjas. Reintento idéntico y conflicto con varias franjas no añaden filas | UI `conflictKeepsEverySegmentInTheEditor`, SQLite `editedSegmentStructures…` |
| Recreación | contenido, textos pendientes y panel abierto sobreviven con la misma huella. Panel sobre intervalo inexistente se cierra con `stale_segment`. Estado que no decodifica se descarta con `invalid_record` visible | UI `recreationKeepsSegments…`, `savedStateIsRevalidated…` |
| Texto no válido con panel abierto | revisión de la PR #30: si un valor del parámetro pasa a no válido con un panel abierto, «Aplicar» se deshabilita, un clic forzado no aplica nada y muestra el código, y ningún valor anterior ya interpretado se usa en su lugar | UI `invalidValueTypedWhileAPanelIsOpenBlocksApplying` |
| Layout | 8 franjas por parámetro con panel abierto, aviso de unidad y unión, 840×900, 900×840 y 411×914 dp, texto 1,0× y 1,8×, claro y oscuro, sin recortes | `segmentEditorFitsExpandedLayoutsWithEightSegmentsAtLargeFont` |
| Sin uso clínico | todas las versiones y el historial siguen con `allowsCalculation = false`. Bolo no lee el perfil | `everyEditingResultStillBlocksCalculation`, `bolusStaysBlockedAndDoesNotUseTheSavedProfile`, `verify.ps1` |

## Verificación

Comandos ejecutados en Windows desde la rama de trabajo:

- `shared/clinical-profile`: 61 pruebas comunes, 19 nuevas en `ProfileSegmentsTest`.
- `scripts/verify.ps1`: clean, compilación, lint con avisos como errores,
  pruebas comunes y Android/JVM, manifests, exclusiones de backup,
  independencia del motor, workflow y `git diff --check`. Resultado: correcto.
- `scripts/verify.ps1 -DeviceTests` en Pixel 10 Pro Fold, único dispositivo
  autorizado, con `KEYCODE_WAKEUP` cada 10 s, sin cambiar ajustes, red ni modo
  avión, API 37. Resultado: `OK (84 tests)` en 72,5 s (74 antes de esta entrega,
  menos 1 sustituida y 11 nuevas: 10 de UI y 1 de SQLite). Primera entrega:
  `OK (83 tests)`. La prueba adicional cubre la corrección de la revisión.

Ninguna prueba usa datos personales. Todas las bases son sintéticas y se borran.
