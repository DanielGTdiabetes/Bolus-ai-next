# ADR 0023: recepción real Dexcom G7 y presentación local de la última lectura

- Estado: propuesto. Reúne en una sola propuesta las decisiones pendientes del
  primer hito Android (EXECUTION_PLAN §6, «Prioridad de continuación —
  2026-10-11»). Nada de este documento está aprobado hasta que el propietario
  responda a la sección «Decisiones solicitadas»
- Fecha: 2026-10-11
- Fase y criterio de aceptación: Fase 5, trabajos 3 a 6 y los cinco criterios de
  aceptación de la fase
- Responsables de decisión: propietario
- Evidencia clínica requerida: pendiente. La presentación no alimenta cálculo,
  pero muestra un valor de glucosa al usuario por primera vez

## Contexto

Desde los ADR 0018 a 0022, Next autentica al emisor, lee extras, parsea el
payload observado y guarda observaciones en `dexcom-observations.db`. El
receiver existe desactivado (R1), sin el permiso del productor (R2) y con la
política `Pending`, así que rechaza todo emisor. Diagnóstico solo muestra
recuentos (F1 del ADR 0021). Ninguna pantalla presenta glucosa y
`ReadOverview` sigue devolviendo `input.glucose.policy_not_approved`.

El objetivo de la Fase 5 es abrir Android sin Internet y ver una lectura local
con valor, unidad, origen, hora, antigüedad y estado, sin habilitar cálculo.
Para llegar hay dos tipos de bloqueo:

1. **Artefactos del productor**, que solo puede aportar el propietario:
   release reproducible, opt-in de identidad y anclas (contrato
   [observado](../contracts/modified-dexcom-g7-broadcast-observed-v1.md)).
2. **Decisiones de alcance y presentación** que hoy no tienen dueño: permiso,
   interruptor, interpretación del timestamp, unidad, selección de la lectura,
   tratamiento de anomalías y vigencia.

Este ADR no inventa ninguna de ellas. Para cada una propone opciones y una
recomendación, y declara qué bloquea.

## Prerrequisitos del productor (acciones del propietario)

No son decisiones de código. Sin ellos la política sigue en `Pending` y el
recorrido real no puede probarse.

| ID | Acción | Bloquea |
|---|---|---|
| P1 | Archivar la compilación instalada como release reproducible (APK, SHA-256 y fuente), o reconstruirla y volver a comparar. Hoy la APK instalada (`6746ac78…`) no coincide con `v23` (`8c21fef7…`) | anclas (P3) |
| P2 | Modificar el productor para enviar con `BroadcastOptions.setShareIdentityEnabled(true)` en las dos rutas (`sendBroadcast(Intent, String, Bundle)`). Sin esto Android no atribuye el emisor y la autenticación devuelve `identity_not_shared` | toda recepción |
| P3 | Aportar las anclas de esa release: paquete exacto, `versionCode` y conjunto completo de SHA-256 de firmantes | política `Approved` |

Dispositivo: el Pixel está en API 37. En API 33 o inferior la recepción sigue no
disponible (contrato observado); no se propone otro mecanismo.

## Decisiones solicitadas

Cada fila indica la opción recomendada en negrita. Las marcadas como
**clínica** no pueden resolverse por criterio técnico.

### Bloque A — Confianza y activación

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| A1 | Dónde viven las anclas aprobadas | (a) constante en código de producción, revisada por PR y comprobada por `verify.ps1` contra una tabla en el contrato; (b) propiedad de Gradle local fuera del repositorio | **(a)**. Paquete, versión y digests de firma no son secretos y así quedan trazados por commit. (b) haría el APK irreproducible y la política invisible en revisión |
| A2 | Permiso `com.dexcom.cgm.EXTERNAL_PERMISSION` (nivel `dangerous`) | (a) declararlo y pedirlo en tiempo de ejecución desde un botón explícito en Ajustes o Diagnóstico, con estado visible; (b) declararlo y concederlo solo por `adb shell pm grant` durante la validación | **(b) durante la validación, (a) al cerrar el hito**. El permiso no autentica (R8); solo permite que el productor descubra a Next. Sin permiso concedido, la UI muestra `permission_missing` en lugar de «sin lecturas» |
| A3 | Interruptor del receiver (R1) | (a) mantener `enabled="false"` y activar por `adb` hasta terminar la validación real; (b) activado por defecto en cuanto haya anclas | **(a)**. Cambiar a (b) se propone en el informe de validación, con la prueba offline superada |
| A4 | Convivencia con Legacy | El productor envía una copia explícita a Legacy y otra a cada paquete con el permiso. Next solo lee y registra en local, sin sync ni tratamientos | **Confirmar** que Legacy conserva la autoridad y que recibir en paralelo no cambia nada en Legacy (AGENTS.md §2 y §7). No requiere código |

