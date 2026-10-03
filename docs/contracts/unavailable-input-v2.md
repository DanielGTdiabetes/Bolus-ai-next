# Indisponibilidad de entradas, versión 2

Contrato en memoria del [ADR 0015](../adr/0015-profile-unavailability-contract.md)
(opción C, decisiones D1 a D10). Vive en `shared/bolus-engine`, junto a v1, y
es común a Android e iOS (ADR 0001). No evalúa reglas clínicas, no contiene
valores ni autoriza cálculo o tratamiento. No depende de Legacy ni del perfil.

v1 ([`unavailable-input-v1`](unavailable-input-v1.md) y
[`unavailable-input-report-v1`](unavailable-input-report-v1.md)) queda congelado:
mismos tipos, códigos, significados, productores y consumidores. No existe
proyección automática v2 → v1: perdería el detalle y no existe para
`unconfirmed`.

## Tipos

| Tipo | Papel |
|---|---|
| `UnavailabilityReasonV2` | los 13 motivos v1 con el mismo código y significado, más `unconfirmed` |
| `InputUnavailability` | una causa: entrada, motivo general y detalle de dominio (`contractVersion = 2`) |
| `InputUnavailabilityReport` | informe no vacío de causas v2 (`contractVersion = 2`) |
| `InputUnavailabilityErrors` | identificadores estables de rechazo |

`code` conserva la forma de v1, `input.<entrada>.<motivo>`. El detalle nunca
sustituye al motivo general: lo acompaña.

| Motivo nuevo | Significado que debe demostrar el productor |
|---|---|
| `unconfirmed` | la lectura y los datos están demostrados y la última versión no tiene una confirmación vigente |

La retirada de una confirmación y las confirmaciones superadas son detalle
(`profile.confirmation.revoked`, `profile.confirmation.superseded`), no motivos.

## Admisibilidad por entrada (C.1)

Tabla normativa. `PublishedContractV2Test` la lee de este documento y la compara
con el código y con la tabla independiente de las pruebas comunes.

<!-- admissibility:start -->
| Motivo v2 | `glucose` | `profile` | `iob` | `meal` |
|---|---|---|---|---|
| `missing` | admitido | admitido | admitido | admitido |
| `invalid` | admitido | admitido | admitido | admitido |
| `expired` | admitido | admitido | admitido | admitido |
| `unknown` | admitido | admitido | admitido | admitido |
| `incomplete` | admitido | admitido | admitido | admitido |
| `conflicting` | admitido | admitido | admitido | admitido |
| `permission_denied` | admitido | admitido | admitido | admitido |
| `source_unavailable` | admitido | admitido | admitido | admitido |
| `authentication_failed` | admitido | admitido | admitido | admitido |
| `clock_anomaly` | admitido | admitido | admitido | admitido |
| `parse_failed` | admitido | admitido | admitido | admitido |
| `persistence_failed` | admitido | admitido | admitido | admitido |
| `policy_not_approved` | admitido | admitido | admitido | admitido |
| `unconfirmed` | no admitido | admitido | no admitido | no admitido |
<!-- admissibility:end -->

- 53 combinaciones admitidas. Las 3 restantes (`glucose`, `iob` y `meal` con
  `unconfirmed`) se rechazan con `input_unavailability.reason_not_admitted`.
- v2 no restringe ninguna combinación que v1 permita.
- Que un motivo esté admitido no significa que un productor pueda emitirlo:
  cada productor sigue obligado a demostrar su causa.
- Añadir otro motivo o admitirlo para otra entrada exige revisar consumidores
  y actualizar esta tabla y sus pruebas.

## Elevación v1 → v2

`UnavailabilityReasonV2.from(motivo v1)`, `InputUnavailability.from(causa v1)`
e `InputUnavailabilityReport.from(informe v1)` son totales sobre las 52
combinaciones de v1: mismo motivo, mismo `code` y detalle vacío. Nunca fallan,
no pierden nada y no declaran `@Throws`.

## Detalle (C.2)

Gramática y límites. Son constantes técnicas, no clínicas (D10):

```text
detalle  := entrada ( "." segmento )+
entrada  := código de InputKind de la causa ("glucose" | "profile" | "iob" | "meal")
segmento := [a-z] [a-z0-9_]*
longitud del detalle: 1 a 128 caracteres ASCII  (MAX_DETAIL_LENGTH)
detalles por causa: 0 a 16, tras eliminar duplicados  (MAX_DETAILS)
```

Válidos: `profile.confirmation.revoked`, `profile.storage.read_failed`.
Rechazados: `Profile.x`, `profile`, `profile.café`, `profile.120`, texto
traducido y valores numéricos de franjas. `glucose.x` en una causa de
`profile` se rechaza por espacio de nombres ajeno.

## Rechazos

`IllegalArgumentException` con el identificador como mensaje. Las pruebas
comparan esta tabla con las constantes del código.

