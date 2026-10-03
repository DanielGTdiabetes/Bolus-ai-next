# ADR 0015: evolución del contrato de indisponibilidad para el perfil

- Estado: **aceptado** para el diseño del contrato. **Implementado** en la rama
  `claude/profile-unavailability-v2` (PR en borrador, pendiente de revisión e
  integración). Ver sección 13.
- Aprobación: el propietario aceptó el ADR el 2026-10-03, tras incorporar las
  tres correcciones de su revisión (sección 12), con las decisiones D1 a D10
  aprobadas según la recomendación de cada una (sección 9). La aceptación fija
  el diseño del contrato v2. No autoriza implementarlo, conectar el perfil con
  ningún consumidor ni ninguna aprobación clínica: cada una exige una petición
  explícita del propietario. Cálculo y tratamiento siguen bloqueados.
- Fecha: 2026-10-03.
- Fase y criterio de aceptación: fase 6 (perfil) y contratos de la fase 2.
  Decide cómo comunicar con `input.profile.*` los estados E1 a E11 del
  [ADR 0014](0014-clinical-profile-confirmation-eligibility.md) antes de
  conectar el perfil con cualquier consumidor. Es la elección entre las
  opciones B y C de su sección 6.3, que ese ADR deja pendiente antes de
  cualquier aprobación clínica.
- Responsables de decisión: propietario del proyecto.
- Evidencia clínica requerida: no aplica. No se introduce ningún límite, regla,
  vigencia, valor por defecto ni conversión.
- Base Next: `c0191484f3d746ee724a3d03d2e9c888afa94f02` (PR #34 integrada, ADR
  0014 cerrado). CI del SHA exacto:
  [Verify 37132300116](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37132300116),
  evento `push`, conclusión `success`.
- Legacy: no se consulta. El contrato es propio de Next.
- Relacionados: [`unavailable-input-v1`](../contracts/unavailable-input-v1.md),
  [`unavailable-input-report-v1`](../contracts/unavailable-input-report-v1.md),
  ADR 0001 (núcleo KMP), ADR 0004 (iOS sin verificación automática), ADR 0012 y
  ADR 0014.

Invariantes que este ADR no modifica en ninguna opción:

- `allowsCalculation = false` y `allowsTreatment = false` en todo estado del
  perfil, con el bloqueo `profile.not_approved_for_calculation`.
- El motor, Bolo y `ReadOverview` no leen el perfil, y `verify.ps1` lo comprueba.
  Conectar la puerta a un consumidor exige un ADR propio (ADR 0014, C7).
- `input.profile.*` comunica causas ya determinadas. No acredita elegibilidad.
- No se declara `expired`: no existe política de vigencia aprobada.
- Un conflicto de escritura (`revision_conflict`, `state_changed`) es un error
  de operación y nunca produce `conflicting`.

## 1. Problema

El contrato v1 tiene 13 motivos generales compartidos por todas las entradas.
Ninguno expresa «datos completos sin confirmar» (E7) ni «confirmación retirada»
(E10). Forzarlos en `missing`, `incomplete`, `invalid` o `expired` cambiaría su
significado. La opción A del ADR 0014 (solo `policy_not_approved`, con el
detalle en el dominio) es válida mientras esa política bloquee todo, pero el
detalle no viaja en el contrato: un consumidor solo ve
`input.profile.policy_not_approved` y no sabe qué acción falta.

Además, el estado actual tiene una inexactitud aceptada como provisional (ADR
0014, sección 1.4): `ReadOverview` emite siempre `input.profile.missing` sin
leer el perfil, y Bolo lo muestra en «Detalles técnicos». El contrato v1 exige
que el productor demuestre el motivo. Con una versión guardada, `missing` no se
ha demostrado y es falso.

## 2. Evidencia: contrato, productores, consumidores y pruebas actuales