### Bloque B — Interpretación del dato (contrato v2 del transporte)

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| B1 | Semántica de `timestamp` | (a) epoch UTC en segundos (el productor divide un valor interno entre 1000), aceptada solo tras comprobar en la validación real que la diferencia con la recepción es coherente; (b) no interpretarlo y mostrar solo la hora de recepción | **(a) con verificación**. Hasta superar la validación se presenta la hora de recepción, etiquetada como tal, y la hora de medida como «no verificada». El informe de validación registra diferencias observadas, sin fijar umbrales clínicos |
| B2 | `egvTxTime` | (a) conservar sin interpretar; (b) investigar semántica | **(a)**. No se presenta ni se usa |
| B3 | Unidad de `glucoseValue` | (a) mg/dL como afirmación versionada del productor (especificación `06f25370…`); (b) unidad desconocida y no mostrar valor | **(a)**, mostrando siempre «mg/dL». **Sin conversión** a mmol/L aunque el perfil esté en mmol/L: convertir es una regla pendiente |
| B4 | **clínica** — Valores extremos (D4 del ADR 0018 no aplica rango) | (a) mostrar el entero recibido con el estado «no aprobada para uso»; (b) no mostrar valores fuera de `1..400` y presentar «valor fuera del intervalo emitido por el productor» | **Decisión del propietario**. (b) usa un literal observado del productor como filtro de presentación, no como rango clínico; (a) no inventa nada pero puede enseñar un número sin sentido. Sin respuesta, se implementa (a) **sin** presentarlo como glucosa actual: ver B6 |
| B5 | Tendencia `trendArrow` | (a) no mostrarla en la vista principal y conservar el nombre literal en detalles; (b) traducirla a flechas | **(a)**. Su significado no está contratado |

### Bloque C — Qué se presenta y dónde

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| C1 | Pantallas | (a) Inicio y Bolo, solo lectura; (b) solo Inicio | **(a)**. Bolo sigue con el cálculo bloqueado y añade la glucosa con su estado. Diagnóstico mantiene F1 (solo recuentos) |
| C2 | Lectura a mostrar | (a) la de mayor `timestamp` del productor entre todas las observaciones; (b) la última recibida | **(a)**, porque el backfill puede llegar después. Si ese `timestamp` tiene un conflicto registrado (E2), se muestra «conflicto» sin valor |
| C3 | Cambio de transmisor | (a) la selección C2 abarca todos los transmisores y la huella se muestra abreviada en detalles; (b) solo el último transmisor visto | **(a)**. No hay contrato de estabilidad de `transmitterId` |
| C4 | Timestamp futuro | (a) cualquier `timestamp` posterior al instante de recepción del mismo mensaje es anomalía `future_timestamp`: se muestra el estado sin valor ni antigüedad; (b) tolerancia de segundos | **(a)**. Emisor y receptor comparten reloj y el productor trunca a segundos, así que una medida nunca debería ser posterior a su recepción. Se compara en segundos sin redondear a favor |
| C5 | **clínica** — Vigencia | (a) mostrar la antigüedad («hace N min», reloj inyectado) con el estado «vigencia sin política aprobada»; nunca «actual»; (b) aprobar un umbral (la auditoría documentada de Legacy en `f5417721…`, no revalidada en esta propuesta, observa 10 min al resolver la fuente y 15 min para marcar histórico) | **(a)** en este hito. El umbral queda para el ADR de uso clínico. Con (a) ninguna lectura cumple «válida» del criterio de la fase: el criterio se cumplirá como «lectura local presentada con metadatos correctos y vigencia explícitamente no aprobada» |
| C6 | Estados sin lectura | Distinguir: receiver desactivado, permiso ausente, política pendiente, ninguna lectura registrada, registro ilegible, último mensaje rechazado | **Adoptar** esos seis estados con códigos estables `dexcom_presentation.*`. Nunca un valor anterior presentado como nuevo |
| C7 | Contador duradero de fallos del receiver (pendiente de R10/R11) | (a) no ahora; (b) añadirlo | **(a)**. No es necesario para el hito ni cubre un riesgo concreto |

