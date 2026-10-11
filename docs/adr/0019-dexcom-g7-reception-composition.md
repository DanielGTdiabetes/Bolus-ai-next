# ADR 0019: composición de recepción Dexcom G7 sin receiver

- Estado: aceptado. El propietario autorizó el 2026-10-11 redactarlo e
  implementarlo («redacta el ADR y sigue»). Las reglas T1 a T6 son decisiones
  técnicas propuestas por el agente, sin contenido clínico, y quedan abiertas a
  revisión del propietario en la PR
- Fecha: 2026-10-11
- Fase y criterio de aceptación: Fase 5, trabajo 2 («adaptador de plataforma
  separado del dominio») y trabajo 4 en la parte de estados explícitos; no
  cumple todavía ningún criterio de aceptación de la fase
- Responsables de decisión: propietario
- Evidencia clínica requerida: no aplica; ninguna salida hace disponible la
  glucosa

## Contexto

El [ADR 0018](0018-dexcom-g7-payload-parser.md) creó un parser puro del
[transporte observado](../contracts/modified-dexcom-g7-broadcast-observed-v1.md)
sin consumidor. La autenticación del emisor existe desde los
[ADR 0005](0005-android-sender-evidence-adapter.md) y
[ADR 0006](0006-authenticate-before-payload.md). Falta el código que, dentro de
`onReceive`, lea los extras solo después de autenticar, se los entregue al
parser y traduzca el resultado al contrato de indisponibilidad. Sin esa pieza,
el futuro receiver tendría que improvisar tipos y motivos.

Siguen bloqueando una lectura disponible la release reproducible del productor,
su opt-in de identidad, las anclas aprobadas, la semántica de timestamps,
tendencia e identidad, la vigencia y la persistencia. Ninguna se resuelve aquí.
Rectificación del propietario, 2026-10-11: release reproducible y opt-in ya no
se piden al productor, que no se modifica. La política compatible se propone en
el [ADR 0023](0023-dexcom-existing-transport-trust.md), todavía propuesto;
hasta aceptarlo la lectura sigue bloqueada.

## Decisión

En `android/app`, paquete `org.bolusai.next.glucose.dexcom`:

- `ExtrasView` y `DexcomPayloadExtras.read(action, extras)`: copia la acción y
  los extras a `DexcomG7Payload` (T1, T2, T3).
- `BundleExtrasView` e `Intent.readDexcomPayload()`: vista Android de un
  `Bundle`, con `Bundle` anidados como vistas anidadas.
- `DexcomG7Reception(policy).receive(source, readPayload)`: autentica con
  `DexcomAuthenticatedIngress`, solo entonces ejecuta `readPayload` una vez y
  pasa el paquete autenticado al parser (T5).
- `DexcomG7ReceptionOutcome`: `SenderRejected`, `PayloadUnreadable`,
  `PayloadRejected` u `Observed`, cada uno con su `UnavailableInput` de glucosa
  (T4).

`android/app` pasa a depender de `:shared:dexcom-g7-payload`.

### Reglas técnicas

| ID | Regla |
|---|---|
| T1 | Los extras solo se leen dentro de la continuación autenticada, de forma síncrona en `onReceive`. Crear la función de lectura no lee nada. Cada mensaje se autentica de nuevo |
| T2 | Sin conversión de tipos: `String` → `Text`, `Int` → `Int32`, `Long` → `Int64`, `Bundle` → `Group`, `null` → `Null`. `Short`, `Byte`, `Double`, `Boolean`, arrays y cualquier otro tipo → `Unsupported` con el nombre técnico de su clase. El parser decide si eso es un error |
| T3 | Si Android no puede deserializar algún extra (`BadParcelableException`), el mensaje entero es ilegible (`PayloadUnreadable`), aunque el extra fuera desconocido. Es coherente con D1 del ADR 0018 y falla cerrado. Cualquier otra excepción se propaga sin reetiquetar |
| T4 | Traducción fija: rechazo del emisor → el motivo que ya da el autenticador (`policy_not_approved` o `authentication_failed`); payload ilegible o rechazado por el parser → `parse_failed`; mensaje observado → `policy_not_approved`, porque sus lecturas son `NOT_APPROVED_FOR_USE`. Ningún resultado hace disponible la glucosa |
| T5 | El parser recibe como paquete autenticado el que probó la autenticación, nunca el extra `packageName` |
| T6 | Sin consumidor: ningún receiver, pantalla, caso de uso ni almacenamiento usa la composición. `PendingDexcomSource`, `LocalGlucoseSourceResult` y los manifiestos no cambian |

El detalle de un rechazo (código `dexcom_g7.payload.*`, campo e índice) queda en
el resultado para diagnóstico futuro. Mostrarlo o registrarlo exige su propio
ADR.

## Alternativas consideradas

- **Registrar ya un receiver productivo**: exige anclas aprobadas y un
  permiso `dangerous` del productor. Bloqueado.
- **Leer los extras con getters tipados** (`getInt`, `getLong`): Android
  convierte o devuelve un valor por defecto si el tipo no coincide, y eso
  ocultaría un cambio del productor. Se usa el getter sin tipo.
- **Tratar un extra desconocido ilegible como desconocido y seguir (D2)**: no
  se puede saber qué contenía. Se prefiere fallar cerrado (T3).
- **Robolectric para probar `Bundle` en JVM**: añade una dependencia grande y
  posibles providers en la instrumentación (ADR 0017). Se prueba la lógica con
  `ExtrasView` en JVM y la vista `Bundle` en dispositivo.

## Consecuencias

- El futuro receiver solo tendrá que construir `AndroidDexcomSenderEvidenceSource`
  y llamar a `DexcomG7Reception.receive(source) { intent.readDexcomPayload() }`.
- Offline: sin efecto, nada usa red.
- iOS: no recibe este broadcast. La composición es un adaptador Android; el
  parser compartido sigue sin objetivo Apple.
- Rollback: borrar los dos ficheros, sus pruebas y la dependencia; nadie los
  consume.

## Seguridad y pruebas

- G2: todas las salidas bloquean glucosa. `DexcomG7ReceptionTest` lo comprueba
  para cada variante.
- `DexcomPayloadExtrasTest` (JVM, 5): mapeo de todos los tipos sin conversión,
  acción y extras ausentes, ilegible en valor, en claves y anidado, defectos
  propagados, cada extra leído una vez.
- `DexcomG7ReceptionTest` (JVM, 7): rechazos del emisor sin leer el payload y
  con su motivo, mensaje válido como `policy_not_approved`, afirmación de
  paquete comparada con el emisor autenticado, ilegible y rechazado como
  `parse_failed`, ninguna salida disponible, autenticación por mensaje y
  defectos del lector propagados.
- `DexcomBundleExtrasDeviceTest` (dispositivo, 3): `Bundle` real con tipos
  mezclados y anidados, ida y vuelta por `Parcel` como en un broadcast entre
  procesos e `Intent` sin extras. Añadida a la lista de `verify.ps1`.
- `verify.ps1`: lista cerrada de ficheros que pueden usar el parser o la
  composición. Los manifiestos siguen sin receivers ni permisos Dexcom.

No hay prueba automatizada de `BadParcelableException` en dispositivo: provocarla
de forma fiable exige una clase `Parcelable` ajena con un cargador distinto. La
rama está cubierta en JVM a través de `ExtrasView`.

## Condiciones para revisar este ADR

- Aprobación de anclas y release reproducible del productor.
- ADR del receiver productivo, de la persistencia o de la presentación de
  rechazos.
- Cambio de los tipos observados en el transporte.