| Elemento | Ubicación | Qué implica para la decisión |
|---|---|---|
| `InputKind`, `UnavailabilityReason`, `UnavailableInput` (`contractVersion = 1`, `code = input.<entrada>.<motivo>`) | `shared/bolus-engine/.../UnavailableInput.kt` | Un único enum de motivos para las cuatro entradas. Todo motivo nuevo es construible para glucosa, IOB y comida |
| `UnavailableInputReport` v1 (copia, deduplicación exacta, orden lexicográfico, vacío rechazado) | `shared/bolus-engine/.../UnavailableInputReport.kt` | Solo transporta pares entrada/motivo. No hay sitio para el detalle de dominio |
| Contratos publicados | `docs/contracts/unavailable-input-v1.md`, `unavailable-input-report-v1.md` | «Añadir motivos exige revisar consumidores; renombrar o cambiar su significado exige nueva versión» |
| Productor `ReadOverview` | `android/.../application/ReadOverview.kt` | Perfil `missing` estático, IOB `unknown`, comida `missing`. `verify.ps1` le prohíbe referenciar el perfil |
| Productores de glucosa | `LocalGlucoseSourcePort.kt`, `DexcomSenderAuthenticator.kt` | Emiten `policy_not_approved` y `authentication_failed` |
| Consumidor `GlucoseStatusPresenter` | `android/.../GlucoseStatusPresenter.kt` | `when` exhaustivo sobre los 13 motivos con una clave de mensaje por motivo |
| Consumidor Bolo | `ScreenRenderer.details` | Muestra los cuatro `code` como texto técnico. `verify.ps1` le prohíbe referenciar el perfil |
| `UnavailableInputTest` | `shared/bolus-engine` (común) | Fija la lista exacta de 13 códigos y la versión 1 |
| `UnavailableInputReportTest` | `shared/bolus-engine` (común) | Ordena todas las combinaciones entrada × motivo |
| `GlucoseStatusPresenterTest` | Android JVM | Exige una clave de mensaje distinta por cada motivo del enum |
| `ReadOverviewTest` | Android JVM | Fija `input.profile.missing` y recorre todos los motivos para glucosa |
| `UnavailableInputReportSmoke.swift` | `tests/swift` | Usa los nombres exportados del binding. Sin ejecución automática (ADR 0004) |
| Restricción de arquitectura | `verify.ps1` | `shared/bolus-engine` no puede depender de `clinical-profile` ni de `org.bolusai.profile` |
| Detalle de dominio | `ProfileGateState.detailCodes`, `ProfileGateCodes`, `ProfileFailure` | Códigos estables ya implementados y probados en `shared/clinical-profile` |

Consecuencias de la evidencia:

- Con la opción B, `GlucoseStatusPresenter` deja de compilar hasta mapear
  motivos que la glucosa no puede tener, y `GlucoseStatusPresenterTest`
  obligaría a inventar mensajes de glucosa para ellos. `ReadOverviewTest`
  aceptaría `input.glucose.unconfirmed` como causa preservada.
- El detalle de dominio tiene que viajar como **códigos de texto**, no como
  tipos del perfil: el motor no puede importar `clinical-profile`.
- El adaptador `ProfileGateState` → contrato no puede vivir en el motor ni en
  `ReadOverview`.

## 3. Requisitos

1. Ningún código publicado cambia de significado.
2. El detalle de dominio se conserva junto al motivo general, nunca lo sustituye.
3. Varias causas conviven sin prioridad. El orden es técnico y determinista.
4. Lectura pendiente, fallida o inválida nunca se presenta como ausencia ni como
   «sin confirmar».
5. `policy_not_approved` acompaña a todo estado del perfil mientras no exista
   aprobación clínica.
6. El contrato es común (KMP) y reutilizable en iOS. Sin red ni persistencia.
7. Los consumidores actuales de glucosa, IOB y comida no cambian de
   comportamiento.

## 4. Opciones

### A. Mantener v1 sin motivo específico (aprobada como provisional)

Ya vigente por el ADR 0014. Deja de bastar cuando una aprobación clínica retire
`policy_not_approved` de algún estado: E7 y E10 quedarían sin motivo general.
Se incluye como referencia, no como opción final.

### B. Añadir motivos a `UnavailabilityReason` v1

`unconfirmed` (y opcionalmente `revoked`) como adición del contrato v1.

- A favor: cambio pequeño, un solo tipo, el informe v1 ordena y deduplica sin
  cambios, la versión sigue en 1 (el contrato permite añadir motivos).
