# ADR 0013: editor de franjas horarias del perfil clínico

- Estado: aceptado. El propietario fijó las decisiones T1 a T5 y aprobó el
  diseño el 2026-09-28 (sección 12). No habilita uso clínico.
- Fecha: 2026-09-28.
- Fase y criterio de aceptación: segundo paso del hito 6. Editar en la UI
  perfiles con varias franjas horarias por parámetro (crear, dividir, unir,
  mover límites y editar valores) con cobertura completa 00:00–24:00, sin huecos
  ni solapes, y sin que ningún valor llegue al cálculo.
- Responsables de decisión: propietario del proyecto.
- Evidencia clínica requerida: no aplica. No se introduce ninguna regla, límite,
  valor por defecto ni conversión. Dividir copia valores ya introducidos por el
  usuario, no crea valores clínicos.
- Base Next: `5f7c50a` (PR #28 integrada). CI del SHA exacto:
  [Verify 36447049411](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36447049411),
  conclusión `success`.
- Relacionados: ADR 0012 (modelo, SQLite, huella, unidad, restauración,
  concurrencia). Este ADR complementa el ADR 0012 sin editarlo, cierra su
  pendiente P5 y no cambia el esquema. La sección 14 lista lo que sustituye.

## 1. Contexto

El ADR 0012 ya soporta varias franjas en modelo, validación, huella y SQLite. La
UI solo edita una franja de día completo por parámetro y bloquea las versiones
con varias franjas con `profile.edit.segments_ui_unavailable`.

Este incremento solo añade edición de franjas. No cambia tablas, huella,
política de escritura ni lectura. El motor y Bolo siguen sin leer el perfil.

Riesgos que el diseño debe cerrar:

- un hueco o solape producido por una edición a medias.
- una normalización silenciosa que borre estructura elegida por el usuario.
- un valor heredado que parezca introducido, o `Sin configurar` convertido en `0`.
- una acción repetida (doble toque, recreación) que divida o una dos veces.
- ambigüedad horaria: 12 h frente a 24 h, medianoche, `24:00`.
- perder franjas o texto pendiente al rotar o recrear la actividad.

## 2. Decisión general

1. Las operaciones de franjas son funciones puras en `shared/clinical-profile`
   sobre `ProfileContent`. Devuelven `ProfileEdit.Changed` o
   `ProfileEdit.Rejected` con código estable. La UI no calcula franjas.
2. La cobertura 00:00–24:00 es una invariante que ninguna operación puede
   romper, ni siquiera de forma transitoria. No existe un estado «editor con
   hueco» que haya que arreglar antes de guardar.
3. Solo hay tres operaciones estructurales, **dividir**, **unir con la
   siguiente** y **mover límite**, y una de valor, **editar valor**. «Crear
   franja» es dividir. «Borrar franja» es unir.
4. Nada se normaliza al guardar. El contenido guardado es exactamente el del
   editor.
5. **Regla general**: la estructura horaria expresa intención del usuario y solo
   cambia mediante una acción explícita suya (dividir, unir o mover límite).
   Ninguna otra operación, incluido el cambio de unidad, añade, quita, fusiona ni
   desplaza fronteras.
6. `withAllDayValue` y `profile.edit.segments_ui_unavailable` dejan de usarse. El
   código se conserva reservado en el enum y no se reutiliza.

## 3. Interacción

Cada parámetro muestra sus franjas como lista ordenada. Cada fila:
`HH:MM–HH:MM`, campo de valor con la unidad derivada y acciones de la franja.

### 3.1 Crear una franja (dividir)

- El usuario selecciona una franja y pulsa «Dividir…».
- Introduce una hora **interior**: `inicio < hora < fin`. El diálogo muestra el
  rango válido y la vista previa `06:00–09:00` + `09:00–12:00` antes de aplicar.
- Se crean dos franjas contiguas. Ambas heredan el valor de la original,
  incluido `Sin configurar` o `0` tal cual.
- Es solo edición. El valor heredado es el que el usuario ya había introducido
  en esa franja. No se marca como valor nuevo ni se propone otro.
- Hora igual al inicio o al fin, o fuera de la franja:
  `profile.edit.split_out_of_range`, sin cambios.
- Si el parámetro tiene 48 franjas o más, «Dividir» se deshabilita y la
  operación devuelve `profile.edit.segment_limit_reached` (T2).

### 3.2 Unir franjas

- Acción explícita «Unir con la siguiente». Nunca automática.
- Solo se permite cuando ambas franjas tienen **el mismo valor**: mismo decimal
  canónico, o ambas `Sin configurar`. `Sin configurar` y `0` no son iguales.
- Con valores distintos la acción no se muestra y la operación devuelve
  `profile.edit.merge_values_differ`. Para unirlas el usuario iguala antes uno de
  los valores de forma explícita. Así una unión nunca descarta un valor.
- La franja resultante va del inicio de la primera al fin de la segunda.
- No se une a través de medianoche: la última (`…–24:00`) y la primera
  (`00:00–…`) no son adyacentes.

### 3.3 Editar hora de inicio o fin (mover límite)

- Un límite interior es compartido: el fin de una franja es el inicio de la
  siguiente. Editar uno mueve los dos. No existe edición independiente que pueda
  abrir un hueco o un solape.
- El inicio de la primera franja (`00:00`) y el fin de la última (`24:00`) son
  fijos y no editables.
- La nueva hora debe quedar estrictamente entre el inicio de la franja anterior
  al límite y el fin de la posterior. Ninguna franja puede quedar con duración 0
  ni saltar por encima de otra. Fuera de rango:
  `profile.edit.boundary_out_of_range`.
- Para eliminar una franja se usa «Unir», no se arrastra un límite hasta ella.
- Los valores no cambian. La vista previa muestra las dos franjas afectadas con
  sus horas nuevas antes de aplicar.

### 3.4 Editar el valor de una franja

- Igual que el ADR 0012 (§4.1 y §4.2): texto vacío es `Sin configurar`, `,` o
  `.` como separador, rechazo de agrupaciones ambiguas, valor canónico visible
  antes de guardar.
- Cada franja se edita de forma independiente. Nunca se copia un valor a otra
  franja salvo al dividir.

### 3.5 Identidad de franja y acciones repetidas

- Cada operación identifica la franja por su intervalo observado
  `[inicio, fin)`, no por su posición en la lista.
- Si ese intervalo ya no existe en el contenido actual (doble toque, evento
  tardío, recreación), la operación devuelve `profile.edit.stale_segment` y no
  cambia nada. Un segundo «Dividir a las 06:00» sobre `00:00–24:00` ya dividida
  no crea otra franja.
- Mientras un campo de valor de un parámetro tenga texto no válido o no aplicado,
  las operaciones estructurales de ese parámetro quedan deshabilitadas. Evita que
  un texto ambiguo se herede al dividir.

### 3.6 Formato y entrada de horas

- Siempre 24 h, `HH:MM` con dos dígitos, independiente del formato 12 h del
  sistema. Sin AM/PM.
- Entrada aceptada: `H:MM` o `HH:MM`, horas `0..23`, minutos `00..59`. Se
  rechazan `600`, `6`, `6.00`, `6h`, `06:00 h` y segundos:
  `profile.edit.invalid_time`. La hora canónica se muestra antes de aplicar.
- Resolución de 1 minuto (T1). Sin redondeo en ninguna operación ni en la
  lectura.

## 4. Reglas de estructura

| Regla | Decisión |
|---|---|
| Cobertura | obligatoria 00:00–24:00 en cada parámetro, siempre, también en el editor |
| Huecos y solapes | imposibles por construcción de las operaciones. Validación del ADR 0012 se mantiene en guardado y lectura |
| Medianoche | ninguna franja cruza medianoche. 22:00–06:00 son dos franjas, `22:00–24:00` y `00:00–06:00` |
| `24:00` | solo existe como fin de la última franja (`end_minute = 1440`). Se muestra como `24:00`, nunca como `00:00`. No es introducible como límite ni como hora de división |
| `00:00` | solo como inicio de la primera franja. No es introducible como límite |
| Límites válidos | `00:01`–`23:59` (minutos 1–1439) |
| Orden canónico | parámetros en orden de catálogo, franjas por `start_minute` ascendente. Las operaciones lo conservan y la lectura lo exige |
| Franjas adyacentes iguales | se conservan tal cual al editar, guardar, leer, restaurar y recrear. La huella las distingue de una franja única. La UI ofrece «Unir con la siguiente» sin aplicarla |
| Guardar solo estructura | dividir o unir sin cambiar valores es un contenido distinto y se guarda como versión nueva. `profile.edit.unchanged` solo si la huella coincide |
| Valores | `Sin configurar` permitido en cualquier franja. La cobertura estructural es obligatoria, la de valores no, porque el perfil no autoriza nada |
| Máximo de franjas | 48 por parámetro (T2). Límite técnico de usabilidad, no clínico, que se aplica **solo a la operación «Dividir»**: si el parámetro tiene 48 franjas o más, «Dividir» está deshabilitado y devuelve `profile.edit.segment_limit_reached`. Leer, restaurar, editar valores, mover límites y hacer uniones válidas (T4) sigue permitido con cualquier número de franjas. No se exige reducir a 48 una versión existente, que con valores distintos puede no admitir ninguna unión. Modelo, huella, almacenamiento, política de escritura y lectura no imponen el límite |
| Resolución | 1 minuto, sin redondeo (T1) |

## 5. `Sin configurar` frente a `0` dentro de una franja

- Estados distintos por franja en dominio, SQLite, huella, UI y estado guardado.
- Dividir hereda el estado exacto: `Sin configurar` da dos `Sin configurar`,
  `0` da dos `0`.
- Unir exige igualdad exacta: `Sin configurar` + `0` no se unen.
- Borrar el texto de una franja la deja `Sin configurar`, nunca `0`, y no toma
  el valor de la vecina.
- Una franja `Sin configurar` junto a franjas con valor se muestra como
  «Sin configurar», no vacía ni con guion ambiguo.

## 6. Cambio de unidad de glucosa con varias franjas

Se mantiene todo el §4.4 del ADR 0012: nada se convierte, la versión que cambia
la unidad no lleva valores dependientes y los valores de la unidad nueva van en
la versión siguiente. Este ADR precisa qué significa «vaciar» cuando hay varias
franjas (T3, decisión del propietario):

- Al cambiar la unidad, `insulin_sensitivity` y `glucose_target` **conservan
  exactamente sus fronteras** (las del contenido actual del editor, incluidas
  divisiones aún no guardadas) y **todas** sus franjas pasan a `Sin configurar`.
- El horario **no** se reduce a una franja 00:00–24:00. Las franjas que quedan
  adyacentes con el mismo valor `Sin configurar` **no** se fusionan. Aplica la
  regla general del §2.5.
- Ejemplo obligatorio (también como prueba):

  | Antes (mg/dL) | Después de cambiar a mmol/L |
  |---|---|
  | 00:00–06:00 → 40 mg/dL/U | 00:00–06:00 → Sin configurar |
  | 06:00–12:00 → 45 mg/dL/U | 06:00–12:00 → Sin configurar |
  | 12:00–24:00 → 50 mg/dL/U | 12:00–24:00 → Sin configurar |

- Mientras el cambio de unidad no se guarde, esos dos parámetros quedan
  bloqueados en valores **y** estructura. La versión que cambia la unidad guarda
  las fronteras conservadas con todos sus valores `Sin configurar`. Unir o
  dividir esas franjas es posible en la versión siguiente, como acción explícita.
- Los textos pendientes de ISF y objetivo (escritos con la unidad anterior y aún
  no aplicados) se descartan al cambiar la unidad. Nunca se aplican con la
  unidad nueva.
- Volver a la unidad inicial en el mismo editor no recupera valores. Las
  fronteras siguen siendo las mismas.
- `carb_ratio` no se ve afectado: fronteras y valores intactos.
- Un parámetro dependiente que ya estaba entero `Sin configurar` queda igual.
- La confirmación del cambio de unidad dice cuántas franjas de cada parámetro
  pasan a `Sin configurar` y que sus horas se conservan.

## 7. Restauración con varias franjas

- Sin cambios de principio respecto al ADR 0012 §8. La copia es exacta: límites,
  número de franjas, adyacentes iguales, `Sin configurar`, `0`, unidad y zona.
- Cualquier operación de franja o valor pasa el origen a `manual` y conserva
  `restored_from`. Si el contenido vuelve a ser idéntico al de origen (por
  ejemplo dividir y después unir), recupera `restored`. Lo decide la igualdad de
  contenido, no el historial de operaciones.
- Las versiones de una sola franja del ADR 0012 se restauran y editan igual.
- Restaurar una versión con otra unidad sigue la advertencia del ADR 0012.
- El historial muestra todas las franjas de cada versión en solo lectura.

## 8. Indicador `Modificada` (T5)

- Cada franja del editor muestra `Modificada` cuando su intervalo `[inicio, fin)`
  no existe en el mismo parámetro del contenido de partida del editor, o existe
  con otro valor (`Sin configurar` y `0` cuentan como distintos).
- El contenido de partida es la última versión, la versión restaurada o el
  perfil vacío, igual que `ProfileEditor.start`.
- Es estado **derivado** de UI/editor: se calcula comparando `start` y
  `content`. No se guarda en SQLite, no forma parte de `ProfileContent`, de la
  huella ni de la procedencia, y no influye en el origen `restored`/`manual`.
- No se guarda en el estado de la actividad. Al recrear se recalcula desde
  `start` y `content`, que sí se guardan.
- Si el usuario deshace un cambio y la franja vuelve a coincidir con la de
  partida, el indicador desaparece.
- Tras un cambio de unidad, las franjas dependientes que tenían valor aparecen
  como `Modificada`.
- Tras guardar, el editor se cierra o parte de la versión nueva, y ninguna
  franja aparece como `Modificada`.

## 9. Concurrencia e idempotencia

- Sin cambios en almacenamiento: `baseVersion`, transacción exclusiva, conflicto
  `profile.storage.revision_conflict`, reintento idéntico en `baseVersion + 1`.
- Las operaciones de franja son locales al editor y puras. Nada se escribe hasta
  «Guardar».
- Idempotencia de edición por identidad de intervalo (§3.5). Un evento repetido
  o tardío no duplica una división ni une dos veces.
- «Guardar» se deshabilita mientras hay un guardado en vuelo y mientras algún
  campo tenga texto no válido o no aplicado.
- Tras un conflicto el editor conserva todas las franjas y textos. No hay fusión
  de estructuras entre versiones ni «última escritura gana».

## 10. Rotación y recreación de la actividad

- El estado guardado incluye el contenido completo con la codificación canónica
  existente (`ProfileCodec`), por lo que conserva límites, orden, adyacentes
  iguales, `Sin configurar` y `0` byte a byte.
- Los textos pendientes se guardan por clave `parámetro@inicio-fin` y se
  restauran como texto, sin interpretarlos. Un texto cuya clave no corresponde a
  una franja actual se descarta con error visible.
- El indicador `Modificada` no se guarda. Se recalcula (§8).
- Un diálogo abierto (dividir, mover límite, unir) guarda su tipo, el intervalo
  objetivo y el texto escrito. Al recrear se reabre igual. Si el intervalo ya no
  existe, el diálogo se cierra con `profile.edit.stale_segment` visible.
- Al recrear todo se revalida. Un estado que no decodifica o no cumple las
  invariantes descarta el editor con un error visible. Nunca se repara ni se
  normaliza en silencio.
- Prueba obligatoria: comparación de la huella del contenido del editor antes y
  después de recrear, con franjas adyacentes iguales, `0` y `Sin configurar`.

## 11. Fuera de alcance

Uso del perfil por el motor, significado de perfil confirmado, límites clínicos,
DIA, IOB, Dexcom, aprendizaje, propuestas automáticas, conversión de unidades,
exportación/importación, sincronización, resolución de franjas en cambios de
hora (P4 del ADR 0012), plantillas de franjas y copiar franjas entre parámetros.

## 12. Decisiones del propietario (2026-09-28)

| Id | Pregunta | Decisión |
|---|---|---|
| T1 | Resolución de horas | 1 minuto, sin redondeo |
| T2 | Máximo de franjas por parámetro | 48, aplicado solo a «Dividir» en el editor. No obliga a reducir versiones existentes |
| T3 | Estructura al cambiar de unidad | conservar exactamente las fronteras de ISF y objetivo y pasar todos sus valores a `Sin configurar`. No reducir a una franja ni fusionar (§6) |
| T4 | Unir con valores distintos | no permitido (§3.2) |
| T5 | Franjas cambiadas durante la edición | indicador visual `Modificada`, solo de UI/editor, fuera del contenido persistente y de la huella (§8) |

## 13. Alternativas consideradas

- **Fusionar adyacentes iguales al guardar**: destruye intención estructural.
  Rechazada por decisión del propietario.
- **Editar inicio y fin de cada franja por separado y validar al guardar**:
  permite estados con hueco o solape y errores tardíos. Rechazada.
- **Unir con valores distintos eligiendo cuál se conserva**: una acción
  estructural descartaría un valor clínico. Rechazada (T4).
- **Franja que cruza medianoche**: contradice el modelo y la huella del ADR
  0012. Rechazada.
- **Identificar franjas por posición**: un toque repetido o tardío actúa sobre
  otra franja. Rechazada frente a identidad por intervalo.
- **Reducir ISF y objetivo a una franja 00:00–24:00 al cambiar de unidad**
  (comportamiento actual de `withGlucoseUnit`): más simple, pero destruye
  estructura sin acción explícita. Rechazada (T3).
- **Persistir el indicador `Modificada`**: mezcla estado de edición con
  contenido clínico y alteraría la huella. Rechazada (T5).
- **Editor de línea temporal con arrastre**: más rápido, pero impreciso y difícil
  con texto 1,8× y accesibilidad. Posible mejora posterior sobre las mismas
  operaciones.

## 14. Consecuencias y relación con decisiones anteriores

- Sin cambios de esquema SQLite, huella, política de escritura ni lectura. Sin
  migración. Rollback: revertir el commit. Las versiones guardadas con varias
  franjas se siguen leyendo con la versión anterior (en solo lectura).
- Nuevos códigos estables: `profile.edit.split_out_of_range`,
  `profile.edit.boundary_out_of_range`, `profile.edit.merge_values_differ`,
  `profile.edit.stale_segment`, `profile.edit.invalid_time`,
  `profile.edit.segment_limit_reached`.
- iOS reutilizará las mismas operaciones puras.

Lo que este ADR sustituye o precisa del ADR 0012:

| ADR 0012 | Cambio |
|---|---|
| §12 P5: UI de una franja por parámetro, varias franjas en solo lectura con `profile.edit.segments_ui_unavailable` | sustituido. Las versiones con varias franjas se editan. El código queda reservado sin uso |
| §15 UI: «versión con varias franjas: se muestra completa y no se puede editar» | sustituida por las pruebas de edición de la sección 15 |
| §4.4: «al cambiar la unidad en el editor se vacían ISF y objetivo» | precisado, no cambiado: vaciar significa pasar cada franja a `Sin configurar` conservando fronteras. El resto del §4.4 queda igual |
| Implementación de `withGlucoseUnit` (no fijada por el ADR 0012) | cambia: deja de reducir a `ParameterSchedule.allDay` y conserva fronteras. La prueba `ProfileUnitTest` que espera una franja única se actualiza en el mismo commit |

No cambia ninguna decisión sobre huella, procedencia, origen `restored`,
concurrencia, idempotencia de guardado, SQLite, backup ni bloqueo de cálculo.

## 15. Pruebas previstas

### Comunes

- dividir: hora interior, en el inicio, en el fin, fuera, herencia exacta de
  `Sin configurar` y `0`, orden conservado.
- unir: iguales, `Sin configurar` + `Sin configurar`, `Sin configurar` + `0`
  rechazado, distintos rechazado, última con primera no adyacentes.
- mover límite: dentro de rango, igual a un vecino, más allá de un vecino,
  primer inicio y último fin no editables, valores sin cambios.
- cobertura y ausencia de huecos/solapes tras secuencias aleatorias de
  operaciones (prueba de propiedad con semilla fija).
- adyacentes iguales se conservan y cambian la huella frente a una franja única.
- identidad por intervalo: operación repetida o sobre intervalo inexistente
  devuelve `stale_segment` sin cambios.
- horas: `H:MM`, `HH:MM`, rechazo de `24:00`, `00:00` como límite, `600`, `6`,
  `6.00`, AM/PM, segundos.
- unidad con varias franjas (T3): el ejemplo del §6 exacto (tres franjas
  conservadas, todas `Sin configurar`, sin fusión), fronteras no guardadas
  conservadas, bloqueo de valores y estructura, volver a la unidad inicial no
  recupera valores, `carb_ratio` intacto, la versión que cambia la unidad se
  guarda con las fronteras y la política la acepta.
- límite de 48 (T2): con 47 franjas dividir funciona, con 48 o más se rechaza
  con `segment_limit_reached`. Una versión sintética con más de 48 franjas y
  valores todos distintos se lee, se restaura, admite editar valores y mover
  límites, no ofrece ninguna unión y se guarda sin reducirse. Política y lectura
  no aplican el límite.
- indicador `Modificada` (T5): aparece al dividir, mover, unir y editar valor,
  desaparece al deshacer, no altera huella ni origen.
- restauración: dividir y unir vuelve a `restored`, cualquier otro cambio da
  `manual` con `restored_from`.
- guardar solo estructura crea versión nueva, sin cambios da `unchanged`.
- todos los estados siguen con `allowsCalculation = false`.

### SQLite en dispositivo

- versión con varias franjas, adyacentes iguales, `0` y `Sin configurar`
  guardada y releída byte a byte, huella incluida.
- reintento idéntico y conflicto con varias franjas sin filas nuevas.

### UI en dispositivo

- dividir, mover límite, unir y editar valor de extremo a extremo.
- «Unir con la siguiente» solo visible con valores iguales.
- `24:00` visible como fin y nunca introducible.
- doble toque en dividir y unir no duplica.
- rotar y recrear con diálogo abierto, texto pendiente y varias franjas.
- conflicto con varias franjas: error visible y franjas conservadas.
- cambio de unidad con el ejemplo del §6: tres franjas visibles como
  `Sin configurar`, bloqueadas, guardadas y releídas con sus fronteras.
- indicador `Modificada` visible, recalculado tras recrear y ausente tras
  guardar.
- con 48 franjas «Dividir» deshabilitado.
- restaurar versión con varias franjas y editarla.
- Bolo sigue bloqueado y no lee el perfil.
- layouts 840×900, 900×840 y 411×914 dp con texto 1,0× y 1,8×, tema claro y
  oscuro, con al menos 8 franjas por parámetro.

### Verificación

- nuevas clases en la lista `-e class` de `scripts/verify.ps1`.
- `scripts/verify.ps1 -DeviceTests` en el Pixel.
- evidencia en `docs/validation/profile-segment-editor-<fecha>.md`.

## 16. Notas de implementación

Precisiones de la entrega, sin cambiar decisiones:

- Las operaciones viven en `shared/clinical-profile` (`ProfileSegments.kt`):
  `SegmentOperation` (`Split`, `MergeWithNext`, `MoveBoundary`, `SetValue`),
  `ProfileContent.edited`, `ProfileEditor.edited`, `ProfileEditor.isModified` y
  `ProfileTimes`. `withAllDayValue` desaparece.
- Código adicional `profile.edit.schedule_locked` para una operación estructural
  sobre ISF u objetivo mientras hay un cambio de unidad sin guardar. Un valor
  introducido en ese estado sigue devolviendo `profile.edit.unit_change_with_values`.
- Sin unidad declarada, ISF y objetivo no aceptan valores pero sí dividir, mover
  y unir franjas `Sin configurar`. La estructura no depende de la unidad.
- Los paneles de dividir, mover límite y unir son en línea, bajo la franja, no
  diálogos del sistema. Su estado vive en el modelo y se guarda con la actividad.
  Unir también pide confirmación con vista previa.
- Con un panel abierto «Guardar» queda deshabilitado, porque su texto aún no se
  ha aplicado.
- La vista previa y «Aplicar» de un panel revalidan el parámetro en el momento:
  si un valor de ese parámetro pasó a no válido después de abrir el panel, la
  operación se rechaza con el código de ese valor (revisión de la PR #30).
- Un estado guardado que no decodifica se descarta con
  `profile.storage.invalid_record` visible. Un texto pendiente cuya clave no
  corresponde a una franja actual, o un panel sobre un intervalo que ya no
  existe, se descarta con `profile.edit.stale_segment` visible. Una clave ausente
  muestra el valor guardado, nunca «Sin configurar».
- Evidencia: [validación 2026-09-28](../validation/profile-segment-editor-2026-09-28.md).

## 17. Condiciones para revisar este ADR

Resolución de franjas en cambios de hora, uso del perfil por el motor, límites
clínicos, plantillas o copia entre parámetros, un segundo esquema de contenido,
o un editor gráfico de línea temporal.