### Bloque D — Validación

| ID | Pregunta | Recomendación |
|---|---|---|
| D1 | Prueba offline | Sin modo avión (ADR 0022). Desactivar Wi-Fi y datos móviles, mantener Bluetooth, comprobar con `adb shell dumpsys connectivity` que no hay red validada y verificar que llegan lecturas y se presentan. Repetir tras reiniciar el teléfono |
| D2 | Datos en la evidencia | El informe registra recuentos, diferencias de tiempo y códigos, nunca valores de glucosa ni el `transmitterId` en claro |

## Plan de implementación tras aprobar

Una entrega funcional, en este orden y con su prueba en cada paso:

1. Contrato del transporte v2: B1 a B5 en `docs/contracts/` y en
   `shared/dexcom-g7-payload` (tipos explícitos, sin cambiar D1 a D4 del
   ADR 0018).
2. Caso de uso común puro `PresentLatestDexcomReading(log, clock)` con C2 a C6.
   Pruebas: sin lecturas, conflicto, futuro, reordenación y backfill, varios
   transmisores, duplicados, antigüedad con reloj inyectado, registro ilegible.
3. Lectura de solo lectura del registro (como `summaryOfExisting()`, sin crear
   ni migrar el fichero) y modelo Android fuera del hilo principal.
4. UI: bloque en Inicio y Bolo con valor, «mg/dL», origen, hora de recepción,
   hora de medida (según B1), antigüedad y estado. Bolo conserva el cálculo
   bloqueado y `ReadOverview` sigue sin glucosa disponible: el motor no recibe
   nada.
5. Política `Approved` con las anclas de P3 (A1) y permiso (A2).
6. Pruebas instrumentadas con emisor sintético y base sintética, y validación
   real según D1 y D2.

`verify.ps1` seguirá exigiendo que el motor, `ReadOverview` y
`LocalGlucoseSourceResult` no dependan del registro ni de la presentación.

## Alternativas consideradas

- **Implementar la presentación con supuestos y pedir aprobación después.**
  Rechazada: unidad, timestamp y vigencia son exactamente lo que AGENTS.md §3
  prohíbe suponer.
- **Una PR por decisión.** Rechazada por EXECUTION_PLAN §3.3.
- **Usar la Dexcom Web API mientras falten P1 a P3.** Fuera de alcance: es
  contingencia con retraso y no cumple el hito local.

## Consecuencias

- Sin P1 a P3 se puede construir y probar todo con el emisor sintético, pero la
  app real seguirá sin recibir nada. Esto se indicará en la UI.
- Mostrar glucosa sin vigencia aprobada puede parecer una lectura actual. Lo
  mitigan la antigüedad siempre visible, el estado textual y el bloqueo de
  cálculo.
- Rollback: desactivar el receiver con `pm default-state` y retirar el bloque
  de presentación. El registro append-only se conserva.

## Seguridad y pruebas

- Ninguna decisión de este ADR habilita cálculo, IOB, tratamiento ni sync.
- Fail-closed: cualquier estado distinto de «lectura presentable» muestra su
  causa y no un valor.
- Pruebas obligatorias: las del paso 2, la ausencia de dependencia del motor y
  la validación real offline tras reinicio.

## Condiciones para revisar este ADR

Cambio del productor o de su forma de envío, aprobación de una política de
vigencia o rango, o evidencia de la validación real que contradiga B1, B3 o C4.
