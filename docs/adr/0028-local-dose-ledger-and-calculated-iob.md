# ADR 0028: historial local de dosis e IOB calculado en Bolo

- Estado: aceptado para desarrollo y pruebas, según la autorización del
  propietario de 2026-10-12 (AGENTS.md §3) y su decisión de 2026-11-28 sobre el
  origen del historial. No aprueba ningún valor ni uso terapéutico
- Fecha: 2026-11-28
- Fase y criterio de aceptación: fase 6, puntos 4 y 5 (IOB local desde un
  historial verificable), y fase 7 en modo de prueba (revisión, confirmación y
  registro local de la dosis)
- Responsables de decisión: propietario. Las decisiones técnicas reversibles las
  toma el agente (EXECUTION_PLAN §3.3)
- Evidencia clínica requerida: pendiente. Las reglas de IOB siguen siendo
  candidatas (ADR 0026)

## Contexto

El IOB candidato (ADR 0026) y la acción de la insulina del perfil (ADR 0027)
existían sin productor de historial: Bolo usaba un IOB tecleado a mano. Quedaba
por decidir de dónde salen las dosis.

Decisión del propietario, 2026-11-28: Next sustituye a `bolus_ai` y debe
funcionar entero en el móvil, sin NAS, Render ni su base de datos. Como en
`bolus_ai`, los bolos los calcula la app y de ahí sale su historial. Por tanto
el historial de dosis es local y lo alimenta el propio flujo de Bolo.

Legacy consultado en solo lectura. `main` remoto el 2026-11-28:
`e48c4ab896751091f323379b17c6211aff793487`. Las reglas siguen fijadas en
`bcac891d85e6fb3e84398c118061e6aeae3f73f1` (ADR 0026); no se ha auditado la
diferencia entre ambos SHA. Observado en `bcac891`,
`backend/app/services/iob.py::compute_iob_from_sources`: el IOB total se
devuelve sin redondear y así entra en el cálculo del bolo.

## Decisión

### 1. Registro local de dosis (`shared/insulin-history`)

Módulo común sin dependencias. Eventos append-only con identificador por
intención (`eventId`, clave de idempotencia):

- `TrackingStarted`: instante desde el que el usuario declara que todas sus
  dosis de insulina rápida quedan anotadas. Solo puede existir uno.
- `Dose`: unidades en decimal canónico (máximo 6 enteros y 3 decimales, límite
  de representación), instante del reloj inyectado, origen `test_calculation`
  o `user_note`. Una dosis de `test_calculation` guarda en su misma fila el
  registro inmutable del cálculo (`CalculationRecord`): el texto canónico de la
  petición al motor (entradas normalizadas, versión y huella del perfil, motor y
  reglas), cuya SHA-256 es la huella de entradas, y el texto del resultado
  (pasos, hechos, total, avisos, reglas no aplicadas e IOB usado con sus
  dosis). Así el cálculo sigue siendo auditable tras cerrar la app. El IOB se
  guarda con su huella, su valor y las dosis con insulina restante; su petición
  completa se reconstruye del propio registro, que es append-only.
- `Void`: anula una dosis; la fila original se conserva.

Un reintento con el mismo `eventId` y el mismo contenido es `Replayed`, no una
segunda dosis; con otro contenido es `dose_ledger.event_id_conflict`. No hay
dosis con fecha atrasada, ni cero, ni antes de empezar el registro. No hay
ningún valor clínico en el módulo: ni DIA, ni ventana, ni dosis máxima.

Cada entrada lleva el estado `local_note_not_treatment_authority`: es una
anotación local para el IOB de prueba. No se sincroniza, no se envía a Legacy
ni a Nightscout y no concede autoridad de tratamiento a Next.

### 2. Almacén Android

`insulin-doses.db` v1, base propia (`SqliteDoseLedger`): una tabla
`dose_events`, triggers que impiden `UPDATE` y `DELETE`, índices únicos para un
solo inicio y una sola anulación por dosis, `synchronous=FULL` y una transacción
por alta. Un fichero que no sea exactamente v1 o con una fila incoherente se
informa como fallo y no se toca. Auto Backup sigue desactivado.

`DoseStorageGuard` impide abrir la base real durante la instrumentación. Sin
fábrica sintética, la actividad usa un registro no disponible: historial
desconocido, nunca vacío.

