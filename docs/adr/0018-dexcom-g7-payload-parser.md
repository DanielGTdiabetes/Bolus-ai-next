# ADR 0018: parser estructural del payload Dexcom G7 observado

- Estado: aceptado (decisiones D1 a D4 del propietario, 2026-10-11)
- Fecha: 2026-10-11
- Fase y criterio de aceptación: Fase 5, trabajo 3 («normalizar valor, unidad,
  timestamp, tendencia, origen e identidad») en su parte estructural; no cumple
  todavía ningún criterio de aceptación de la fase
- Responsables de decisión: propietario
- Evidencia clínica requerida: no aplica (no hay regla clínica); bloquea
  cualquier uso clínico de la salida

## Contexto

El [contrato de transporte observado v1](../contracts/modified-dexcom-g7-broadcast-observed-v1.md)
describe el broadcast de la app Dexcom G7 modificada del propietario. Next ya
autentica al emisor antes de tocar el payload ([ADR 0005](0005-android-sender-evidence-adapter.md),
[ADR 0006](0006-authenticate-before-payload.md)), pero no tiene parser: la
función que se ejecuta tras autenticar no existe y `PendingDexcomSource` sigue
devolviendo `input.glucose.policy_not_approved`.

Siguen sin resolver la release reproducible del productor, el opt-in de
identidad, las anclas, la semántica de `timestamp` y `egvTxTime`, la tendencia,
la identidad de lectura, la vigencia y la persistencia. Ninguna de esas puertas
se abre aquí. Este ADR solo fija cómo se lee la forma observada, de modo que el
siguiente incremento (receiver y persistencia) no improvise el parseo.

## Decisión

Se crea el módulo KMP `shared/dexcom-g7-payload` (paquete
`org.bolusai.dexcomg7`) con:

- `DexcomG7Payload` y `PayloadValue`: copia tipada y neutral de la acción y los
  extras. Distingue `Text`, `Int32`, `Int64`, `Group`, `Null` (clave presente
  con valor nulo) y `Unsupported(typeName)`. No hay conversión entre tipos: un
  `Long` donde el contrato observa `Int` es un error de tipo. La construcción
  desde `Intent`/`Bundle` pertenece al futuro adaptador Android, dentro de la
  frontera autenticada.
- `DexcomG7PayloadParser.parse(payload, authenticatedPackage)`: función pura y
  determinista, sin reloj, almacenamiento, red ni tipos de plataforma.
  Devuelve `Parsed(ObservedDexcomG7Message)` o `Rejected(DexcomG7PayloadRejection)`.
- `ObservedDexcomG7Reading`: valor entero, `timestamp` y `egvTxTime` en bruto,
  nombre de tendencia sin interpretar y `transmitterId` sin confianza como
  identidad. Su único estado posible es
  `ObservedReadingStatus.NOT_APPROVED_FOR_USE`.
- `ObservedDexcomG7Message`: contrato `modified-dexcom-g7-broadcast-observed-v1`,
  paquete autenticado, unidad como afirmación del productor
  (`ProducerUnitClaim.MG_DL_STATED_BY_PRODUCER`), lecturas, duplicados
  colapsados y nombres de campos desconocidos.

### Decisiones del propietario

| ID | Pregunta | Decisión |
|---|---|---|
| D1 | Lote con una lectura mal formada | Se rechaza el mensaje completo con su motivo e índice. No hay lotes parciales |
| D2 | Campos no previstos por el contrato v1 | Se ignoran para el contenido y se listan por nombre, nunca por valor. No bloquean |
| D3 | Mismo `timestamp` dentro de un mensaje | Duplicado idéntico en los cinco campos: se colapsa sobre el primer índice y se cuenta. Mismo `timestamp` con otro contenido: el mensaje se rechaza como conflicto |
| D4 | Validación del valor | Solo estructura: entero de 32 bits, sin rango. El filtro `1..400` del productor no se copia |

### Reglas técnicas (no clínicas)

- Acción exacta `com.dexcom.cgm.EXTERNAL_BROADCAST`; `sensorType` exactamente
  `G7` (distingue mayúsculas).
- El extra `packageName` es una afirmación: debe ser igual al paquete que la
  autenticación probó. Nunca sustituye a la autenticación. El parser exige un
  paquete autenticado no vacío como precondición de programación.