- En contra:
  - el enum es común a las cuatro entradas: `input.glucose.unconfirmed` o
    `input.iob.revoked` pasan a ser construibles y no significan nada.
  - rompe el `when` exhaustivo de glucosa y su prueba de una clave por motivo
    (sección 2). Habría que inventar mensajes o debilitar la prueba.
  - el detalle de dominio sigue sin viajar: superada, zona no reconocida o la
    causa exacta de un fallo de lectura se pierden en el contrato.
  - `revoked` es un hecho del historial del perfil, no una causa general:
    E10 es «la última versión no tiene confirmación vigente», igual que E7.
  - en el binding Swift, un caso nuevo del enum no está verificado contra los
    consumidores Swift (ADR 0004): no verificado.

### C. Contrato v2 con detalle de dominio estructurado

Tipo nuevo, versión 2, que conserva v1 intacto:

```kotlin
// Ilustrativo. Vive en el módulo del contrato, sin depender del perfil.
class InputUnavailability @Throws(IllegalArgumentException::class) constructor(  // contractVersion = 2
    input: InputKind,
    reason: UnavailabilityReasonV2,   // los 13 motivos v1 con el mismo código y significado, más unconfirmed
    details: List<String>,            // códigos de dominio estables
)
class InputUnavailabilityReport @Throws(IllegalArgumentException::class) constructor(
    entries: List<InputUnavailability>,
) // contractVersion = 2
```

#### C.1 Admisibilidad por entrada

| Motivo v2 | `glucose` | `profile` | `iob` | `meal` |
|---|---|---|---|---|
| `missing`, `invalid`, `expired`, `unknown`, `incomplete`, `conflicting`, `permission_denied`, `source_unavailable`, `authentication_failed`, `clock_anomaly`, `parse_failed`, `persistence_failed`, `policy_not_approved` (los 13 de v1) | admitido | admitido | admitido | admitido |
| `unconfirmed` | no admitido | admitido | no admitido | no admitido |

- Los 13 motivos de v1 siguen admitidos para las cuatro entradas, exactamente
  como en v1 (52 combinaciones). v2 **no** restringe ninguna combinación que v1
  permita: restringirla cambiaría su significado y haría parcial la elevación.
- La única restricción nueva es `unconfirmed`, admitido solo para `profile`.
  En total, 53 combinaciones admitidas.
- Elevación v1 → v2: función total sobre las 52 combinaciones de v1, con el
  mismo motivo, el mismo `code` y detalle vacío. Nunca falla y no pierde nada.
- Que un motivo esté admitido no significa que un productor concreto pueda
  emitirlo: cada productor sigue obligado a demostrar su causa (contrato v1).
- Añadir otro motivo v2 o admitirlo para otra entrada exige revisar
  consumidores y actualizar esta tabla y sus pruebas.

#### C.2 Garantías de construcción

Gramática del detalle (constantes técnicas, no clínicas):

```text
detalle  := entrada ( "." segmento )+
entrada  := código de InputKind de la causa ("glucose" | "profile" | "iob" | "meal")
segmento := [a-z] [a-z0-9_]*
longitud del detalle: 1 a 128 caracteres ASCII
detalles por causa: 0 a 16, después de eliminar duplicados
```

Ejemplos válidos: `profile.confirmation.revoked`,
`profile.storage.read_failed`. Rechazados: `Profile.x` (mayúscula),
`profile` (sin segmento), `glucose.x` en una causa de `profile` (espacio de
nombres ajeno), texto traducido o valores numéricos de franjas.

Identificadores estables de rechazo (mensaje de `IllegalArgumentException`):

| Identificador | Cuándo |
|---|---|
| `input_unavailability.reason_not_admitted` | combinación entrada/motivo fuera de C.1 |
| `input_unavailability.detail_malformed` | un detalle no cumple la gramática o la longitud |
| `input_unavailability.detail_foreign_namespace` | un detalle no empieza por el código de la entrada de la causa |
| `input_unavailability.too_many_details` | más de 16 detalles distintos |
| `input_unavailability_report.empty` | informe v2 sin causas |

Copias y normalización:

- Cada causa copia la lista recibida **antes** de validarla, elimina detalles
  idénticos y los ordena por código. Guarda solo esa copia. Mutar después la
  lista del productor no cambia la causa.
