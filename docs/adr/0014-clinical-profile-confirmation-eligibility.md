# ADR 0014: perfil clínico confirmado y puertas de elegibilidad

- Estado: **aceptado** para confirmación de datos. No habilita uso clínico.
- Aprobación: el propietario aceptó el ADR el 2026-10-03, tras incorporar las
  cuatro correcciones de su revisión (sección 16), con las decisiones C1 a C9
  fijadas según la recomendación de cada una (sección 11). C7 queda aprobada como
  solución provisional mientras `policy_not_approved` bloquee todo. La
  aceptación no es aprobación clínica ni autoriza a empezar la implementación
  sin una petición explícita.
- Fecha: 2026-10-03.
- Fase y criterio de aceptación: fase 6, trabajo 1 y 2 («confirmación de perfil»
  y «validar completitud, solapamientos, huecos, zona horaria, vigencia y límites
  aprobados»). Este ADR define qué significa confirmar los datos de una versión
  guardada, cómo se registra y revoca, qué puertas existen entre esa confirmación
  y cualquier uso clínico, y cómo se comunicarían sus causas mediante
  `input.profile.*`. No habilita cálculo ni tratamiento en ningún estado.
- Responsables de decisión: propietario del proyecto.
- Evidencia clínica requerida: no aplica. No se introduce ningún límite, regla,
  vigencia, valor por defecto ni conversión. La aprobación clínica del perfil
  queda expresamente fuera y pendiente de un ADR posterior.
- Base Next: `6f9806c0d6b5430e604b57fe4da46711c47d9ab0` (PR #30 integrada,
  ADR 0013 implementado). CI del SHA exacto:
  [Verify 36452587608](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36452587608),
  evento `push`, conclusión `success`.
- Legacy: no se consulta. No hace falta evidencia nueva de Legacy para decidir
  qué declara el usuario sobre sus propios datos. La última auditoría documentada
  sigue fijada en `f5417721d8019a9831126f4d843edfc4de87653d` y no se usa aquí.
- Relacionados: ADR 0012 (modelo, huella, SQLite v1, unidad, restauración,
  concurrencia), ADR 0013 (franjas, límite de 48 solo para dividir),
  [contrato `unavailable-input-v1`](../contracts/unavailable-input-v1.md) y
  [contrato `unavailable-input-report-v1`](../contracts/unavailable-input-report-v1.md).
  Este ADR complementa al ADR 0012 sin editarlo. La sección 1.3 lista qué precisa.