- `glucoseValues` es un grupo no vacío con claves decimales canónicas (`0` o
  sin ceros a la izquierda, hasta nueve cifras) y contiguas desde `0`.
- Los cinco campos de lectura son obligatorios con su tipo exacto. Texto vacío o
  solo espacios en `sensorType`, `packageName`, `trendArrow` o `transmitterId`
  se rechaza (`blank_text`).
- Se conserva el orden de índices del productor. El orden temporal no se
  evalúa: su semántica sigue pendiente.
- Nombres desconocidos fuera de `[A-Za-z0-9_.]{1,64}` no se listan: se cuentan
  una vez por nombre distinto (`unreportableUnknownFieldCount`).
- `toString` de payload, lectura y mensaje nunca imprime `transmitterId` ni
  textos del productor.

Códigos de rechazo estables: `dexcom_g7.payload.wrong_action`, `missing_field`,
`null_field`, `wrong_type`, `blank_text`, `unsupported_sensor`,
`package_claim_mismatch`, `empty_batch`, `non_canonical_index`,
`non_contiguous_index` y `conflicting_duplicate` (todos con el prefijo
`dexcom_g7.payload.`). El rechazo lleva solo un nombre de campo del contrato y
un índice canónico, nunca texto del productor.

### Lo que no cambia

No hay consumidor. `android/app` no depende del módulo, no se registra receiver,
no se piden permisos, no se persiste nada y `PendingDexcomSource` sigue igual.
`LocalGlucoseSourceResult` no gana una variante disponible (R-014). El motor,
el perfil, las comidas, SQLite y el contrato de indisponibilidad no cambian.
La traducción de un rechazo a `input.glucose.parse_failed` (u otro motivo)
queda para el ADR que conecte el parser.

## Alternativas consideradas

- **Parser dentro de `android/app` sobre `Bundle`**: obliga a probar con
  Android y mezcla la lectura de extras con su significado. Se descarta.
- **Aceptar lecturas válidas de un lote parcial**: más disponibilidad, menos
  consistencia. El propietario eligió D1.
- **Rechazar campos desconocidos**: una actualización inocua del productor
  cortaría la recepción. El propietario eligió D2.
- **Copiar el rango `1..400` del productor**: sería portar un literal clínico
  observado sin aprobación. Descartado por AGENTS.md §3 y D4.

## Consecuencias

- El siguiente incremento puede leer extras dentro de
  `DexcomAuthenticatedIngress` y entregar un `DexcomG7Payload` al parser sin
  decidir reglas de forma.
- Ninguna salida del parser es glucosa utilizable. Mostrarla, persistirla o
  usarla exige su propio ADR y las puertas del contrato observado.
- Offline: sin efecto, el parser no usa red.
- iOS: no recibe este broadcast. El módulo solo declara el objetivo JVM; una
  fuente iOS necesitará su propio adaptador y contrato. No se declara soporte
  iOS.
- Rollback: borrar el módulo y su línea de `settings.gradle.kts`; nadie lo
  consume.

## Seguridad y pruebas

- G1: sin parámetros clínicos. Los literales son nombres del transporte
  observado.
- G2: la salida está marcada `NOT_APPROVED_FOR_USE` y no existe otro estado.
- G3: función pura; la prueba de determinismo y copia defensiva lo comprueba.
- `DexcomG7PayloadParserTest` (20 pruebas JVM, payloads sintéticos): forma
  observada, estado único, valores enteros extremos sin rango (D4), timestamps
  en bruto, orden de índices, acción, sensor, afirmación de paquete y
  precondición, lote vacío o mal tipado, índices no canónicos o con huecos,
  rechazo completo por una lectura (D1), cada campo ausente, nulo, mal tipado o
  vacío, lectura que no es grupo, duplicados idénticos y conflictivos (D3),
  campos desconocidos listados y contados (D2), diagnósticos sin
  `transmitterId` y códigos estables.
- `verify.ps1`: el módulo no importa motor, perfil, comidas, Android ni Java y
  ningún fichero de `android/` o `shared/` fuera del módulo lo usa.

## Condiciones para revisar este ADR

- Nueva versión del productor o del contrato observado.
- Archivado de la release reproducible y aprobación de anclas.
- Decisión sobre la semántica de `timestamp`, `egvTxTime`, tendencia o
  identidad de lectura.
- ADR que conecte el parser con el receiver, la persistencia o la UI.