- `details` devuelve una copia nueva en cada acceso. Igualdad y `hashCode` se
  calculan sobre motivo, entrada y la copia normalizada. No es una `data class`
  con una lista expuesta, para no compartir la referencia mutable.
- El informe v2 copia la lista de causas, une los detalles de las causas con
  el mismo par entrada/motivo (unión ordenada y sin duplicados, que vuelve a
  pasar el límite de 16), ordena las causas por `code` como v1 y devuelve una
  copia en cada acceso a `entries`.
- Nada es nulo: entrada, motivo y cada detalle son obligatorios.

Límite con Swift:

- Los constructores que validan declaran `@Throws(IllegalArgumentException::class)`,
  como `UnavailableInputReport` v1. Sin esa declaración, Kotlin/Native termina el
  proceso al cruzar el límite Objective-C (contrato del informe v1). Desde
  Swift se invocan con `try` y el identificador estable llega en
  `localizedDescription`.
- Alternativa (D9): una fábrica que devuelve un resultado explícito
  (`Built`/`Rejected(identificador)`) sin excepciones. Más verbosa en Kotlin y
  sin precedente en el proyecto.
- La elevación v1 → v2 no lanza y no necesita `@Throws`.
- Comportamiento en iOS: no verificado. La prueba Swift queda prevista y su
  ejecución requiere autorización explícita de macOS (ADR 0004).

#### C.3 Compatibilidad y coste

- v1 queda congelado y sus productores y consumidores no cambian.
- Proyectar v2 a v1 pierde el detalle y no existe para `unconfirmed`: no se
  ofrece proyección automática.
- En contra: dos versiones conviven, un tipo y un informe más, y cada consumidor
  nuevo debe elegir v2.

## 5. Recomendación

**Opción C**, con estas precisiones:

- Motivo general nuevo solo `unconfirmed`, admitido únicamente para `profile`
  (tabla C.1). La retirada y la confirmación superada son detalle, no motivo.
- Los 13 motivos v1 conservan su código, su significado y su admisibilidad para
  las cuatro entradas.
- Construcción con las garantías de C.2: gramática del detalle, identificadores
  estables de rechazo, copias defensivas y `@Throws` hacia Swift.
- El informe v2 agrupa por par entrada/motivo y une los detalles, ordenados y
  sin duplicados. Ordena las causas por código como v1. Lista vacía rechazada.
- El adaptador deriva cada causa de las dimensiones de `ProfileGateState`
  (lectura, completitud y confirmación) con las reglas de la sección 6, no
  solo de `detailCodes`, que omite `confirmation.missing` cuando la versión es
  incompleta. Los códigos de detalle son los ya publicados en
  `ProfileGateCodes` y `ProfileFailure`. Las faltas concretas de completitud
  (franja, parámetro) se quedan en el dominio: el consumidor que las necesite
  consulta la puerta del perfil.
- El adaptador `ProfileGateState` → v2 vive en un módulo común nuevo que
  depende del perfil y del contrato, nunca al revés. Motor, Bolo y
  `ReadOverview` siguen sin depender del perfil.
- En la misma entrega de implementación, `ReadOverview` sustituye el
  `input.profile.missing` estático por `input.profile.policy_not_approved`
  estático: es verdad sin leer el perfil y el productor lo puede demostrar
  (sección 1).

Justificación: B obliga a que la glucosa, el IOB y la comida acepten motivos que
no pueden tener, rompe su presentador y aun así pierde el detalle. C cumple los
siete requisitos sin tocar un solo código publicado y deja el detalle disponible
para la futura puerta clínica.

## 6. Correspondencias E1 a E11

### 6.1 Reglas de composición (opción C)

Las causas se acumulan por dimensión y nunca se sustituyen entre sí. «+
política» indica siempre `policy_not_approved`
[`profile.not_approved_for_calculation`]. En todos los casos,
`allowsCalculation = false` y `allowsTreatment = false`.