### 3. Del registro al motor

`TestCalculation` traduce, sin calcular:

- acción de la insulina: DIA, curva y pico de la última versión del perfil, con
  su versión, huella y confirmación. Lo ausente bloquea con su causa
  (`profile.insulin_curve.not_in_schema`, `…not_configured`, etc.);
- historial: cobertura desde `TrackingStarted` hasta el instante del cálculo,
  extendido máximo declarado `0` (solo se anotan bolos normales) y las dosis no
  anuladas. Registro sin leer, ilegible o sin empezar es
  `iob.history.read_pending`, `iob.history.unreadable` o
  `iob.history.tracking_not_started`.

`CandidateIob` decide: si el registro no cubre toda la acción de la insulina
bloquea con `iob.history.insufficient_window`. Recién empezado el registro, el
IOB calculado queda bloqueado hasta que pase ese tiempo. No se ofrece declarar
«sin dosis anteriores»: sería completar el historial con una suposición.

### 4. Procedencia del IOB en el cálculo del bolo

`IobValue` distingue `manual_entry` de `calculated_from_dose_history`. El
calculado conserva el `double` exacto del IOB, sin redondeo, y su referencia es
la huella de entradas de ese cálculo de IOB, que entra en la huella del bolo.
El aviso pasa a `iob.calculated_candidate_unverified`. La codificación del IOB
manual no cambia, así que las huellas y los 12 vectores existentes se conservan.

### 5. Bolo

El formulario elige el IOB: a mano (por defecto, como hasta ahora) o calculado
del historial. Bajo el cálculo aparece el registro: empezar, anotar una dosis
puesta ahora, registrar el total de prueba como bolo puesto y anular. Cada alta
pide confirmación explícita y se guarda antes de repintar. Registrar el total
descarta el resultado para que no pueda anotarse dos veces. El motor redondea
en binario (`0,1 × 3 = 0,30000000000000004`): al registrar el total solo se
elimina ese ruido, por debajo de `1e-9`; un total con decimales reales más allá
del tercero no se redondea y no puede registrarse desde el botón. El resultado
muestra el desglose del IOB o las causas del bloqueo; un IOB bloqueado nunca se
muestra como cero.

## Límites y pendientes

- Durante la convivencia los bolos reales se siguen registrando en `bolus_ai`.
  El IOB de Next refleja solo lo anotado en Next y es un resultado de prueba.
- Sin bolo extendido o dual, sin importación desde Legacy o Nightscout, sin
  hidratos ni COB, sin techo de IOB ni intervalo mínimo entre bolos.
- La lectura del registro es síncrona en el hilo de la interfaz y carga todos
  los eventos. Suficiente para la prueba; paginar o acotar exige decidir la
  ventana sin inventarla.
- El reloj es el del sistema: una dosis con instante posterior al cálculo
  bloquea (`iob.history.future_dose`).
- iOS: el módulo es común; falta el almacén. Paridad de Walsh en iOS sin
  comprobar (ADR 0026).
- Retención y exportación del registro, sin decidir.

## Pruebas

- `DoseLedgerTest` (común): inicio único, alta, reintento, conflicto, rechazos,
  reloj atrasado, anulación e incoherencias.
- `DoseHistoryCalculationTest` (JVM): cero con cobertura suficiente, efecto de
  una dosis en el total, procedencia en la huella, anulación, bloqueos por
  historial desconocido, ventana insuficiente, perfil sin acción o sin
  confirmar y dosis futura, y la opción manual sin cambios.
- `DoseHistoryCalculationTest` cubre además el registro de auditoría (huella que
  coincide con la petición, rechazo de un resultado de otra petición) y el
  ruido binario del total.
- Evidencia de dispositivo: [validación](../validation/local-dose-ledger-2026-11-28.md).
- `SqliteDoseLedgerDeviceTest` (dispositivo): reapertura, reintento, rechazos
  sin escritura, append-only, esquema ajeno o más nuevo y barrera de
  instrumentación.
- `verify.ps1`: consumidores permitidos del IOB, módulo autocontenido, motor sin
  dependencia del registro, estado sin autoridad, triggers y ausencia de red.

## Reversión

Elegir «IOB a mano» en Bolo devuelve el comportamiento anterior. Revertir el
cambio deja `insulin-doses.db` sin lector; el fichero no afecta a nada más.