<!-- errors:start -->
| Identificador | Cuándo |
|---|---|
| `input_unavailability.reason_not_admitted` | combinación entrada/motivo fuera de la tabla de admisibilidad |
| `input_unavailability.detail_malformed` | un detalle no cumple la gramática o la longitud |
| `input_unavailability.detail_foreign_namespace` | un detalle no empieza por el código de la entrada de la causa |
| `input_unavailability.too_many_details` | más de 16 detalles distintos en una causa, también tras unir causas en el informe |
| `input_unavailability_report.empty` | informe v2 sin causas |
<!-- errors:end -->

Orden de comprobación de una causa: admisibilidad del motivo, después cada
detalle en orden normalizado (gramática y longitud, luego espacio de nombres) y
por último el número de detalles. El identificador devuelto no depende del
orden en que el productor entregue los detalles.

## Copias, igualdad y orden

- La causa copia la lista recibida **antes** de validarla, elimina detalles
  idénticos, los ordena por código y guarda solo esa copia. Mutar después la
  lista del productor no la cambia.
- `details` devuelve una copia nueva en cada acceso. Igualdad y `hashCode` se
  calculan sobre entrada, motivo y detalle normalizado. No es una `data class`.
- El informe copia la lista de causas, une los detalles de las causas con el
  mismo par entrada/motivo (unión ordenada y sin duplicados) y vuelve a aplicar
  el límite de 16. Si la unión lo supera, rechaza con
  `input_unavailability.too_many_details`: nunca recorta causas ni detalles.
- Ordena las causas por `code` como v1. El orden es técnico, no prioridad.
  `entries` devuelve una copia en cada acceso.
- Nada es nulo: entrada, motivo y cada detalle son obligatorios.

## Límite con Swift

Los constructores de `InputUnavailability` e `InputUnavailabilityReport`
declaran `@Throws(IllegalArgumentException::class)` (D9), como el informe v1.
Desde Swift se invocan con `try` y el identificador llega en
`localizedDescription`. `tests/swift/UnavailableInputReportSmoke.swift` cubre
un rechazo de causa, uno de detalle y uno de informe, y una construcción
correcta posterior en el mismo proceso. **iOS: no verificado.** La prueba no se
ejecuta sin autorización explícita de macOS (ADR 0004).

## Traductor del perfil

`ProfileUnavailability.report(ProfileGateState)` vive en el módulo común
`shared/profile-unavailability`, que depende del perfil y de este contrato,
nunca al revés (D8). Traduce un estado recibido según las secciones 6.1 y 6.2
del ADR 0015: no lee almacenamiento, no añade reglas clínicas, no declara
`expired` ni `conflicting` y añade siempre `policy_not_approved`
[`profile.not_approved_for_calculation`].

| Estado del perfil | Causas |
|---|---|
| E1 lectura pendiente | `unknown` [`profile.read.pending`] |
| E2 lectura fallida | `persistence_failed` [`profile.storage.read_failed` o `profile.storage.corrupt`] |
| E3 historial inválido | `invalid` [`profile.storage.invalid_record`] |
| E4 esquema no soportado | `parse_failed` [`profile.storage.unsupported_schema`] |
| E5 sin versión | `missing` [`profile.history.missing`] |
| Faltas distintas de la zona | `incomplete` [`profile.gate.incomplete`] |
| Zona declarada no reconocida | `invalid` [`profile.gate.time_zone_unrecognized`] |
| Sin confirmar | `unconfirmed` [`profile.confirmation.missing`] |
| Confirmación retirada | `unconfirmed` [`profile.confirmation.revoked`] |
| Confirmaciones superadas y sin confirmación vigente | añade [`profile.confirmation.superseded`] a `unconfirmed` |
| Confirmación vigente | ninguna causa de confirmación |
| Todo estado | `policy_not_approved` [`profile.not_approved_for_calculation`] |

E1 a E5 no evalúan completitud ni confirmación. En los estados evaluados las
causas de completitud y de confirmación se acumulan sin sustituirse.

`ProfileGateState.Unreadable` con un fallo que no es de lectura (por ejemplo
`profile.storage.save_failed`, `profile.storage.revision_conflict` o un error
de operación de confirmación) no es un estado de entrada (ADR 0014, sección
6.2). El traductor lo rechaza con
`profile_unavailability.unreadable_reason_not_supported` en lugar de inventar
una causa. Ningún productor actual construye ese estado.

Ningún consumidor usa el traductor todavía. Conectarlo con Bolo, el motor u
otro consumidor exige un ADR propio (ADR 0014, C7). `verify.ps1` lo comprueba.

## Verificación

- `InputUnavailabilityTest`, `InputUnavailabilityReportTest` (comunes, JVM):
  códigos, versión, 53 + 3 combinaciones, elevación de las 52 de v1, límites
  de longitud (1, 128, 129) y número (16, 17), gramática, espacio de nombres,
  duplicados, orden, igualdad, mutaciones, vacío y unión de detalles.
- `PublishedContractV2Test` (JVM): tablas de este documento frente al código.
- `ProfileUnavailabilityTest` (común, JVM): matriz exacta E1 a E11 con sus
  acumulaciones.
- Regresión v1 sin cambios: `UnavailableInputTest`,
  `UnavailableInputReportTest`, `GlucoseStatusPresenterTest`, Dexcom.

Serialización, persistencia y huellas del contrato siguen fuera de alcance.