| Dimensión (ADR 0014 §2.1) | Condición demostrada | Causa aportada |
|---|---|---|
| Lectura | pendiente | `unknown` [`profile.read.pending`]. No se evalúa nada más |
| Lectura | fallida | `persistence_failed` [`profile.storage.read_failed` o `profile.storage.corrupt`]. No se evalúa nada más |
| Lectura | historial inválido | `invalid` [`profile.storage.invalid_record`]. No se evalúa nada más |
| Lectura | esquema no soportado | `parse_failed` [`profile.storage.unsupported_schema`]. No se evalúa nada más |
| Lectura | ausencia demostrada | `missing` [`profile.history.missing`]. No hay completitud ni confirmación que evaluar |
| Completitud | alguna falta distinta de la zona: unidad o zona sin declarar, o franja sin valor | `incomplete` [`profile.gate.incomplete`] |
| Completitud | zona declarada que la plataforma no reconoce | `invalid` [`profile.gate.time_zone_unrecognized`] |
| Confirmación | sin confirmar | `unconfirmed` [`profile.confirmation.missing`] |
| Confirmación | retirada | `unconfirmed` [`profile.confirmation.revoked`] |
| Confirmación | vigente | ninguna causa de confirmación |
| Confirmación | hay confirmaciones superadas de versiones anteriores y la última no tiene confirmación vigente | añade [`profile.confirmation.superseded`] al detalle de `unconfirmed` |
| Elegibilidad | siempre en este incremento | + política |

Precisiones:

- Las dos filas de completitud son independientes: si faltan valores y además
  la zona no se reconoce, se emiten `incomplete` **e** `invalid`, cada una con
  su detalle. Esto puede ocurrir con o sin confirmación vigente.
- `invalid` por zona no reconocida y `invalid` por historial inválido no
  coinciden nunca: el segundo impide evaluar la completitud.
- `profile.confirmation.superseded` se emite también con una confirmación
  retirada. El dominio solo lo incluye en `detailCodes` para «sin confirmar»;
  el adaptador lo deriva de `ProfileGateState.superseded`. Ver D5.
- Una versión confirmada fue completa al confirmarse y su contenido es
  inmutable. Después solo puede fallar la zona, por la plataforma. La regla
  cubre igualmente cualquier combinación.

### 6.2 Matriz resultante

| Estado | A (vigente) | B | C recomendada |
|---|---|---|---|
| E1 lectura pendiente | `unknown` + política | igual | `unknown` [`profile.read.pending`] + política |
| E2 lectura fallida | `persistence_failed` + política | igual | `persistence_failed` [`profile.storage.read_failed` o `profile.storage.corrupt`] + política |
| E3 historial inválido | `invalid` + política | igual | `invalid` [`profile.storage.invalid_record`] + política |
| E4 esquema no soportado | `parse_failed` + política | igual | `parse_failed` [`profile.storage.unsupported_schema`] + política |
| E5 sin versión | `missing` + política | igual | `missing` [`profile.history.missing`] + política |
| E6 incompleta, solo faltas distintas de la zona | `incomplete` + política | igual | `incomplete` [`profile.gate.incomplete`] + `unconfirmed` [`profile.confirmation.missing`] + política |
| E6 incompleta, solo zona no reconocida | `invalid` + política | igual | `invalid` [`profile.gate.time_zone_unrecognized`] + `unconfirmed` [`profile.confirmation.missing`] + política |
| E6 incompleta, faltas y zona no reconocida | `incomplete` + `invalid` + política | igual | `incomplete` [`profile.gate.incomplete`] + `invalid` [`profile.gate.time_zone_unrecognized`] + `unconfirmed` [`profile.confirmation.missing`] + política |
| E7 completa sin confirmar | solo política | `unconfirmed` + política | `unconfirmed` [`profile.confirmation.missing`] + política |
| E8 anterior confirmada, última sin confirmar | como E6 o E7 | como E6 o E7 | la fila de E6 o E7 que corresponda, con `unconfirmed` [`profile.confirmation.missing`, `profile.confirmation.superseded`] |
| E9 confirmación vigente | solo política | igual | solo política |
| E10 confirmación retirada, completa | solo política | `revoked` (o `unconfirmed`) + política | `unconfirmed` [`profile.confirmation.revoked`] + política. Con confirmaciones superadas de versiones anteriores, `unconfirmed` [`profile.confirmation.revoked`, `profile.confirmation.superseded`] (6.1) |
| E10 confirmación retirada, con faltas | política más las causas de completitud | `revoked` y las de completitud + política | las causas de completitud que se demuestren (`incomplete` [`profile.gate.incomplete`], `invalid` [`profile.gate.time_zone_unrecognized`] o ambas) + `unconfirmed` [`profile.confirmation.revoked`] + política. Con superadas, también [`profile.confirmation.superseded`] |
| E11 confirmada, zona no reconocida | `invalid` + política | igual | `invalid` [`profile.gate.time_zone_unrecognized`] + política. Sin `unconfirmed`: la confirmación sigue vigente |

