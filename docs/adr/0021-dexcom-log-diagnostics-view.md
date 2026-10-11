# ADR 0021: vista del registro Dexcom en Diagnóstico

- Estado: aceptado (decisiones F1 y F2 del propietario, 2026-10-11). F3 a F5 son
  decisiones técnicas del agente, revisables en la PR
- Fecha: 2026-10-11
- Fase y criterio de aceptación: Fase 5, trabajo 5 («mostrar ... estado; no
  insinuar vigencia») en su parte de diagnóstico; no muestra lecturas
- Responsables de decisión: propietario
- Evidencia clínica requerida: no aplica; no se muestra ningún valor de glucosa

## Contexto

El [ADR 0020](0020-dexcom-g7-observation-log.md) guarda lo recibido del
productor Dexcom en `dexcom-observations.db`, sin consumidor. Para comprobar en
el dispositivo que la recepción funciona cuando exista el receiver, hace falta
ver el registro sin riesgo de confundirlo con glucosa actual.

## Decisión

| ID | Decisión |
|---|---|
| F1 (propietario) | Solo recuentos y estado: mensajes, observados, ilegibles, rechazos por código, lecturas guardadas, duplicados, colapsados en el mensaje, conflictos, hora UTC de la última recepción y emisor (`paquete@versionCode`). Nunca valor, tendencia, timestamp del productor ni huella del transmisor |
| F2 (propietario) | Solo en Diagnóstico. Bolo y el resto de pantallas no leen el registro. Cada entrada en Diagnóstico lo lee de nuevo |
| F3 | Bloque técnico plegado con tarjeta «Registro Dexcom» y aviso «No contiene lecturas de glucosa utilizables». El estado plegado no se guarda: se pliega al cambiar de destino y tras recreación. Lectura pendiente → `dexcom_log.read.pending`; fallo → su código `dexcom_observation.*` |
| F5 | El resumen se agrega en SQLite dentro de una transacción de lectura (`summary()` del puerto); solo se decodifica el último mensaje. Nunca se carga el historial, que crece sin borrado (E4). Debe ser igual a `ObservationSummary.of(read())`. Los duplicados frente a mensajes anteriores (`duplicates`) y los colapsados dentro de un mismo mensaje (`collapsed_in_message`) se muestran por separado (revisión de Codex en la PR #47) |
| F4 | La lectura de producción no crea ni migra el fichero: si `dexcom-observations.db` no existe, el registro se presenta vacío sin abrir SQLite. La lectura corre fuera del hilo principal y una lectura nueva descarta la anterior |

Componentes:

- `shared/dexcom-g7-payload`: `ObservationSummary.of(ObservationRead.Loaded)`,
  puro y sin valores de glucosa.
- Android: `ui/DexcomLogBlock` (líneas puras), `ui/DexcomLogModel`
  (`ViewModel`, solo lectura), `glucose/dexcom/storage/DexcomLogReader`
  (F4), `DexcomLogView` en `ScreenRenderer` y la composición en
  `MainActivity` con la costura de pruebas `dexcomLogReader`.

Sin escrituras, receiver, permisos ni cambios de esquema. Glucosa, cálculo y
tratamiento siguen bloqueados.

## Alternativas consideradas

- **Mostrar la última lectura cruda**: descartado por el propietario (F1).
- **También en Bolo**: descartado por el propietario (F2).
- **Leer en el hilo principal**: bloquearía la interfaz con una base grande.

## Consecuencias

- Hoy el registro aparece vacío: no hay receiver. Cuando exista, Diagnóstico
  permitirá comprobar recepción, rechazos y conflictos sin ver valores.
- iOS: el resumen es común. La vista es Android.

## Seguridad y pruebas

- `ObservationSummaryTest` (JVM común, 3): vacío, recuentos y último emisor,
  sin valor, tendencia ni huella en el resumen.
- `DexcomLogBlockTest` (JVM Android, 3): pendiente y fallo, registro vacío
  sin inventar recepción, líneas exactas de un registro cargado.
- `SqliteDexcomObservationStoreDeviceTest` añade la igualdad entre
  `summary()` y `ObservationSummary.of(read())` y la corrupción también en el
  resumen.
- `DexcomLogDiagnosticsDeviceTest` (dispositivo, 3): bloque plegado sin
  valores, fallo con su código, solo Diagnóstico lee y cada entrada vuelve a
  leer. En la lista de `verify.ps1`.
- `verify.ps1`: `ScreenRenderer` no conoce el registro ni sus tipos, y la
  lista cerrada de usuarios del parser incluye solo estos ficheros.

## Condiciones para revisar este ADR

- ADR del receiver productivo o de presentación de lecturas.
- Cambio del esquema del registro (ADR 0020).