- Revisión del propietario (2026-10-03, PR #31 en borrador): cuatro correcciones
  incorporadas antes de decidir (sección 16). Las recomendaciones generales C1 a
  C9 se mantienen y siguen pendientes.

## 0. Cómo leer este ADR

Cada afirmación pertenece a una de tres categorías:

| Marca | Significado |
|---|---|
| **Aprobado** | decisión ya aceptada en ADR 0012 o 0013, o invariante de `AGENTS.md`. Se cita, no se reabre |
| **Recomendación** | propuesta nueva de este ADR. No vinculante hasta que el propietario la apruebe |
| **Pendiente** | pregunta abierta, recogida en C1 a C9 o en decisiones anteriores (P4 del ADR 0012) |

Invariantes aprobadas que este ADR no modifica en ninguna recomendación:

- Guardar una versión no confirma sus datos (ADR 0012 §2.4).
- `allowsCalculation = false` y `allowsTreatment = false` en todo resultado del
  perfil, y el bloqueo estable `profile.not_approved_for_calculation` (ADR 0012
  §2.4).
- El motor (`shared/bolus-engine`), Bolo y `ReadOverview` no dependen del perfil,
  y `scripts/verify.ps1` lo comprueba.
- `input.profile.*` comunica causas ya determinadas. No acredita elegibilidad ni
  autoriza nada (`unavailable-input-v1`, `unavailable-input-report-v1`).
- Orden de prioridades: SEGURIDAD > CONSISTENCIA > DISPONIBILIDAD > COMODIDAD.

Estado de las marcas tras la aceptación (2026-10-03): las **recomendaciones**
de las secciones 1 a 10 quedan aprobadas como decisiones de diseño. Siguen
**pendientes** las marcadas como tales que dependen de decisiones futuras: la
elección entre las opciones B y C de la sección 6.3 antes de cualquier
aprobación clínica, y P4 y P6 del ADR 0012.

**Decisión central:** confirmar los datos de un perfil no aprueba límites,
reglas, vigencia ni aptitud para calcular o administrar insulina. Tras este
incremento existiría el estado «Datos confirmados», y en todos los casos iría
acompañado de «Cálculo todavía bloqueado».

## 1. Alcance y significado de la confirmación

### 1.1 Qué declara el usuario (recomendación, C1)

Confirmar es un acto explícito del usuario que declara exactamente esto:

> He revisado los datos de la versión guardada `N`, con huella `H`, tal como se
> muestran en pantalla (unidad, zona horaria y todas las franjas de cada
> parámetro con su valor), y corresponden a los datos que quiero tener
> registrados.

Propiedades:

- Recae sobre **una versión guardada e identificada**: número de versión más
  `content_sha256`. Nunca sobre el contenido de un editor, un texto pendiente ni
  un perfil «actual» sin número.
- Es un **hecho histórico append-only**, separado del contenido. No cambia la
  versión, su huella ni sus metadatos.
- Se registra con su propia identidad de operación, versión del contrato de
  confirmación, instante informativo y escritor (sección 4).
- Puede **revocarse** con otro hecho append-only enlazado (sección 4.4).
- **No declara** que los valores sean clínicamente correctos para calcular, que
  estén dentro de límites, que estén vigentes en un instante, que se resuelvan
  bien en un cambio de hora ni que sirvan para administrar insulina.

### 1.2 Qué no es confirmar (recomendación, C1)

| Acción | Dónde existe | Diferencia con confirmar datos |
|---|---|---|
| Guardar versión | ADR 0012 §7 | crea contenido nuevo. No declara revisión |
| Confirmar restauración | ADR 0012 §8, botón «Cargar la versión N en el editor» | solo carga contenido en un editor. No escribe ni confirma nada |
| Confirmar cambio de unidad | ADR 0012 §4.4, ADR 0013 §6 | acepta vaciar ISF y objetivo en el editor. No escribe ni confirma nada |
| Aceptar una recomendación del sistema | no existe. `system_proposal_accepted` sigue reservado y rechazado | sería crear una versión nueva. Una propuesta nunca confirma datos |
| Confirmar un tratamiento | fase 7, sin implementar | registra un hecho clínico de dosis. Ningún componente, estado ni texto se comparte con la confirmación de datos |
| Aprobación clínica del perfil | ADR posterior, sin definir | requiere límites y políticas aprobados. Es otro registro, no otra versión del contrato de confirmación |

### 1.3 Relación con el ADR 0012 (recomendación, C1)

El ADR 0012 §2.4 remitía el significado de «confirmado» a «un ADR posterior junto
con los límites aprobados». Este ADR propone **separar dos actos** que aquella
frase agrupaba:

1. **Confirmación de datos** (este ADR): el usuario revisa y declara sus propios
   datos guardados. No necesita límites ni evidencia clínica.
2. **Aprobación clínica** (ADR futuro): una política aprobada declara que una
   versión confirmada cumple límites, vigencia y reglas para que el motor pueda
   leerla. Requiere decisiones clínicas que hoy no existen.

Justificación:

- **Actores y evidencia distintos.** El usuario puede afirmar qué datos quiso
  introducir. No puede, con un botón, aprobar límites que aún no existen.
- **Invalidaciones distintas.** La confirmación se invalida por un cambio de
  datos (versión nueva) o una revocación. La aprobación clínica se invalidaría
  además por cambios de política, de límites o de motor.
- **Seguridad.** Unir ambos actos obliga a esperar a los límites para registrar
  revisiones, o tienta a presentar la revisión de datos como aprobación. Separarlos
  permite fijar ahora la mitad que no depende de reglas clínicas, con el uso
  clínico bloqueado.
- **Trazabilidad.** Un futuro cálculo podrá enlazar versión, huella, confirmación
  y aprobación como hechos independientes.

Este ADR precisa del ADR 0012, sin cambiar sus decisiones:

| ADR 0012 | Precisión propuesta |
|---|---|
| §2.4 «qué significa confirmado se decidirá … junto con los límites» | «confirmado» pasa a significar solo confirmación de datos. La aprobación clínica sigue pendiente y es otro acto |
| §5 esquema v1 | se propone v2 añadiendo una tabla de eventos. Tablas, triggers, huellas y serialización de v1 no cambian |
| §10 «toda versión futura añadirá su `onUpgrade` con validación completa» | se concreta en la sección 8 |
| §17 condición de revisión «significado de confirmado» | se activa con este ADR. El texto del ADR 0012 no se edita |

### 1.4 Texto actual de Bolo (pendiente, C7)

Bolo muestra hoy «Perfil · sin versión confirmada» y `ReadOverview` emite
siempre `input.profile.missing` sin leer el perfil. Hoy es exacto porque no hay
confirmaciones. Tras este incremento podría existir una versión confirmada y el
texto pasaría a ser falso. Se recomienda cambiarlo en la misma entrega por un
texto que no dependa del perfil, por ejemplo «Perfil · no se usa para calcular»,
sin que `ReadOverview` lea el perfil. El código `input.profile.missing` estático
de `ReadOverview` queda igual hasta que una entrega futura conecte la puerta,
lo cual exige su propio ADR. Ver C7.

## 2. Estados y puertas

### 2.1 Cuatro dimensiones separadas (recomendación, C1 y C2)

El estado del perfil no es una única enumeración. Son cuatro dimensiones que se
evalúan en orden, y cada una solo se evalúa si la anterior está demostrada:

| Dimensión | Valores | Fuente |
|---|---|---|
| **Lectura e integridad** | pendiente · fallida(motivo) · ausencia demostrada · historial íntegro | `ProfileHistory` (aprobado) más eventos de confirmación validados (sección 4.5) |
| **Completitud estructural** de la última versión | completa · incompleta(lista de faltas) | sección 3, sin límites clínicos |
| **Confirmación** de la última versión | sin confirmar · vigente(evento) · revocada(evento, revocación) | eventos append-only (sección 4) |
| **Elegibilidad clínica** | bloqueada(`policy_not_approved`) | siempre bloqueada en este incremento |

Consecuencias:

- «Confirmada» y «confirmada pero no usable» no son estados excluyentes. Son la
  misma versión vista en dos dimensiones. En este incremento **toda** versión
  con confirmación vigente tiene elegibilidad clínica bloqueada.
- Un fallo de lectura nunca se presenta como ausencia demostrada ni como «sin
  confirmar». Si la lectura no está demostrada, las dimensiones posteriores no se
  muestran como valores, sino como «no evaluable».
- La elegibilidad no deriva de la confirmación. Una futura aprobación clínica
  añadiría una puerta, no cambiaría el significado de las anteriores.

### 2.2 Matriz de estados

En todas las filas: `allowsCalculation = false`, `allowsTreatment = false`,
bloqueo `profile.not_approved_for_calculation`. La columna `input.profile.*` es
la correspondencia recomendada de la sección 6, documentada para el futuro y no
conectada a nada.

| # | Estado | Lectura e integridad | Completitud | Confirmación | Elegibilidad | Acciones permitidas | Bloqueado | `input.profile.*` recomendado | Detalle de dominio |
|---|---|---|---|---|---|---|---|---|---|
| E1 | Lectura pendiente | pendiente | no evaluable | no evaluable | bloqueada | esperar, cancelar | guardar, confirmar, revocar, restaurar | `unknown`, `policy_not_approved` | `profile.read.pending` (recomendado) |
| E2 | Lectura fallida | fallida | no evaluable | no evaluable | bloqueada | reintentar lectura | guardar, confirmar, revocar, restaurar | `persistence_failed`, `policy_not_approved` | `profile.storage.read_failed` o `profile.storage.corrupt` |
| E3 | Historial inválido | fallida por integridad | no evaluable | no evaluable | bloqueada | reintentar lectura | guardar, confirmar, revocar, restaurar. Sin reparación | `invalid`, `policy_not_approved` | `profile.storage.invalid_record` |
| E4 | Esquema no soportado | fallida por esquema | no evaluable | no evaluable | bloqueada | ninguna sobre el perfil | todo. Sin reparación ni degradación | `parse_failed`, `policy_not_approved` | `profile.storage.unsupported_schema` |
| E5 | Sin versión guardada | ausencia demostrada | no aplica | no aplica | bloqueada | crear perfil | confirmar, revocar, restaurar | `missing`, `policy_not_approved` | `profile.history.missing` |
| E6 | Última versión incompleta | íntegro | incompleta | sin confirmar | bloqueada | editar, guardar, restaurar, ver historial | confirmar | `incomplete`, `policy_not_approved` | `profile.gate.incomplete` con la lista de faltas |
| E7 | Completa sin confirmar | íntegro | completa | sin confirmar | bloqueada | revisar y confirmar, editar, guardar, restaurar | uso clínico | `policy_not_approved` | `profile.confirmation.missing` |
| E8 | Completa, una versión anterior confirmada | íntegro | completa o incompleta | sin confirmar (la anterior queda como historia) | bloqueada | como E6 o E7 | como E6 o E7. Nunca se usa la anterior | como E6 o E7 | `profile.confirmation.missing` más `profile.confirmation.superseded` |
| E9 | Confirmación vigente (confirmada y bloqueada para uso clínico) | íntegro | completa | vigente | **bloqueada** | ver confirmación, revocar, editar, guardar, restaurar | uso clínico | `policy_not_approved` | `profile.not_approved_for_calculation` |
| E10 | Confirmación revocada | íntegro | completa o incompleta (por ejemplo, tras revocar en E11) | revocada | bloqueada | revisar y confirmar de nuevo (acto nuevo, solo si está completa), editar, guardar, restaurar | uso clínico. Confirmar mientras esté incompleta | `policy_not_approved`, más `incomplete` o `invalid` si está incompleta | `profile.confirmation.revoked` y, si procede, las faltas |
| E11 | Confirmada, zona ya no reconocida por la plataforma | íntegro | incompleta (zona no reconocida) | vigente, como hecho histórico | bloqueada | ver confirmación, revocar, editar, guardar, restaurar | confirmar de nuevo, uso clínico | `invalid`, `policy_not_approved` | `profile.gate.time_zone_unrecognized` |

Notas:

- E9 y E11 son las filas con confirmación vigente, y ambas están bloqueadas
  para uso clínico. E9 es la fila «confirmada pero bloqueada para uso clínico»
  con la versión completa. E11 tiene además la completitud fallida. Una futura
  fila «confirmada y elegible» solo podría existir con un ADR de aprobación
  clínica.
- E11 cubre una actualización de la base de zonas del sistema que retire un
  identificador. La confirmación no se borra ni se revoca automáticamente: sigue
  siendo verdad que el usuario revisó esos datos. La puerta de completitud vuelve
  a fallar y lo explica. El usuario puede retirar la confirmación, porque revocar
  no exige completitud ni zona reconocida (sección 2.3).
- Mientras un guardado, una confirmación o una revocación están en vuelo, la UI
  bloquea las otras dos operaciones y muestra el estado anterior marcado como «en
  revisión», no el resultado esperado.

### 2.3 Precondiciones y transiciones (recomendación, C1, C3, C4, C6)

Confirmar la versión `N` exige, comprobado de nuevo dentro de la transacción:

1. Lectura demostrada e historial íntegro (versiones y eventos).
2. `N` es la última versión guardada.
3. La huella enviada coincide con la de `N` en la base.
4. `N` es estructuralmente completa (sección 3), incluida la comprobación de zona
   con la plataforma en ese momento.
5. `N` no tiene una confirmación vigente.
6. El último evento observado por el usuario coincide con el último evento
   almacenado (sección 7.2).
7. No hay cambios sin guardar en el editor (comprobado por la UI, sección 5.4).

Revocar la confirmación `c` exige:

1. Lectura demostrada e historial íntegro (versiones y eventos).
2. `c` es un evento de confirmación de la última versión y sigue vigente.
3. El último evento observado coincide con el almacenado.

Revocar **no** exige completitud ni zona reconocida por la plataforma. Retirar
una declaración nunca puede quedar bloqueado por la misma falta que la vuelve
dudosa (E11).

Transiciones:

```text
E5 --guardar--> E6 o E7
E6 --guardar versión completa--> E7
E7 --confirmar--> E9
E9 --revocar--> E10
E10 --confirmar (operación nueva)--> E9
E7, E9, E10 --guardar o restaurar--> E6 o E8 (versión nueva sin confirmar)
cualquier estado --lectura fallida--> E2, E3 o E4
E9 --zona retirada por la plataforma--> E11
E11 --revocar--> E10 (incompleta, no confirmable hasta corregir la zona)
E11 --guardar versión con zona reconocida--> E7 (versión nueva sin confirmar)
```

Ninguna transición lleva a un estado con elegibilidad clínica.

## 3. Completitud estructural

Reutiliza solo reglas ya aprobadas (ADR 0012 §4, ADR 0013 §4). **No añade
límites clínicos.**

| Regla | Origen | Falta reportada |
|---|---|---|
| Esquema de contenido y catálogo reconocidos | aprobado, ADR 0012 §4.2 | no llega a completitud: falla la lectura (E3 o E4) |
| Unidad de glucosa declarada y coherente con los valores | aprobado, ADR 0012 §4.4 | `unit_not_declared` |
| Zona IANA declarada | aprobado, ADR 0012 §3.1 | `time_zone_not_declared` |
| Zona reconocida por la plataforma en el momento de evaluar | aprobado para guardar (ADR 0012 §4.2). Recomendado también al confirmar y al evaluar la puerta | `time_zone_unrecognized` |
| Cobertura 00:00–24:00 por parámetro, sin huecos ni solapes | aprobado, ADR 0012 §4.2 y ADR 0013 §4 | no llega a completitud: el contenido no se construye (E3) |
| Valor configurado en **todas** las franjas de **todos** los parámetros | recomendación, C2 | `value_not_configured(parámetro, inicio, fin)` por franja |
| Decimales canónicos, huella e historial íntegros | aprobado, ADR 0012 §4.2, §4.5 y §16 | no llega a completitud: falla la lectura (E3) |

Precisiones:

- **«Sin configurar» frente a `0`.** `Entered("0")` está estructuralmente
  presente y cuenta como configurado. `NotConfigured` no. Un `0` explícito no
  acredita validez clínica: la pantalla de revisión lo destaca como «0 ·
  introducido explícitamente» para que el usuario lo revise, sin bloquear ni
  juzgar. Decidir si `0` es admisible para ratio o ISF es una regla clínica del
  motor y queda fuera (C2).
- **Máximo de 48 franjas.** Sigue siendo solo una restricción de «Dividir»
  (ADR 0013, T2). No es condición de lectura, completitud, confirmación ni
  elegibilidad. Una versión con más de 48 franjas completa puede confirmarse.
- **Zona.** Declarar una zona no resuelve horas repetidas ni inexistentes en
  cambios de hora y no aprueba ninguna política de vigencia. P4 del ADR 0012
  sigue pendiente.
- **Franjas adyacentes iguales.** Se conservan y no afectan a la completitud.
- La lista de faltas es completa y determinista (orden de catálogo y de
  `start_minute`). La UI muestra todas, no solo la primera.

Función propuesta, pura y en `shared/clinical-profile`:

```kotlin
sealed interface ProfileCompleteness {
    data object Complete : ProfileCompleteness
    data class Incomplete(val gaps: List<CompletenessGap>) : ProfileCompleteness   // no vacía
}
sealed interface CompletenessGap {
    data object UnitNotDeclared : CompletenessGap
    data object TimeZoneNotDeclared : CompletenessGap
    data class TimeZoneUnrecognized(val id: String) : CompletenessGap
    data class ValueNotConfigured(val parameter: ProfileParameter, val start: Int, val end: Int) : CompletenessGap
}
fun ProfileVersion.completeness(zones: TimeZoneRules): ProfileCompleteness
```

Los nombres son ilustrativos. Lo vinculante, si se aprueba, son los estados y
las reglas.

## 4. Confirmación y revocación append-only

### 4.1 Registros separados (recomendación, C1, C4, C8, C9)

Confirmaciones y revocaciones son **eventos** en una tabla propia. No se guardan
en `ProfileContent` ni en `ProfileVersion`, no forman parte de la huella y no
alteran ninguna fila de `profile_versions` ni de `profile_segments`.

Modelo ilustrativo:

```kotlin
enum class ConfirmationEventKind(val code: String) { CONFIRM("confirm"), REVOKE("revoke") }

data class ConfirmationEvent(
    val seq: Long,                    // orden autoritativo, contiguo 1..N
    val operationId: String,          // UUID canónico en minúsculas, generado por dependencia inyectada
    val kind: ConfirmationEventKind,
    val contractVersion: Int,         // contrato de confirmación de datos, 1
    val profileVersion: Long?,        // solo CONFIRM
    val contentSha256: String?,       // solo CONFIRM, igual a la huella de profileVersion
    val revokesSeq: Long?,            // solo REVOKE, confirmación revocada
    val observedEventSeq: Long,       // último evento que vio el usuario al decidir (0 si ninguno)
    val recordedAtEpochMs: Long,      // informativo, reloj inyectado
    val writer: String,               // mismo formato que en las versiones
)
```

### 4.2 Identidad y orden

- `operation_id` identifica la **intención** del usuario. Lo genera una
  dependencia inyectada (`OperationIds`) en el momento en que el usuario pulsa
  «Confirmar» o «Retirar», se conserva en el estado guardado de la actividad y
  se reutiliza en cualquier reintento de esa misma intención.
- `seq` es el **orden autoritativo**. Es contiguo desde 1 y lo asigna la
  transacción. No se usa el instante como orden.
- `recorded_at_ms` es informativo. Si el reloj del dispositivo está mal, la fecha
  lo refleja, pero no altera el orden ni el estado vigente.
- `writer` identifica cliente y build (`android/0.1.0-dev(1)`), sin identificadores
  de dispositivo ni datos personales.

### 4.3 Contrato de confirmación

`contract_version = 1` identifica el significado de la sección 1.1 y las
precondiciones de la sección 2.3. Si el significado cambiara, sería una versión
nueva del contrato y las confirmaciones v1 no contarían como v2. La aprobación
clínica futura **no** es una versión nueva de este contrato: es otro registro.

### 4.4 Revocación

- Una revocación es un evento `revoke` que enlaza **inequívocamente** una
  confirmación por `revokes_seq`. No enlaza «la confirmación actual» ni una
  versión.
- Una confirmación se revoca como máximo una vez (índice único).
- Revocar no borra ni modifica la confirmación. La historia muestra ambas.
- Revocar no crea versión, no cambia contenido y no reactiva confirmaciones
  anteriores.
- Volver a confirmar tras revocar exige una **nueva** acción explícita, con
  `operation_id` nuevo, sobre la versión vigente y con todas las precondiciones.

### 4.5 Derivación del estado vigente y validación

El estado se deriva, nunca se guarda como columna «confirmado»:

1. Leer versiones y eventos en **una** transacción.
2. Validar versiones como hoy (ADR 0012).
3. Validar eventos, en orden de `seq`:
   - `seq` contiguo 1..N, `operation_id` único y bien formado.
   - `kind` y `contract_version` conocidos (desconocido: `unsupported_schema`).
   - `confirm`: `profile_version` existe y `content_sha256` coincide con su huella.
   - `revoke`: `revokes_seq` apunta a un `confirm` anterior aún no revocado.
   - `observed_event_seq = seq - 1` (sección 7.2).
   - reproduciendo los eventos, ninguna versión tiene dos confirmaciones
     vigentes a la vez.
4. Para la última versión `L`: sin eventos `confirm` para `L` es «sin
   confirmar». Si el último `confirm` de `L` está revocado, «revocada». En otro
   caso, «vigente».
5. Las confirmaciones de versiones anteriores quedan como historia
   («superada por la versión `L`»). **Nunca** se usan como respaldo.

Cualquier fallo de validación hace fallar **todo** el estado con
`profile.storage.invalid_record`. Nunca se muestra «sin confirmar» por una
lectura que no se pudo demostrar (recomendación, C9).

### 4.6 Qué garantiza la huella y qué no

La huella SHA-256 detecta corrupción accidental, manipulación incoherente y
discrepancias entre lo revisado y lo guardado. **No** es firma, autenticación ni
prueba de autoría: quien pueda escribir el fichero puede recalcular huellas. Una
confirmación no prueba quién pulsó el botón. La protección del fichero sigue
siendo la del almacenamiento privado de la app, sin Auto Backup.

## 5. Versión vigente y cambios

### 5.1 Solo la última versión (recomendación, C3)

- Solo la **última versión guardada** puede ser candidata a un uso futuro, y
  solo con su **propia** confirmación vigente.
- Nunca se recurre a una versión anterior confirmada si la última no lo está,
  está incompleta, revocada o no se puede leer. No hay fallback histórico.
- Si la lectura falla, no hay candidata.

### 5.2 Guardar (recomendación, C5)

- Una versión nueva **no hereda** confirmación, aunque su contenido y su huella
  coincidan con los de una versión histórica confirmada. La confirmación se
  refiere a versión más huella, no solo a huella.
- Las confirmaciones anteriores se conservan como historia y pasan a
  «superadas».
- Guardar no crea ni revoca eventos.

### 5.3 Restaurar y cambiar unidad (aprobado más recomendación, C5)

- Restaurar crea una versión nueva (aprobado, ADR 0012 §8). Esa versión
  requiere confirmación nueva aunque la restaurada estuviera confirmada
  (recomendación).
- Cambio de unidad (aprobado, ADR 0012 §4.4 y ADR 0013 §6): sin conversión,
  fronteras conservadas y valores dependientes «Sin configurar» en la versión
  que cambia la unidad. La regla aprobada se mide contra la **unidad de
  partida** del editor (la última versión, o la versión restaurada si el editor
  nació de una restauración) y solo aplica si esa unidad estaba declarada.
- Por tanto, solo es incompleta por construcción (E6) y no confirmable la
  versión que **cambia desde una unidad de partida ya declarada**: todas las
  franjas de ISF y objetivo quedan «Sin configurar». Los valores en la unidad
  nueva van en la versión siguiente, que necesita su propia confirmación.
- Se conservan las dos excepciones aprobadas, que no vacían nada:
  - **Primera declaración de unidad** (de «no configurada» a una unidad): la
    versión puede llevar ISF y objetivo en esa unidad. Si queda completa, puede
    optar a confirmación como cualquier otra (E7).
  - **Restauración exacta de una versión con otra unidad** (origen `restored`):
    copia unidad y valores juntos, sin convertir. Si el contenido restaurado está
    completo, la versión nueva puede optar a una confirmación nueva. Nunca hereda
    la de la versión restaurada.
- Una edición manual a partir de una restauración compara con la unidad de la
  versión restaurada, como fija el ADR 0012 §4.4. Conservar esa unidad no vacía
  valores. Cambiarla sí.

### 5.4 Edición sin guardar frente a versión guardada (recomendación, C6)

Editar no crea versión. Riesgo: confirmar una versión guardada creyendo que se
confirma lo que se ve en el editor. Barreras propuestas:

1. «Revisar y confirmar» solo existe en la vista de solo lectura de la última
   versión, **nunca** dentro del editor.
2. Si existe un editor abierto con cambios (contenido distinto del de partida,
   textos pendientes no aplicados, panel de franjas abierto o cambio de unidad
   pendiente), la acción se deshabilita con el texto de la sección 9.
3. La pantalla de revisión se construye desde una lectura nueva de la base, no
   desde el editor. Muestra versión, huella corta, unidad, zona y todas las
   franjas.
4. La petición lleva `profile_version` y `content_sha256` de lo mostrado. La
   transacción rechaza si no coinciden con la última versión almacenada.
5. No existe «Guardar y confirmar». Tras guardar, el usuario vuelve a la vista
   de la versión guardada y confirma como acto separado.

## 6. Correspondencia con `input.profile.*`

### 6.1 Principios

- Documentación para el futuro. **No se conecta** a Bolo, `ReadOverview` ni al
  motor en este incremento. `ReadOverview` sigue emitiendo
  `input.profile.missing` estático.
- La correspondencia la haría un adaptador fuera del motor. El motor no depende
  del perfil y `shared/clinical-profile` no debería depender del motor por esto
  (pendiente, C7).
- No se inventan códigos publicados ni se cambia el significado de los
  existentes. Añadir un motivo exige revisar consumidores. Cambiar uno exige
  contrato nuevo (`unavailable-input-v1`).
- El motivo general convive con el **código de dominio específico**, que se
  conserva siempre. El general nunca sustituye al de dominio.
- Varias causas se conservan juntas en `UnavailableInputReport`, sin prioridad.
- **No se declara `expired`.** No hay política de vigencia aprobada.
- `policy_not_approved` se incluye en todos los estados (recomendación, C7): es
  verdad con independencia de los datos mientras no exista aprobación clínica.

### 6.2 Tabla de correspondencias

| Situación de dominio | Código de dominio | `input.profile.*` recomendado | Alternativa |
|---|---|---|---|
| Sin versión guardada (ausencia demostrada) | `profile.history.missing` | `missing` | — |
| Lectura pendiente | `profile.read.pending` (nuevo, de dominio) | `unknown` | no emitir informe hasta resolver. La puerta sigue cerrada |
| Lectura fallida del almacenamiento | `profile.storage.read_failed` | `persistence_failed` | — |
| Base corrupta | `profile.storage.corrupt` | `persistence_failed` | `invalid` |
| Fila o historial que no supera validación (huella, hueco, procedencia, decimal, eventos) | `profile.storage.invalid_record` | `invalid` | `parse_failed` para decimal o código ilegible. Exigiría separar el código de dominio |
| Esquema de base o de contenido desconocido | `profile.storage.unsupported_schema` | `parse_failed` | `unknown` |
| Última versión incompleta | `profile.gate.incomplete` y sus faltas | `incomplete` | — |
| Zona declarada no reconocida por la plataforma | `profile.gate.time_zone_unrecognized` | `invalid` | `incomplete` |
| Completa sin confirmar | `profile.confirmation.missing` | ninguno específico. Solo `policy_not_approved` | nuevo motivo `unconfirmed` en el contrato, con revisión de consumidores |
| Confirmación revocada | `profile.confirmation.revoked` | ninguno específico. Solo `policy_not_approved` | el mismo nuevo motivo, o `unconfirmed` y `revoked` como dos motivos |
| Confirmación vigente | `profile.not_approved_for_calculation` | `policy_not_approved` | — |
| Cualquier estado sin aprobación clínica | `profile.not_approved_for_calculation` | `policy_not_approved` | — |

Errores de **operación** (guardar, confirmar, revocar) no son estados de entrada:
se devuelven al llamante y la UI los muestra, pero no producen `input.profile.*`.
En particular `conflicting` **no** se emite por un conflicto de escritura
(`profile.storage.revision_conflict`, `profile.confirmation.state_changed`): el
estado leído después sigue siendo coherente. `conflicting` quedaría para dos
fuentes de perfil con identidades en conflicto, que hoy no existen.

### 6.3 Falta de expresividad (pendiente, C7)

El contrato v1 no tiene un motivo para «datos completos sin confirmar» ni para
«confirmación revocada». Forzarlo en `missing` (hay datos), `incomplete` (están
completos), `invalid` (no lo son) o `expired` (no hay política) cambiaría su
significado. Opciones:

- **A (recomendada para este incremento):** no emitir motivo general específico.
  El informe lleva `policy_not_approved` y el código de dominio conserva la causa.
  Válido mientras nada lea la puerta, porque `policy_not_approved` ya bloquea
  todo.
- **B:** añadir `unconfirmed` (y opcionalmente `revoked`) a `UnavailabilityReason`
  como adición del contrato v1, con revisión de consumidores y pruebas comunes.
- **C:** contrato v2 con detalle de dominio estructurado.

Antes de que una puerta futura deje de depender de `policy_not_approved` (es
decir, antes de cualquier aprobación clínica), A deja de bastar y hay que elegir
B o C.

### 6.4 Ejemplos sintéticos

```text
E5 sin versión          -> [input.profile.missing, input.profile.policy_not_approved]
                           dominio: profile.history.missing
E6 ISF 06:00-12:00 vacía -> [input.profile.incomplete, input.profile.policy_not_approved]
                           dominio: profile.gate.incomplete
                                    value_not_configured(insulin_sensitivity, 360, 720)
E7 completa sin confirmar -> [input.profile.policy_not_approved]
                           dominio: profile.confirmation.missing
E9 confirmación vigente -> [input.profile.policy_not_approved]
                           dominio: profile.not_approved_for_calculation
E10 revocada            -> [input.profile.policy_not_approved]
                           dominio: profile.confirmation.revoked
E2 fallo de lectura     -> [input.profile.persistence_failed, input.profile.policy_not_approved]
                           dominio: profile.storage.read_failed
E3 + zona irreconocible -> no aplica: con historial inválido no se evalúa completitud
```

El orden de las listas es el lexicográfico técnico del informe, no prioridad.

## 7. Concurrencia e idempotencia

### 7.1 Operación atómica (recomendación, C8)

`confirm(request, recordedAtEpochMs, writer)` y `revoke(...)` se ejecutan en una
transacción exclusiva de SQLite, igual que `save`:

1. Releer y validar versiones y eventos completos. Si no se demuestra:
   `profile.storage.invalid_record` y nada se escribe ni se devuelve como
   histórico.
2. Si `operation_id` ya existe: comparar la carga útil almacenada (tipo, versión,
   huella, `revokes_seq`, `observed_event_seq`, contrato). Igual: devolver
   `Recorded(evento histórico, replayed = true)` junto al **estado vigente
   actual**, derivado del mismo historial ya validado. Distinta:
   `profile.confirmation.operation_mismatch`. No se inserta nada en ningún caso.
   Esta rama solo se alcanza después del paso 1: un reintento nunca devuelve
   éxito, ni histórico ni actual, sobre un historial que no se puede demostrar.
3. Comprobar `observed_event_seq` contra el último `seq`. Distinto:
   `profile.confirmation.state_changed`.
4. `confirm`: la versión pedida es la última (si no, `profile.confirmation.stale_version`),
   la huella coincide (si no, `stale_version`), completitud con la plataforma (si
   no, `profile.confirmation.incomplete`) y sin confirmación vigente (si no,
   `profile.confirmation.already_active`).
5. `revoke`: el objetivo es un `confirm` vigente de la última versión (si no,
   `profile.confirmation.not_active`).
6. Insertar, releer lo insertado dentro de la transacción y devolver
   `Recorded(replayed = false)` solo tras el commit.

Los rechazos no se persisten. No hay «última escritura gana», ni fusión, ni
confirmación optimista: la UI no muestra «Datos confirmados» hasta recibir
`Recorded` del commit.

Los códigos `profile.confirmation.*` son propuesta y no existen en el código.

### 7.2 Estado observado

El usuario confirma o revoca lo que vio. Por eso la petición incluye
`observed_event_seq`, el último evento de la lectura que generó la pantalla. Si
otra conexión confirmó, revocó o reconfirmó entre medias, la operación se
rechaza y la pantalla se refresca. Con la regla estricta propuesta, el valor
almacenado siempre es `seq - 1`. Se guarda igualmente para que la carga útil del
reintento sea completa y para que la auditoría muestre sobre qué estado se
decidió. La alternativa laxa (solo comprobar «sin confirmación vigente») es C8.

### 7.3 Escenarios

| Escenario | Resultado |
|---|---|
| Doble toque | el botón se deshabilita en vuelo y ambos toques comparten `operation_id`. El segundo, si llega, devuelve el histórico |
| Dos conexiones confirman la misma versión con operaciones distintas | la transacción exclusiva serializa. La primera inserta. La segunda falla con `state_changed` (o `already_active`). Nunca dos confirmaciones vigentes |
| Se guarda una versión nueva mientras se revisa la anterior | `stale_version`. La pantalla ofrece revisar la versión nueva. No se confirma nada |
| Confirmación concurrente con revocación | la que entra después ve `observed_event_seq` distinto y falla con `state_changed`. Ninguna se aplica sobre un estado no visto |
| Respuesta perdida tras el commit | el reintento con el mismo `operation_id` devuelve el evento histórico (`replayed = true`) |
| Reintento tras reinicio de la actividad | `operation_id` se conserva en el estado guardado. Al recrear, la operación pendiente se resuelve primero por su identidad (sección 9.3). Si ya consta, se muestra como registrada. Si no, el reintento sigue siendo idempotente |
| Commit terminado antes de recrear la pantalla | la lectura posterior encuentra `operation_id` con la misma carga útil. Se muestra «registrada», nunca `state_changed` ni fracaso, aunque `observed_event_seq` ya no sea el último evento |
| Reinicio completo del proceso sin estado guardado | la intención se pierde. La nueva lectura muestra el estado real. Si el commit ocurrió, aparece «Datos confirmados». Si no, el usuario vuelve a confirmar con operación nueva. No hay cola persistente |
| Misma clave con carga útil distinta | `operation_mismatch`. Nada se escribe |
| Reintento de una confirmación antigua tras revocarla | devuelve el evento histórico con `replayed = true` y estado vigente «revocada». **No** reactiva la confirmación |
| Commit fallido | excepción, transacción revertida, `profile.storage.save_failed`. La UI conserva el estado anterior y no afirma nada |
| Operación sin red | igual que con red. Todo es local |

### 7.4 Éxito histórico frente a estado vigente

`Recorded(replayed = true)` significa «esa operación se registró en su día». No
significa «está vigente ahora». La UI muestra siempre el estado vigente derivado
de la lectura posterior, y un `replayed` sobre una confirmación revocada se
explica como tal (sección 9). Una reconfirmación exige una acción nueva.

### 7.5 Guardar versiones

`save` (ADR 0012 §7) no cambia su lógica, pero su transacción pasa a validar
también los eventos antes de añadir (recomendación, C9). No se añade a una base
cuyo historial completo no se puede demostrar.

## 8. Esquema y migración propuestos

### 8.1 Tres versiones distintas

| Versión | Valor | Qué identifica |
|---|---|---|
| Base de datos (`user_version`) | 1 → **2** | tablas, índices y triggers de `clinical-profile.db` |
| Esquema de contenido (`schema_version`) | **1**, sin cambios | catálogo y forma de `ProfileContent` |
| Contrato de confirmación (`contract_version`) | **1**, nuevo | significado y precondiciones de confirmar (sección 4.3) |

La serialización canónica (`ProfileCodec`) y todas las huellas existentes no
cambian.

### 8.2 Objetos nuevos (ilustrativo)

```sql
CREATE TABLE profile_confirmation_events (
    seq                 INTEGER PRIMARY KEY CHECK(seq > 0),
    operation_id        TEXT NOT NULL UNIQUE CHECK(length(operation_id) = 36),
    kind                TEXT NOT NULL,
    contract_version    INTEGER NOT NULL CHECK(contract_version > 0),
    profile_version     INTEGER REFERENCES profile_versions(version),
    content_sha256      TEXT CHECK(content_sha256 IS NULL OR length(content_sha256) = 64),
    revokes_seq         INTEGER REFERENCES profile_confirmation_events(seq),
    observed_event_seq  INTEGER NOT NULL CHECK(observed_event_seq >= 0 AND observed_event_seq < seq),
    recorded_at_ms      INTEGER NOT NULL,
    writer              TEXT NOT NULL CHECK(length(writer) BETWEEN 1 AND 128),
    CHECK ((kind = 'confirm' AND profile_version IS NOT NULL AND content_sha256 IS NOT NULL
                             AND revokes_seq IS NULL)
        OR (kind = 'revoke'  AND profile_version IS NULL AND content_sha256 IS NULL
                             AND revokes_seq IS NOT NULL AND revokes_seq < seq))
);

CREATE UNIQUE INDEX profile_confirmation_one_revocation
    ON profile_confirmation_events(revokes_seq) WHERE revokes_seq IS NOT NULL;
CREATE INDEX profile_confirmation_by_version
    ON profile_confirmation_events(profile_version) WHERE profile_version IS NOT NULL;

CREATE TRIGGER profile_confirmation_immutable_update BEFORE UPDATE ON profile_confirmation_events
    BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END;
CREATE TRIGGER profile_confirmation_immutable_delete BEFORE DELETE ON profile_confirmation_events
    BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END;

-- Orden contiguo y estado observado igual al almacenado.
CREATE TRIGGER profile_confirmation_contiguous BEFORE INSERT ON profile_confirmation_events
    WHEN NEW.seq <> COALESCE((SELECT MAX(seq) FROM profile_confirmation_events), 0) + 1
      OR NEW.observed_event_seq <> NEW.seq - 1
    BEGIN SELECT RAISE(ABORT, 'profile.confirmation.state_changed'); END;

-- Solo la última versión, con su huella exacta.
CREATE TRIGGER profile_confirmation_latest_exact BEFORE INSERT ON profile_confirmation_events
    WHEN NEW.kind = 'confirm' AND (
         NEW.profile_version <> (SELECT MAX(version) FROM profile_versions)
      OR NEW.content_sha256 IS NOT
         (SELECT content_sha256 FROM profile_versions WHERE version = NEW.profile_version))
    BEGIN SELECT RAISE(ABORT, 'profile.confirmation.stale_version'); END;

-- Nunca dos confirmaciones vigentes para la misma versión.
CREATE TRIGGER profile_confirmation_single_active BEFORE INSERT ON profile_confirmation_events
    WHEN NEW.kind = 'confirm' AND EXISTS (
         SELECT 1 FROM profile_confirmation_events c
          WHERE c.kind = 'confirm' AND c.profile_version = NEW.profile_version
            AND NOT EXISTS (SELECT 1 FROM profile_confirmation_events r WHERE r.revokes_seq = c.seq))
    BEGIN SELECT RAISE(ABORT, 'profile.confirmation.already_active'); END;

-- Solo se revoca una confirmación de la última versión.
CREATE TRIGGER profile_confirmation_revoke_target BEFORE INSERT ON profile_confirmation_events
    WHEN NEW.kind = 'revoke' AND NOT EXISTS (
         SELECT 1 FROM profile_confirmation_events c
          WHERE c.seq = NEW.revokes_seq AND c.kind = 'confirm'
            AND c.profile_version = (SELECT MAX(version) FROM profile_versions))
    BEGIN SELECT RAISE(ABORT, 'profile.confirmation.not_active'); END;
```

Reparto, igual que en el ADR 0012 §5.1: SQLite impone estructura estable,
inmutabilidad y las reglas de inserción. El dominio compartido aplica la misma
política dentro de la transacción y revalida todo al leer (doble barrera).

A diferencia del ADR 0012, que evitó `CHECK(... IN ...)` para catálogos que
crecerán, aquí `kind` sí se restringe en un `CHECK`: los tipos de evento forman
parte del contrato de confirmación v1, y uno nuevo sería contrato y migración
nuevos. La alternativa de dos tablas (`confirmations`, `revocations`) obliga a
un tercer objeto para tener un orden único. Ver C9.

### 8.3 Creación limpia de v2

`onCreate` crea en la misma transacción los objetos de v1 (sin cambios) y los de
8.2. Si el fichero tiene objetos ajenos con `user_version = 0`, falla con
`unsupported_schema` sin tocarlos (como hoy). El resultado debe ser idéntico al
de migrar una v1 vacía: misma lista de objetos en `sqlite_master`.

### 8.4 Migración v1 → v2

`onUpgrade(db, 1, 2)` dentro de la transacción del helper:

1. Comprobar el esquema v1 por **definición**, no solo por inventario.
   Cualquier diferencia: `unsupported_schema`.
   - **Inventario exacto** de `sqlite_master`: tablas `profile_versions` y
     `profile_segments`, los cinco triggers de v1, el índice automático
     `sqlite_autoindex_profile_segments_1` de la clave primaria compuesta y la
     tabla `android_metadata` que crea la plataforma. Ningún objeto más ni
     menos.
   - **Definiciones exactas**: el campo `sql` de cada tabla y trigger de perfil
     se compara byte a byte con el texto de v1 congelado en el código
     (`SCHEMA_V1`). SQLite conserva el texto `CREATE` original, así que la
     comparación cubre columnas, `CHECK`, claves foráneas y cuerpos de trigger.
     El texto de `SCHEMA` se introdujo en `e78563a` y no ha cambiado desde
     entonces (`git log -L` sobre `SqliteClinicalProfileRepository.kt`). Un
     fichero creado por un build no integrado con otro texto se rechaza.
   - **Índice automático**: su `sql` es `NULL`, así que se comprueba con
     `PRAGMA index_list(profile_segments)` (un único índice, `unique = 1`,
     `origin = 'pk'`) y `PRAGMA index_info` (columnas `version`, `parameter`,
     `start_minute`, en ese orden).
   - `android_metadata` no aporta garantías de perfil. Solo se exige su
     presencia y la columna `locale`.
   - Comprobar solo nombres y columnas no basta: un trigger sustituido por otro
     permisivo con el mismo nombre conserva ese inventario (comprobado por el
     propietario en SQLite en memoria). La comparación de definiciones lo
     rechaza.
2. `PRAGMA foreign_key_check` vacío y `PRAGMA quick_check` igual a `ok`. Si no:
   `corrupt`.
3. Leer y validar el historial completo con el dominio: decodificación,
   catálogo, cobertura, huellas, contigüidad, procedencia y transiciones de
   unidad. Si no: `invalid_record`.
4. Crear los objetos de 8.2.
5. **No** crear confirmaciones. Tras migrar, toda versión aparece «sin
   confirmar».
6. Conservar versiones, franjas, huellas, origen, `restored_from`, fechas y
   escritores sin reescribir ninguna fila.

Tras crear los objetos de 8.2, el esquema resultante se compara también por
definición con el texto congelado de v2 (`SCHEMA_V2`), igual que una creación
limpia.

Cualquier excepción revierte la transacción: el fichero queda en v1 intacto y
la pantalla muestra el motivo, nunca «sin perfil». No hay reparación
destructiva, borrado de filas ni recreación de la base. El siguiente arranque
vuelve a intentar la migración desde el mismo estado.

### 8.5 Apertura, degradación y recuperación

- Apertura de v2: comparación de definiciones con `SCHEMA_V2` (inventario,
  `sql` de tablas, índices con nombre y triggers, e índices automáticos por
  `PRAGMA`) al abrir, y validación completa de versiones y eventos en cada
  lectura. Una definición distinta falla con `unsupported_schema` sin tocar el
  fichero.
- `user_version` mayor que 2: `unsupported_schema`.
- Degradación: un build v1 que abre una base v2 ya falla hoy con
  `unsupported_schema` en `onDowngrade` y no la toca. Un build v2 hace lo mismo
  con una v3.
- Rollback de código tras migrar: el build anterior no puede leer el perfil y
  falla cerrado. No hay pérdida de datos. Se recupera instalando de nuevo un build
  compatible con v2.
- `meal-drafts.db` no cambia (sigue en v3) y no se abre desde el código de perfil.
- Auto Backup y transferencia siguen excluidos, y `verify.ps1` lo sigue
  comprobando.
- No se introduce exportación ni importación de producto. La copia previa es una
  **prueba**, no una función.

### 8.6 Prueba de copia previa

Caso válido:

1. Crear con el esquema v1 congelado (fixture de prueba) una base con historial
   sintético de varias versiones, franjas, `0`, «Sin configurar» y restauración.
2. Cerrar la base y copiar el fichero.
3. Migrar el original a v2 y validarlo.
4. Abrir la copia con el adaptador v1 congelado: se lee y valida idéntica.

Caso corrupto:

1. Crear la misma base v1 e introducir una fila manipulada (por ejemplo, una
   huella que no corresponde a su contenido, o un decimal no canónico).
2. Cerrar la base y copiar el fichero.
3. Intentar migrar el original: falla con el motivo estable y revierte.
4. El original conserva `user_version = 1` y el mismo contenido lógico,
   **incluida la fila corrupta**. No se repara ni se borra nada.
5. Original y copia, abiertos con el adaptador v1 congelado, se **siguen
   rechazando** con el mismo motivo que antes de intentar la migración. Ninguno
   se lee como válido.

Caso de esquema alterado: una v1 con un trigger sustituido por otro permisivo
del mismo nombre, o con un `CHECK` distinto, se rechaza con
`unsupported_schema` antes de leer filas y queda intacta.

## 9. UI propuesta

### 9.1 Principios

- «Datos confirmados» aparece **siempre** junto a «Cálculo todavía bloqueado».
- Prohibido: «perfil válido», «perfil aprobado», «apto para dosificar», «listo
  para calcular», «perfil activo» o un «Confirmar» sin objeto.
- Confirmar datos, guardar versión, cargar una restauración y confirmar un
  tratamiento tienen textos, botones y estados distintos.
- Todo error muestra su código estable.

### 9.2 Textos

Estado en la vista de la última versión:

| Estado | Texto |
|---|---|
| E5 | «Sin perfil guardado. Todo está sin configurar.» (actual) |
| E6 | «Versión 4 · datos incompletos · no se puede confirmar todavía. Cálculo todavía bloqueado.» |
| E7 | «Versión 4 · datos sin confirmar. Cálculo todavía bloqueado.» |
| E8 | «Versión 5 · datos sin confirmar. La confirmación de la versión 4 queda en el historial y no se aplica a la versión 5. Cálculo todavía bloqueado.» |
| E9 | «Datos confirmados · versión 4. Cálculo todavía bloqueado: faltan límites y reglas clínicas aprobados.» |
| E10 | «Confirmación retirada · versión 4. Revisa los datos y vuelve a confirmarlos si son correctos. Cálculo todavía bloqueado.» |
| E11 | «Datos confirmados · versión 4, pero la zona horaria Europe/Madrid ya no se reconoce en este dispositivo. Revisa la zona o retira la confirmación. Cálculo todavía bloqueado.» |
| E1 | «Leyendo perfil y confirmaciones…» |
| E2 a E4 | «No se pudo leer el perfil ni su estado de confirmación · `<código>`. No se muestra como sin confirmar.» |

Acción de revisión: «Revisar y confirmar los datos de la versión 4…»

Pantalla de revisión:

> **Revisar datos de la versión 4**
>
> Vas a declarar que has revisado estos datos guardados y que son los que
> quieres registrar. Esto no aprueba límites, no comprueba que los valores sirvan
> para calcular y no autoriza ninguna dosis. El cálculo sigue bloqueado.
>
> Versión 4 · guardada el 03/10/2026 10:42 (hora del dispositivo) · huella
> `3f9a…c21e`
> Unidad de glucosa: mg/dL
> Zona horaria: Europe/Madrid
> Ratio de hidratos (g/U): 00:00–06:00 · 12 · 06:00–24:00 · 10
> Sensibilidad (ISF) (mg/dL/U): 00:00–24:00 · 0 · introducido explícitamente
> Objetivo glucémico (mg/dL): 00:00–24:00 · 110
>
> [Confirmar datos de la versión 4] [Volver sin confirmar]

(Valores sintéticos, solo de presentación.)

Bloqueos de la acción:

- Incompleta: «No se puede confirmar: faltan datos.» seguido de cada falta:
  «Unidad de glucosa sin declarar», «Zona horaria sin declarar», «Zona horaria
  no reconocida en este dispositivo: `<id>`», «Sensibilidad (ISF) 06:00–12:00 ·
  Sin configurar».
- Cambios sin guardar: «Tienes cambios sin guardar en el editor. Guárdalos o
  descártalos antes de confirmar. Solo se confirman versiones guardadas.»

Durante y después:

- En vuelo: «Registrando confirmación…»
- Éxito: «Datos confirmados · versión 4. Cálculo todavía bloqueado.»
- Reintento histórico sobre una confirmación luego revocada: «Esta confirmación
  ya se había registrado y después se retiró. No se ha vuelto a confirmar.»
- `stale_version`: «No se confirmó: existe una versión más reciente (5). Revisa
  la versión 5 antes de confirmar.»
- `state_changed`: «No se confirmó: el estado de confirmación cambió mientras
  revisabas. Vuelve a revisarlo.»
- `already_active`: «La versión 4 ya tiene una confirmación vigente.»
- `incomplete`: «No se confirmó: la versión 4 está incompleta en este
  dispositivo.» con la lista de faltas.
- Fallo de escritura: «No se pudo registrar la confirmación · `<código>`. No se
  ha confirmado nada.»

Revocación:

- Acción: «Retirar la confirmación de la versión 4…»
- Diálogo: «Los datos de la versión 4 dejarán de constar como confirmados. La
  versión y sus valores no cambian. Para volver a confirmarlos tendrás que
  revisarlos otra vez.» Botones «Retirar confirmación» y «Cancelar».
- Éxito: «Confirmación retirada · versión 4.»
- `not_active`: «No se retiró: esa confirmación ya no está vigente.»

Guardado y restauración tras confirmar:

- Al guardar desde E9: «Guardado · versión 5 · datos sin confirmar.»
- Al cargar una restauración de una versión confirmada: «La versión que se
  guarde será nueva y necesitará su propia confirmación. La confirmación de la
  versión 2 no se hereda.»

Historial, por versión: «Confirmada el `<fecha>`», «Confirmación retirada el
`<fecha>`», «Superada por la versión `<n>`». Fechas en hora del dispositivo,
orden por `seq`.

Bolo (C7): «Perfil · no se usa para calcular», sin leer el perfil.

### 9.3 Recreación y operación pendiente

La pantalla de revisión guarda versión, huella, `observed_event_seq`,
`operation_id` pendiente y su carga útil. Al recrear, relee versiones y eventos
en una transacción y aplica este orden:

1. **Lectura no demostrada** (pendiente, fallida o historial inválido): se
   conserva la operación pendiente sin reintentarla y se muestra «No se sabe
   todavía si la confirmación se registró · `<código>`. Reintenta la lectura
   antes de volver a confirmar.» No se presenta como fracaso ni como éxito.
2. **Resolver por identidad.** Si el historial validado contiene
   `operation_id`:
   - con la misma carga útil: la operación terminó antes de recrear. Se muestra
     como registrada, con el estado vigente actual (que puede ser ya
     «revocada» si otra acción posterior la retiró). Se descarta la operación
     pendiente.
   - con otra carga útil: `operation_mismatch` visible. No se reintenta.
3. **La operación no consta.** Solo entonces se comparan versión, huella y
   `observed_event_seq` con lo almacenado. Si algo cambió, se cierra la
   revisión con `stale_version` o `state_changed` visible, se descarta la
   operación pendiente y no se confirma. Si nada cambió, la revisión sigue
   abierta y el usuario puede repetir la acción con el mismo `operation_id`.

Así una confirmación ya registrada nunca se presenta como conflicto ni como
fracaso. El mismo orden aplica a una revocación pendiente.

Texto para el paso 2: «La confirmación de la versión 4 ya se había registrado.»

## 10. Pruebas previstas

Sin implementar en esta entrega. Todas con datos sintéticos.

### Comunes (`shared/clinical-profile`, JVM)

- matriz E1 a E11 completa, cada fila con sus dimensiones y códigos.
- ausencia frente a `0`: `0` cuenta como configurado, «Sin configurar» como falta,
  en cada parámetro y franja.
- completitud por franja: una sola franja vacía entre varias configuradas, lista
  de faltas completa y ordenada.
- unidad no declarada, zona no declarada, zona no reconocida por un
  `TimeZoneRules` falso.
- versión con más de 48 franjas completa: confirmable.
- derivación: sin confirmar, vigente, revocada, reconfirmada, superada por versión
  nueva, nunca fallback a una anterior.
- versión nueva con huella igual a una histórica confirmada: sin confirmar.
- restauración de una versión confirmada: versión nueva sin confirmar.
- versión que cambia desde una unidad de partida declarada: incompleta y no
  confirmable. Primera declaración de unidad con valores completos:
  confirmable. Restauración exacta completa de una versión con otra unidad:
  confirmable con confirmación nueva, sin heredar la de origen.
- revocación desde E11: aceptada sin completitud ni zona reconocida. Después,
  E10 incompleta no confirmable.
- validación de eventos: hueco o duplicado en `seq`, `operation_id` repetido o
  mal formado, `kind` o contrato desconocidos, huella distinta, versión
  inexistente, revocación de revocación, doble revocación, dos confirmaciones
  vigentes, `observed_event_seq` incoherente. Todo falla cerrado sin mostrar
  «sin confirmar».
- política de operación: idempotencia, `operation_mismatch`, `stale_version`,
  `state_changed`, `already_active`, `not_active`, `incomplete`.
- reintento de una confirmación revocada: histórico con `replayed` y estado
  vigente «revocada».
- reintento con historial o eventos manipulados: `invalid_record`, nunca
  `Recorded`, aunque `operation_id` exista.
- resolución de operación pendiente al recrear (sección 9.3): lectura fallida
  conserva la operación sin éxito ni fracaso. Operación encontrada con la misma
  carga útil se muestra registrada aunque `observed_event_seq` haya quedado
  atrás. Operación no encontrada con estado cambiado da `state_changed`.
- correspondencia `input.profile.*` de la sección 6, conservando el código de
  dominio y sin `expired`.
- todos los estados: `allowsCalculation = false` y `allowsTreatment = false`.

### SQLite en dispositivo

- creación limpia de v2 equivalente a migrar una v1 vacía (`sqlite_master`).
- migración de v1 vacía y de v1 con historial: filas, huellas y procedencia
  idénticas, cero confirmaciones.
- rollback ante corrupción, esquema ajeno, columna extra o huella manipulada:
  sigue en v1 con contenido lógico intacto.
- inventario v1 con el índice automático `sqlite_autoindex_profile_segments_1`
  aceptado. Índice ausente, objeto adicional, trigger sustituido por otro
  permisivo con el mismo nombre o `CHECK` alterado: `unsupported_schema`.
- definiciones de v2 tras crear y tras migrar idénticas a `SCHEMA_V2`. Una v2
  con un trigger alterado se rechaza al abrir.
- copia previa de la sección 8.6.
- `user_version` futura y degradación: `unsupported_schema`.
- triggers: `UPDATE` y `DELETE` abortan, inserción no contigua, versión no
  última, huella distinta, segunda confirmación vigente, revocación de
  confirmación no vigente o de otra versión.
- dos conexiones confirmando a la vez, confirmación contra revocación, versión
  nueva entre lectura y confirmación.
- commit fallido inyectado: nada insertado y reintento correcto después.
- reinicio del repositorio: estado idéntico.
- `save` rechaza añadir si los eventos no validan.
- `meal-drafts.db` intacta en v3.

### UI en dispositivo

- E5 a E11 visibles con sus textos, siempre con «Cálculo todavía bloqueado».
- confirmar no disponible dentro del editor ni con cambios pendientes, textos
  pendientes, panel abierto o cambio de unidad pendiente.
- la revisión muestra versión, huella corta, unidad, zona y todas las franjas, y
  destaca `0`.
- doble toque, rotación y recreación con revisión abierta y operación en vuelo.
- commit terminado antes de recrear: la confirmación aparece como registrada,
  nunca como conflicto ni fracaso.
- E11 ofrece «Retirar la confirmación» y, tras retirarla, no ofrece confirmar
  hasta corregir la zona.
- versión nueva guardada desde otra conexión durante la revisión: `stale_version`
  visible.
- errores de lectura y escritura visibles, nunca como «sin confirmar».
- revocar y reconfirmar como acciones separadas.
- historial con confirmaciones, revocaciones y superadas.
- layouts 840×900, 900×840 y 411×914 dp, texto 1,0× y 1,8×, tema claro y oscuro.

### Arquitectura y verificación

- motor, Bolo y `ReadOverview` siguen sin depender del perfil ni de las
  confirmaciones (`verify.ps1`).
- Bolo no muestra «Datos confirmados» ni lee el perfil.
- clases nuevas en la lista `-e class` de `scripts/verify.ps1`.
- `scripts/verify.ps1 -DeviceTests` con un único dispositivo autorizado y
  repositorios sintéticos aislados.

## 11. Decisiones del propietario (C1 a C9)

Todas **aprobadas** el 2026-10-03 con la recomendación de cada una. Ninguna aprueba límites clínicos, vigencia,
DIA, IOB ni resolución de cambios de hora, y ninguna sustituye pendientes
anteriores (P4 y P6 del ADR 0012 siguen abiertas).

### C1. Significado y alcance de la confirmación de datos

- Pregunta: ¿qué declara exactamente el usuario al confirmar?
- Alternativas: (a) revisión explícita de los datos de una versión guardada
  identificada por número y huella, sin aprobación clínica. (b) confirmación más
  aprobación clínica en un solo acto, esperando a los límites. (c) no confirmar
  nada hasta tener límites.
- Recomendación: (a), con el texto de la sección 1.1 y la separación de la 1.3.
- Justificación: registra ya lo que el usuario puede afirmar sin presentar nada
  como apto para calcular.
- Si no se resuelve: no hay forma de distinguir revisión de captura, el texto de
  Bolo sigue ambiguo y el futuro ADR de aprobación clínica no tiene sobre qué
  apoyarse.
- Estado: aprobada (2026-10-03) con la recomendación.

### C2. Precondiciones estructurales, incluida la distinción entre 0 y ausencia

- Pregunta: ¿qué debe cumplir una versión para poder confirmarse?
- Alternativas: (a) todas las reglas estructurales aprobadas más valor
  configurado en todas las franjas, con `0` contado como configurado. (b) igual
  que (a) pero bloqueando `0`. (c) permitir confirmar versiones incompletas
  marcándolas.
- Recomendación: (a), con zona comprobada contra la plataforma al confirmar y al
  evaluar, `0` destacado en la revisión y el límite de 48 fuera de la puerta.
- Justificación: (b) sería una regla clínica sin aprobar. (c) haría de la
  confirmación una etiqueta sin significado.
- Si no se resuelve: no se puede implementar la puerta de completitud y el
  riesgo de confundir «Sin configurar» con `0` sigue sin cubrir en esta etapa.
- Estado: aprobada (2026-10-03) con la recomendación.

### C3. Política de última versión y ausencia de fallback histórico

- Pregunta: ¿qué versión puede ser candidata a un uso futuro?
- Alternativas: (a) solo la última guardada con su propia confirmación vigente.
  (b) la última confirmada aunque exista una posterior sin confirmar. (c) elegir
  manualmente una versión «activa».
- Recomendación: (a).
- Justificación: (b) usaría datos que el usuario ya cambió. (c) introduce un
  puntero mutable y perfiles alternativos, fuera de alcance.
- Si no se resuelve: un futuro adaptador podría caer a una versión antigua sin
  decisión documentada.
- Estado: aprobada (2026-10-03) con la recomendación.

### C4. Revocación y eventual reconfirmación explícita

- Pregunta: ¿cómo se retira una confirmación y cómo se vuelve a confirmar?
- Alternativas: (a) evento `revoke` enlazado por `seq` a una confirmación vigente
  de la última versión, reconfirmación como operación nueva. (b) sin revocación,
  solo versión nueva. (c) permitir revocar confirmaciones superadas.
- Recomendación: (a). Revocar exige historial íntegro y confirmación vigente
  de la última versión, pero **no** completitud ni zona reconocida, de modo que
  también se puede retirar en E11.
- Justificación: permite al usuario retractarse sin crear contenido nuevo y
  conserva toda la historia. (c) no cambia ningún estado vigente y añade ruido.
- Si no se resuelve: el usuario no puede retirar una confirmación errónea sin
  guardar una versión artificial.
- Estado: aprobada (2026-10-03) con la recomendación.

### C5. Efecto de guardar, restaurar y cambiar unidad

- Pregunta: ¿qué ocurre con la confirmación al crear versiones?
- Alternativas: (a) ninguna versión nueva hereda confirmación, ni con huella
  idéntica, ni por restauración. (b) heredar si la huella coincide con una
  versión confirmada. (c) heredar en restauraciones exactas.
- Recomendación: (a). Solo la versión que cambia desde una unidad de partida
  declarada es incompleta y no confirmable. La primera declaración de unidad y
  la restauración exacta de una versión con otra unidad conservan sus valores y
  pueden optar a una confirmación nueva si están completas (sección 5.3). Se
  mantienen sin cambios las reglas aprobadas de unidad.
- Justificación: la confirmación es un acto sobre lo que se revisó, en una
  versión concreta. Heredarla convertiría una coincidencia de huella en una
  revisión no hecha.
- Si no se resuelve: restaurar podría parecer una forma de confirmar sin revisar.
- Estado: aprobada (2026-10-03) con la recomendación.

### C6. Confirmación cuando existen cambios sin guardar en el editor

- Pregunta: ¿se puede confirmar con un editor abierto?
- Alternativas: (a) no: solo desde la vista de solo lectura, sin editor con
  cambios, con revisión construida desde la base. (b) sí, confirmando la última
  versión guardada con aviso. (c) «Guardar y confirmar» en un paso.
- Recomendación: (a), con las cinco barreras de la sección 5.4.
- Justificación: (b) y (c) facilitan confirmar algo distinto de lo que se ve.
- Si no se resuelve: riesgo de confirmar por error contenido distinto del
  visible.
- Estado: aprobada (2026-10-03) con la recomendación.

### C7. Correspondencia de estados y errores con `input.profile.*`

- Pregunta: ¿cómo se reportan los estados del perfil en el contrato general?
- Alternativas: (a) tabla de la sección 6.2 con la opción A de 6.3: sin motivo
  general para «sin confirmar» o «revocada», código de dominio conservado y
  `policy_not_approved` siempre presente. (b) añadir `unconfirmed` (y `revoked`)
  al contrato v1 con revisión de consumidores. (c) contrato v2.
- Recomendación: (a) para el incremento de implementación, y decidir entre (b) y (c) antes de
  cualquier aprobación clínica. Incluye los motivos propuestos para pendiente
  (`unknown`), fallo de lectura (`persistence_failed`), historial inválido
  (`invalid`) y esquema no soportado (`parse_failed`), que `conflicting` no se
  emita por conflictos de escritura, que el adaptador viva fuera del motor y
  sustituir el texto de Bolo por «Perfil · no se usa para calcular».
- Justificación: no se inventan códigos ni se fuerzan significados, y nada se
  conecta todavía.
- Si no se resuelve: la puerta futura no puede comunicar por qué un perfil
  completo no es candidato, y el texto actual de Bolo pasaría a ser falso.
- Estado: aprobada (2026-10-03) como solución provisional mientras
  `policy_not_approved` bloquee todo. Elegir entre (b) y (c) sigue pendiente
  antes de cualquier aprobación clínica.

### C8. Identidad, concurrencia e idempotencia de las operaciones

- Pregunta: ¿cómo se identifican y serializan confirmar y revocar?
- Alternativas: (a) `operation_id` UUID por intención, `seq` contiguo como orden,
  transacción exclusiva y comprobación estricta de `observed_event_seq`.
  (b) igual pero comprobación laxa (solo «sin confirmación vigente»). (c) sin
  identificador, idempotencia por contenido como en `save`.
- Recomendación: (a), con los escenarios de la sección 7.3. Todo reintento
  valida antes versiones y eventos, y una operación pendiente al recrear la
  pantalla se resuelve primero por su identidad (sección 9.3).
- Justificación: (b) permite confirmar sobre una revocación hecha por otra
  conexión que el usuario no ha visto. (c) no distingue un reintento de una
  reconfirmación tras revocar, que es justo el caso que no debe reactivarse.
- Si no se resuelve: no se puede garantizar que un reintento no reactive una
  confirmación revocada.
- Estado: aprobada (2026-10-03) con la recomendación.

### C9. Esquema v2, migración y recuperación

- Pregunta: ¿cómo se almacena y se migra?
- Alternativas: (a) tabla única de eventos con `CHECK` por tipo, índices y
  triggers de la sección 8.2, migración v1 → v2 transaccional con validación
  completa y sin confirmaciones automáticas, `save` validando también eventos.
  (b) dos tablas, confirmaciones y revocaciones, con un tercer objeto para el
  orden. (c) base separada para confirmaciones.
- Recomendación: (a), con verificación del esquema por definición (texto
  congelado de v1 y v2, índice automático por `PRAGMA`) al migrar y al abrir, y
  con las pruebas de 8.6 y 10, incluida la conservación y el rechazo de una copia
  corrupta.
- Justificación: una sola secuencia da orden inequívoco. (c) pierde la
  transacción común con las versiones, imprescindible para comprobar «última
  versión y su huella» de forma atómica.
- Si no se resuelve: no hay forma durable de registrar confirmaciones y la
  migración no tiene criterios de aceptación.
- Estado: aprobada (2026-10-03) con la recomendación.

## 12. Alternativas consideradas

- **Columna `confirmed` en `profile_versions`**: exige `UPDATE` sobre filas
  inmutables y pierde la historia de revocaciones. Rechazada.
- **Confirmación dentro de la huella**: cambiaría huellas existentes y mezclaría
  revisión con contenido. Rechazada.
- **Confirmación implícita al guardar**: contradice el ADR 0012 §2.4. Rechazada.
- **Confirmar por huella sin número de versión**: heredaría confirmaciones entre
  versiones. Rechazada (C5).
- **Instante como orden de eventos**: depende del reloj del dispositivo.
  Rechazada.
- **Estado de confirmación como puntero mutable** («versión confirmada actual»):
  equivale a «última escritura gana». Rechazada.
- **Usar `expired` para confirmaciones antiguas**: no hay política de vigencia.
  Rechazada.

## 13. Consecuencias

- Si se aprueba, el siguiente incremento añade completitud, eventos, migración a
  v2, UI de revisión y revocación y el cambio de texto de Bolo, con cálculo y
  tratamiento bloqueados en todos los estados.
- Local, sin red, sin permisos nuevos, sin tocar motor ni `ReadOverview`.
- Coste: una tabla, seis triggers, dos índices y validación de eventos en cada
  lectura. El volumen es pequeño.
- Rollback de la implementación: revertir el commit deja una base v2 que un build
  v1 no abre (fallo cerrado, sin pérdida). Se recupera con un build compatible.
- iOS reutilizaría la misma política común con SQLite nativo y los mismos
  objetos. Sigue sin verificación de plataforma (ADR 0004).
- Riesgo nuevo a registrar en la implementación: confirmación de datos
  interpretada como aprobación clínica. Mitigación: textos de la sección 9,
  `allowsCalculation = false` y verificación de aislamiento.

## 14. Fuera de alcance

Uso del perfil por el motor, Bolo o `ReadOverview`, límites clínicos, DIA, IOB,
resolución de franjas en cambios de hora, Dexcom, aprendizaje, propuestas
automáticas, conversión de unidades, exportación o importación de producto,
sincronización, perfiles alternativos y autoridad de tratamiento.

## 15. Condiciones para revisar este ADR

Aprobación de límites o de una política clínica, conexión de la puerta a Bolo o
al motor, cambio del contrato `unavailable-input`, perfiles alternativos,
sincronización entre dispositivos, propuestas del sistema, un segundo esquema de
contenido o un cambio del significado de confirmar.

## 16. Revisión del propietario sobre la PR #31 (2026-10-03)

El propietario revisó el borrador (commit `c5f64b8`, CI en verde) y pidió cuatro
correcciones antes de decidir. Se incorporan sin cambiar las recomendaciones
generales C1 a C9, que siguen pendientes. C7 se mantiene como solución
provisional mientras `policy_not_approved` bloquee todo.

| Punto | Corrección | Secciones | Decisiones afectadas |
|---|---|---|---|
| 1 | Un reintento valida versiones y eventos antes de devolver `Recorded`. Al recrear la pantalla, la operación pendiente se resuelve primero por su identidad, para no presentar una confirmación ya registrada como conflicto o fracaso | 7.1, 7.3, 9.3, 10 | C8 |
| 2 | La versión incompleta por construcción es solo la que cambia desde una unidad de partida declarada. Primera declaración y restauración exacta con otra unidad conservan valores y pueden optar a confirmación nueva | 5.3, 10, C5 | C5 |
| 3 | E11 permite retirar la confirmación. Revocar no exige completitud ni zona reconocida. E9 y E11 tienen confirmación vigente | 2.2, 2.3, 9.2, 10, C4 | C4 |
| 4 | La migración verifica definiciones exactas, incluye el índice automático de la clave compuesta y conserva y sigue rechazando una copia corrupta | 8.4, 8.5, 8.6, 10, C9 | C9 |

Además se corrigen dos detalles del borrador publicado: el recuento de triggers
nuevos (seis) y la puntuación de algunas listas.

Aceptación: tras esta revisión (commit `0d4a3fd`, CI en verde) el propietario
aceptó el ADR el 2026-10-03. C7 se aprueba como solución provisional: la
elección entre añadir motivos al contrato v1 o crear un contrato v2 sigue
pendiente y debe resolverse antes de cualquier aprobación clínica.