Notas:

- E6 en C declara también `unconfirmed`, porque la lectura y la ausencia de
  confirmación están demostradas. El ADR 0014 no emitía `confirmation.missing`
  para una versión incompleta. Ver D5.
- E1 a E5 nunca llevan `unconfirmed`, `incomplete` ni causas de zona: sin
  lectura demostrada, o sin versión, no se evalúan completitud ni confirmación
  (ADR 0014, sección 2.1).
- La columna A aplica la regla del ADR 0014 §6.1: varias causas se conservan
  juntas en el informe, sin prioridad, también para la completitud.
- Un error de operación al confirmar o retirar no es un estado de entrada y no
  produce ninguna causa.

## 7. Versionado y compatibilidad

| Elemento | Cambio |
|---|---|
| `UnavailableInput` v1, `UnavailableInputReport` v1 y sus contratos | ninguno. Quedan congelados |
| `contract: unavailable-input-v2` (nuevo documento) | motivos, admisibilidad por entrada, forma del detalle, informe v2 |
| `GlucoseStatusPresenter`, Dexcom, `LocalGlucoseSourcePort` | ninguno |
| `ReadOverview` | solo el código estático del perfil (`missing` → `policy_not_approved`), si se aprueba D7 |
| Binding Swift | tipos nuevos aditivos. Verificación iOS pendiente de autorización (ADR 0004) |
| Serialización, persistencia, huella | ninguna. El contrato sigue siendo en memoria |

Rollback: revertir la implementación elimina tipos aditivos sin efecto en datos.

## 8. Pruebas previstas (para la implementación, no en esta entrega)

Comunes (`shared`, JVM):

- v2: códigos `input.<entrada>.<motivo>` iguales a v1 para los 13 motivos, más
  `input.profile.unconfirmed`. `contractVersion = 2`.
- admisibilidad (C.1): las 53 combinaciones admitidas se construyen. Las 3
  restantes (`glucose`, `iob` y `meal` con `unconfirmed`) fallan con
  `input_unavailability.reason_not_admitted`. La tabla de la prueba se compara
  con la del contrato publicado.
- detalle (C.2): casos válidos e inválidos de la gramática en los límites (1 y
  128 caracteres, 16 y 17 detalles, mayúsculas, segmento vacío, carácter no
  ASCII, espacio de nombres ajeno), cada uno con su identificador estable.
  Duplicados eliminados y orden determinista.
- copias defensivas: mutar la lista del productor después de construir una
  causa o un informe no los cambia. Mutar la lista devuelta por `details` o
  `entries` tampoco. Igualdad por contenido normalizado.
- informe v2: vacío rechazado con `input_unavailability_report.empty`, unión de
  detalles por par entrada/motivo con el límite de 16 aplicado tras la unión,
  orden independiente del productor.
- elevación v1 → v2: las 52 combinaciones de v1 se elevan sin fallar, con el
  mismo `code` y detalle vacío.
- adaptador: las reglas de 6.1 y la matriz de 6.2 exactas, incluidas las
  acumulaciones (faltas y zona juntas, con y sin confirmación vigente, con
  retirada y con superadas), con el detalle de dominio conservado, sin
  `expired`, con `policy_not_approved` en todas las filas y sin `conflicting`
  por errores de operación.
- regresión: `UnavailableInputTest`, `UnavailableInputReportTest`,
  `GlucoseStatusPresenterTest` y las pruebas de Dexcom sin cambios.

Android y arquitectura:

- `ReadOverviewTest` con `input.profile.policy_not_approved` si se aprueba D7.
- `verify.ps1`: el motor sigue sin depender del perfil, el módulo adaptador
  depende en una sola dirección, `ReadOverview` y `ScreenRenderer` siguen sin
  referenciar el perfil.
- Swift: ampliar `UnavailableInputReportSmoke.swift` con v2: un rechazo de
  causa y uno de informe capturados con `try`, con su identificador en
  `localizedDescription`, y una construcción correcta posterior en el mismo
  proceso. Prevista, sin ejecutar hasta una autorización explícita de macOS
  (ADR 0004). Hasta entonces el comportamiento en iOS figura como no
  verificado.

## 9. Decisiones del propietario

Estado: D1 a D10 **aprobadas** el 2026-10-03 con la recomendación de cada una.
La columna «Si no se decide» se conserva como justificación histórica.

| Id | Pregunta | Recomendación | Si no se decide |
|---|---|---|---|
| D1 | ¿B o C? | C (sección 5) | sigue la opción A provisional. Ninguna aprobación clínica puede retirar `policy_not_approved` |
| D2 | ¿Motivo general para «sin confirmar»? | `unconfirmed`, solo en v2 y solo para `profile` | E7 y E10 dependen de `policy_not_approved` |
| D3 | ¿La retirada es motivo propio? | no: detalle `profile.confirmation.revoked` bajo `unconfirmed` | — |
| D4 | ¿Admisibilidad por entrada en v2? | sí, tabla C.1: los 13 motivos v1 para las cuatro entradas y `unconfirmed` solo para `profile` | motivos sin sentido construibles, como en B |
| D5 | ¿Cómo deriva el adaptador las causas de confirmación? | por dimensiones (6.1): `unconfirmed` también en una versión incompleta, y `superseded` también con una retirada | E6 sin `unconfirmed` y `superseded` solo como en `detailCodes`, como en el ADR 0014 |
| D6 | ¿Detalle de completitud por franja en el contrato? | no: solo códigos de dominio. Las faltas se consultan en la puerta | — |
| D7 | ¿Código estático de `ReadOverview`? | `input.profile.policy_not_approved` en la entrega de implementación | sigue `missing`, no demostrado cuando existe una versión |
| D8 | ¿Ubicación del adaptador? | módulo común nuevo dependiente del perfil y del contrato | no se puede implementar sin romper las reglas de `verify.ps1` |
| D9 | ¿Cómo se rechaza una construcción inválida hacia Swift? | `@Throws(IllegalArgumentException::class)` con identificador estable, como el informe v1 | sin decisión no se implementa: omitirlo puede terminar el proceso en iOS |
| D10 | ¿Límites técnicos del detalle? | 128 caracteres y 16 detalles por causa (C.2), constantes técnicas no clínicas | sin límites, un productor defectuoso podría crecer sin cota |

Ninguna decisión conecta el perfil con Bolo, `ReadOverview` (salvo el texto
estático de D7) o el motor, ni aprueba límites, vigencia, DIA, IOB, resolución
de franjas en cambios de hora (P4 del ADR 0012) o exportación/importación de
copias (P6 del ADR 0012).

## 10. Consecuencias

- Con C, el contrato puede expresar cada estado del perfil sin cambiar ningún
  código existente, y la futura puerta clínica dispone del detalle.
- Coste: un tipo, un informe, un documento de contrato y un módulo adaptador,
  con sus pruebas. Sin migración de datos.
- Riesgo: dos versiones del contrato. Mitigación: v1 congelado, sin proyección
  automática v2 → v1 y pruebas de regresión de v1 sin cambios.
- Cálculo y tratamiento siguen bloqueados en todos los estados.

## 11. Condiciones para revisar este ADR

Una aprobación clínica del perfil, la conexión de la puerta con Bolo, el motor
u otro consumidor, una segunda entrada que necesite un motivo propio, la
serialización o persistencia del contrato, o la verificación de iOS que muestre
un problema del binding.

## 12. Revisión del propietario sobre la PR #35 (2026-10-03)

El propietario revisó el borrador `5582f41` (CI en verde, `verify.ps1`
correcto) y pidió tres correcciones antes de decidir. Se incorporan sin cambiar
la recomendación general (opción C). Confirmó como correctos D7 y que una
versión incompleta declare `unconfirmed` cuando la lectura y la ausencia de
confirmación están demostradas.

