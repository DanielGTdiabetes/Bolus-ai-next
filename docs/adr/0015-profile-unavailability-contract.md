# ADR 0015: evolución del contrato de indisponibilidad para el perfil

- Estado: **propuesto**. No implementado. Pendiente de revisión y decisión del
  propietario (sección 9).
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
data class InputUnavailability(          // contractVersion = 2
    val input: InputKind,
    val reason: UnavailabilityReasonV2,   // motivos v1 con el mismo código y significado, más unconfirmed
    val details: List<String>,            // códigos de dominio estables, ordenados y sin duplicados
)
class InputUnavailabilityReport(entries: List<InputUnavailability>) // contractVersion = 2
```

- Admisibilidad por entrada: una tabla fija qué motivos puede declarar cada
  entrada. `unconfirmed` solo para `profile`. Construir una combinación no
  admitida falla con un identificador estable.
- Detalle: códigos con el espacio de nombres de su entrada (`profile.…`),
  validados por forma, nunca texto traducido ni valores clínicos.
- Compatibilidad: v1 queda congelado y sus productores y consumidores no
  cambian. Elevar una causa v1 a v2 (mismo motivo, sin detalle) no pierde nada.
  Proyectar v2 a v1 pierde el detalle y no existe para `unconfirmed`: no se
  ofrece proyección automática.
- En contra: dos versiones conviven, un tipo y un informe más, y cada consumidor
  nuevo debe elegir v2.

## 5. Recomendación

**Opción C**, con estas precisiones:

- Motivo general nuevo solo `unconfirmed` (E7 y E10), admitido únicamente para
  `profile`. La retirada y la confirmación superada son detalle, no motivo.
- Los 13 motivos v1 conservan su código y significado en v2.
- El informe v2 agrupa por par entrada/motivo y une los detalles, ordenados y
  sin duplicados. Ordena las causas por código como v1. Lista vacía rechazada.
- El detalle del perfil son los `detailCodes` de `ProfileGateState` y el código
  de `ProfileFailure` de lectura. Las faltas concretas de completitud (franja,
  parámetro) se quedan en el dominio: el consumidor que las necesite consulta la
  puerta del perfil.
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

Columna «C recomendada»: motivos generales y, entre corchetes, detalle de
dominio. En todas las filas, `allowsCalculation = false` y
`allowsTreatment = false`. «+ política» indica `policy_not_approved` con el
detalle `profile.not_approved_for_calculation`.

| Estado | A (vigente) | B | C recomendada |
|---|---|---|---|
| E1 lectura pendiente | `unknown` + política | igual | `unknown` [`profile.read.pending`] + política |
| E2 lectura fallida | `persistence_failed` + política | igual | `persistence_failed` [`profile.storage.read_failed` o `profile.storage.corrupt`] + política |
| E3 historial inválido | `invalid` + política | igual | `invalid` [`profile.storage.invalid_record`] + política |
| E4 esquema no soportado | `parse_failed` + política | igual | `parse_failed` [`profile.storage.unsupported_schema`] + política |
| E5 sin versión | `missing` + política | igual | `missing` [`profile.history.missing`] + política |
| E6 incompleta | `incomplete` + política | igual | `incomplete` [`profile.gate.incomplete`] + `unconfirmed` [`profile.confirmation.missing`] + política |
| E7 completa sin confirmar | solo política | `unconfirmed` + política | `unconfirmed` [`profile.confirmation.missing`] + política |
| E8 anterior confirmada, última sin confirmar | como E6 o E7 | como E6 o E7 | como E6 o E7, con `profile.confirmation.superseded` añadido al detalle de `unconfirmed` |
| E9 confirmación vigente | solo política | igual | solo política |
| E10 confirmación retirada | solo política | `revoked` (o `unconfirmed`) + política | `unconfirmed` [`profile.confirmation.revoked`] + política, y `incomplete` o `invalid` si además falla la completitud |
| E11 confirmada, zona no reconocida | `invalid` + política | igual | `invalid` [`profile.gate.time_zone_unrecognized`] + política. Sin `unconfirmed`: la confirmación sigue vigente |

Notas:

- E6 en C declara también `unconfirmed`, porque la última versión no tiene
  confirmación. El ADR 0014 no emitía `confirmation.missing` para una versión
  incompleta. Ver D5.
- E1 a E4 nunca llevan `unconfirmed` ni `missing`: sin lectura demostrada no se
  evalúa la confirmación (ADR 0014, sección 2.1).
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
- admisibilidad: cada combinación no admitida falla con su identificador
  estable. En particular `glucose`, `iob` y `meal` con `unconfirmed`.
- detalle: forma válida, espacio de nombres de la entrada, sin duplicados,
  orden determinista. Detalle vacío admitido para causas elevadas desde v1.
- informe v2: vacío rechazado, unión de detalles por par entrada/motivo, orden
  independiente del productor, aislamiento de mutaciones.
- elevación v1 → v2 sin pérdida para cada motivo y entrada.
- adaptador: la matriz E1 a E11 de la sección 6 exacta, con el detalle de
  dominio conservado, sin `expired`, con `policy_not_approved` en todas las
  filas y sin `conflicting` por errores de operación.
- regresión: `UnavailableInputTest`, `UnavailableInputReportTest`,
  `GlucoseStatusPresenterTest` y las pruebas de Dexcom sin cambios.

Android y arquitectura:

- `ReadOverviewTest` con `input.profile.policy_not_approved` si se aprueba D7.
- `verify.ps1`: el motor sigue sin depender del perfil, el módulo adaptador
  depende en una sola dirección, `ReadOverview` y `ScreenRenderer` siguen sin
  referenciar el perfil.
- Swift: ampliar `UnavailableInputReportSmoke.swift` con v2, sin ejecutarlo
  hasta una autorización explícita de macOS (ADR 0004).

## 9. Decisiones del propietario

| Id | Pregunta | Recomendación | Si no se decide |
|---|---|---|---|
| D1 | ¿B o C? | C (sección 5) | sigue la opción A provisional. Ninguna aprobación clínica puede retirar `policy_not_approved` |
| D2 | ¿Motivo general para «sin confirmar»? | `unconfirmed`, solo en v2 y solo para `profile` | E7 y E10 dependen de `policy_not_approved` |
| D3 | ¿La retirada es motivo propio? | no: detalle `profile.confirmation.revoked` bajo `unconfirmed` | — |
| D4 | ¿Admisibilidad por entrada en v2? | sí, con tabla fija y fallo estable | motivos sin sentido construibles, como en B |
| D5 | ¿Una versión incompleta declara también `unconfirmed`? | sí: es verdad y evita inferir confirmación de la ausencia del motivo | E6 sin `unconfirmed`, como en el ADR 0014 |
| D6 | ¿Detalle de completitud por franja en el contrato? | no: solo códigos de dominio. Las faltas se consultan en la puerta | — |
| D7 | ¿Código estático de `ReadOverview`? | `input.profile.policy_not_approved` en la entrega de implementación | sigue `missing`, no demostrado cuando existe una versión |
| D8 | ¿Ubicación del adaptador? | módulo común nuevo dependiente del perfil y del contrato | no se puede implementar sin romper las reglas de `verify.ps1` |

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
