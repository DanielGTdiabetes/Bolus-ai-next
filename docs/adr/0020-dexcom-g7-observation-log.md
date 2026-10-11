# ADR 0020: registro local de observaciones Dexcom G7

- Estado: aceptado (decisiones E1 a E4 del propietario, 2026-10-11). E5 a E8
  y E9 son decisiones técnicas del agente, revisables en la PR
- Fecha: 2026-10-11
- Fase y criterio de aceptación: Fase 5, trabajo 3 («normalizar y persistir»)
  en la parte de persistencia y trabajo 4 en duplicados y orden; no cumple
  todavía ningún criterio de aceptación de la fase
- Responsables de decisión: propietario
- Evidencia clínica requerida: no aplica; nada de lo guardado es glucosa
  utilizable

## Contexto

El [ADR 0019](0019-dexcom-g7-reception-composition.md) compone autenticación,
lectura de extras y parser, pero su resultado solo vive en memoria. La fase 5
exige persistir valor, unidad, timestamps, origen e identidad, detectar
duplicados y orden, y sobrevivir a reinicios antes de mostrar una lectura.
AGENTS.md §6 exige además no borrar ni sobrescribir historial y resolver
conflictos de forma explícita.

Este ADR solo crea el registro. No hay receiver ni consumidor, no se muestra
nada y la glucosa sigue bloqueada.

## Decisión

### Decisiones del propietario

| ID | Pregunta | Decisión |
|---|---|---|
| E1 | Qué se guarda | Mensajes observados con sus lecturas y, de cada mensaje rechazado o ilegible, solo código, campo e índice. Nunca su contenido |
| E2 | Misma clave (transmisor y `timestamp` del productor) con otro contenido en otro mensaje | Se conserva y se registra como conflicto enlazado a la primera observación. Nada se sobrescribe. Ambas siguen bloqueadas |
| E3 | Identificador del transmisor | Solo su huella SHA-256. El identificador no se guarda |
| E4 | Retención | Append-only, sin borrado automático. La política de retención queda pendiente con su propio ADR |

### Reglas técnicas

| ID | Regla |
|---|---|
| E5 | Los rechazos del emisor no se guardan: nada prueba quién envió el mensaje y guardarlo permitiría a cualquier app llenar la base. Solo se registra lo que llega tras autenticar |
| E6 | Duplicado entre mensajes: lectura idéntica (transmisor, `timestamp`, valor, tendencia y `egvTxTime`) a cualquier observación guardada con su clave. No se vuelve a guardar y se cuenta en el mensaje. Repetir un conflicto ya guardado también es duplicado. Un conflicto siempre apunta a la primera observación de su clave |
| E7 | Huella = SHA-256 de `bolusai.dexcom.transmitter.v1\0` + identificador UTF-8. El prefijo separa este uso de cualquier otro. Minimiza, no anonimiza: un identificador corto se puede probar por fuerza bruta |
| E8 | `received_at_ms` es el reloj de pared del dispositivo, inyectado. Solo sella la recepción: no se deriva vigencia ni orden de él |
| E9 | Cada mensaje guardado, también rechazado o ilegible, lleva el emisor que probó la autenticación: paquete, `versionCode` exacto y huellas SHA-256 de los firmantes actuales, ordenadas. Así la procedencia sobrevive a cambios posteriores de la política de confianza. No se guarda el UID: no es estable entre reinstalaciones. Las variantes autenticadas de `DexcomG7ReceptionOutcome` (ADR 0019) pasan a llevar ese emisor (revisión de Codex en la PR #46) |

### Componentes

- `shared/dexcom-g7-payload`, `ObservationLog.kt`: `TransmitterFingerprint`,
  `TransmitterHasher`, `ObservationIds`, `AuthenticatedSender`, `ObservationInput`, `StoredMessage`,
  `StoredReading` (siempre `NOT_APPROVED_FOR_USE`), `ObservationPolicy.plan`
  (decisión pura de qué añadir), `ObservationWrite`, `ObservationRead` y el
  puerto `DexcomObservationStore`.
- Android, `glucose/dexcom/storage`:
  - `DexcomObservationSchema`: esquema v1 congelado y verificado por
    definición en cada apertura.
  - `SqliteDexcomObservationStore`: base propia `dexcom-observations.db`.
    Política y escrituras en una transacción exclusiva, relectura antes del
    commit, lectura estricta y fallo cerrado (`corrupt_storage`,
    `unsupported_schema`).
  - `DexcomG7Recorder` y `AndroidTransmitterHasher`.
- Tablas `dexcom_messages` y `dexcom_readings`. Triggers impiden `UPDATE` y
  `DELETE`. Un índice único parcial garantiza una sola primera observación por
  clave. Un trigger exige que todo conflicto apunte a la primera observación de
  su misma clave. `CHECK` de outcome, contrato, estado y recuentos.
- Recorder: rechazo del emisor → no se guarda y conserva su motivo. Lo demás
  se guarda con el reloj inyectado. Si la escritura falla, la glucosa pasa a
  `input.glucose.persistence_failed` y nunca se informa como guardada.

La base queda excluida de copias y transferencia por las reglas existentes
(`data_extraction_rules.xml` excluye todo el dominio `database`).

### Lo que no cambia

Sin receiver, permisos, UI, sync ni uso clínico. `PendingDexcomSource` sigue
igual. Nadie fuera de la lista cerrada de `verify.ps1` usa el registro.

## Alternativas consideradas

- **Última escritura gana**: prohibido por AGENTS.md §6 para datos clínicos.
- **Guardar el identificador en claro**: el propietario eligió E3.
- **Guardar también rechazos del emisor**: abre un vector de llenado de
  almacenamiento por apps no autenticadas (E5).
- **Reutilizar la base del perfil**: mezcla dominios y migraciones. Base
  propia, como comidas y perfil.

## Consecuencias

- El futuro receiver solo tendrá que encadenar `DexcomG7Reception` y
  `DexcomG7Recorder`. La recepción será durable antes de mostrar nada.
- La base crece sin límite hasta que exista un ADR de retención (E4).
- iOS: la lógica de duplicados y conflictos es común y reutilizable. El
  almacenamiento es Android.
- Rollback: el registro no tiene consumidor. Retirar el código deja un fichero
  `dexcom-observations.db` que la app no abre.

## Seguridad y pruebas

- `ObservationPolicyTest` (JVM común, 13): lecturas nuevas sin identificador en
  claro, duplicado entre mensajes, conflicto enlazado a la primera para cada
  campo, repetición de un conflicto, otro transmisor con el mismo timestamp,
  rechazos sin contenido, diagnósticos del parser conservados, defectos del
  almacén, formas inconsistentes y emisor autenticado completo en cada
  mensaje (E9).
- `DexcomG7RecorderTest` (JVM Android, 5): emisor rechazado sin guardar ni leer
  el reloj, resultados autenticados guardados con el reloj inyectado y
  bloqueados, fallo de escritura como `persistence_failed`, defectos
  propagados y vector SHA-256 con prefijo.
- `SqliteDexcomObservationStoreDeviceTest` (dispositivo, 8 en esta entrega; el
  ADR 0021 añadió una más, 9 existentes): reapertura con
  duplicados y conflictos, rechazos sin contenido, identificador ausente del
  fichero, escritura fallida sin restos, triggers append-only, trigger de
  conflicto, código desconocido como corrupción y esquema alterado como
  `unsupported_schema`. En la lista de `verify.ps1`.

## Condiciones para revisar este ADR

- ADR de retención o exportación.
- ADR del receiver productivo o de la presentación de lecturas.
- Cambio del contrato observado o de la semántica de `timestamp`.