| Punto | Corrección | Secciones | Decisiones afectadas |
|---|---|---|---|
| 1 | La matriz perdía causas de completitud: zona no reconocida sin confirmación vigente, y faltas y zona a la vez. Ahora las causas se componen por dimensión, `incomplete` e `invalid` se conservan juntas con sus detalles, junto a `unconfirmed` y la política, y E10 precisa la acumulación | 6.1, 6.2, 8 | D5 |
| 2 | Faltaba la tabla de admisibilidad. Ahora enumera las 53 combinaciones: los 13 motivos v1 para las cuatro entradas, sin restricción nueva, y `unconfirmed` solo para `profile`. La elevación v1 → v2 es total sobre las 52 combinaciones de v1 | C.1, 5, 8 | D4 |
| 3 | Garantías de construcción: gramática y límites del detalle, identificadores estables de rechazo, copias defensivas en causa e informe y `@Throws` hacia Swift (o un resultado explícito), con sus pruebas previstas e iOS sin verificar | C.2, 5, 8 | D9, D10 |

Aceptación: tras esta revisión (commit `220e138`, CI en verde) el propietario
aceptó el ADR el 2026-10-03 con D1 a D10 según su recomendación. Antes de
cerrar pidió explicitar en la fila «E10 confirmación retirada, completa» el
detalle `profile.confirmation.superseded` cuando corresponda, como ya fijaba
6.1. La aceptación no autoriza la implementación ni ninguna aprobación clínica.

## 13. Estado de implementación (2026-10-03)

El propietario autorizó el 2026-10-03 implementar este ADR con D1 a D10
aprobadas, desde `b3cf7e6c869b2fe9aa7ed9a873a5323705145ec9` (PR #35 integrada,
[Verify 37139124635](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37139124635),
evento `push`, conclusión `success`). Alcance autorizado: contrato v2, módulo
traductor, D7, documentación y pruebas. Sin integrar: la PR queda en borrador
para revisión.

| Elemento | Implementación |
|---|---|
| Contrato v2 (C.1, C.2, D2 a D4, D9, D10) | `shared/bolus-engine/.../InputUnavailability.kt`: `UnavailabilityReasonV2`, `InputUnavailability`, `InputUnavailabilityReport`, `InputUnavailabilityErrors`. v1 sin cambios |
| Documento del contrato | [`unavailable-input-v2`](../contracts/unavailable-input-v2.md), con las tablas de admisibilidad y de rechazos comprobadas por `PublishedContractV2Test` |
| Traductor (D5, D6, D8) | módulo común nuevo `shared/profile-unavailability`, `ProfileUnavailability.report(ProfileGateState)`. Depende del perfil y del contrato. Ni el motor ni el perfil dependen de él, ni Android lo usa |
| D7 | `ReadOverview` emite `input.profile.policy_not_approved` estático. Sigue sin leer el perfil |
| `verify.ps1` | el perfil no depende del contrato ni del traductor, ningún módulo de Android o de comidas usa el traductor, `ReadOverview` y `ScreenRenderer` no usan tipos v2 |
| Swift | `UnavailableInputReportSmoke.swift` ampliado. **No ejecutado: iOS no verificado** (ADR 0004) |

Decisión de implementación sin regla en este ADR, para revisión del
propietario: `ProfileGateState.Unreadable` puede construirse con cualquier
`ProfileFailure`, pero solo `read_failed`, `corrupt`, `invalid_record` y
`unsupported_schema` son estados de entrada (E2 a E4). Para cualquier otro
fallo (errores de guardado, conflicto o de operación de confirmación) el
traductor rechaza con `IllegalArgumentException`
`profile_unavailability.unreadable_reason_not_supported` en vez de inventar una
causa o emitir `conflicting` (ADR 0014, sección 6.2). Ningún productor actual
construye ese estado, y `report` declara `@Throws`.

Evidencia: [validación](../validation/profile-unavailability-v2-2026-10-03.md).
Nada de esta implementación conecta el perfil con un consumidor ni aprueba uso
clínico. Cálculo y tratamiento siguen bloqueados en todos los estados.
