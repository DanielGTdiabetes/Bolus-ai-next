# ADR 0017: informe del perfil en los detalles del bloqueo de Bolo

- Estado: **aceptado** el 2026-10-04 con B2 a B10 según su recomendación,
  incluida expresamente la ampliación a Diagnóstico (B10), y con las
  precisiones de la sección 14. La implementación queda autorizada con ese
  alcance y con cálculo y tratamiento bloqueados.
- Fecha: 2026-10-04.
- Fase y criterio de aceptación: fases 2 y 6. Desarrolla la opción B que el
  [ADR 0016](0016-profile-unavailability-consumer.md) aprobó solo como
  planificación (B1): decide si «Detalles del bloqueo» de Bolo muestra el
  informe v2 real del perfil, qué se ve antes de la primera lectura, cómo se
  combina con los códigos v1 y cómo se comporta ante errores, reintentos y
  recreación.
- Responsables de decisión: propietario del proyecto.
- Evidencia clínica requerida: no aplica. No se introduce ninguna regla,
  límite, vigencia, valor por defecto ni conversión.
- Base Next: `f4f622a643810967e36adc0ee2cf94b32534977b` (PR #40 integrada).
  CI del SHA exacto:
  [Verify 37178396380](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37178396380),
  rama `main`, evento `push`, conclusión `success`.
- Legacy: no se consulta.
- Relacionados: ADR 0012, ADR 0014 (C7), ADR 0015 (D7, D8, D11), ADR 0016 y
  [`unavailable-input-v2`](../contracts/unavailable-input-v2.md).

Invariantes que ninguna opción modifica:

- `allowsCalculation = false` y `allowsTreatment = false` en todo estado.
  `ScreenRenderer.render` conserva su `check`. «Calcular» y «Confirmar» siguen
  deshabilitados en todos los estados del perfil, también con una versión
  completa y confirmada.
- Todo informe válido del perfil contiene
  `input.profile.policy_not_approved` [`profile.not_approved_for_calculation`].
  Ante el rechazo conocido del traductor (D11 del ADR 0015) no hay informe
  del perfil: se muestra el identificador estable
  `profile_unavailability.unreadable_reason_not_supported`, no se fabrica una
  causa sustitutiva (tampoco `policy_not_approved`) y cálculo y tratamiento
  siguen bloqueados porque `allowsCalculation` y `allowsTreatment` no dependen
  del informe.
- El motor y `ReadOverview` no leen el perfil ni el informe. No hay fórmulas,
  límites, vigencia, DIA, IOB ni conversiones.
- Bolo muestra causas ya determinadas. No decide, no las ordena por
  importancia, no cambia colores, textos visibles ni acciones según el estado
  del perfil y no las convierte en un permiso.
- Bolo no escribe nada en el perfil: no guarda, no confirma, no retira y no
  reintenta operaciones.
- Sin cambios de SQLite, huellas, serialización, confirmaciones ni contrato v2.

## 1. Problema

Tras el ADR 0016, Ajustes → Cálculo muestra el informe v2 real del perfil. Bolo
sigue mostrando en «Detalles del bloqueo» `input.profile.policy_not_approved`
estático (D7 del ADR 0015). Las dos pantallas describen la misma entrada con
códigos distintos: una lectura fallida, un perfil sin versión o una
confirmación retirada no aparecen en Bolo.

Conectar Bolo exige retirar una garantía vigente desde el ADR 0012 y repetida
en el ADR 0014 y en `verify.ps1`: «Bolo no lee el perfil». Este ADR propone
retirarla de forma acotada y verificable, no eliminarla sin más.

## 2. Evidencia (base `f4f622a`)

| Elemento | Ubicación | Qué implica |
|---|---|---|
| `ClinicalProfileModel` | `android/.../ui/ClinicalProfileModel.kt` | `ViewModel` de la actividad, creado en `onCreate` aunque no se abra Ajustes. Un único `worker`, `stateToken` que descarta resultados superados, `gate` nulo mientras lee |
| `ensureLoaded()` | mismo fichero | no hace nada si hay `gate` o una lectura en curso. Si hay una operación de confirmación pendiente la resuelve primero (`resolvePending`, ADR 0014 §9.3), si no lee. Ambas son solo lectura |
| `resolvePending` | mismo fichero y `ClinicalProfiles.resolvePending` | lee el estado y lo compara con la operación pendiente. No escribe. Borra `notice` mientras resuelve |
| `retry()` | mismo fichero | relee o resuelve con aviso. Hoy solo lo invoca Ajustes |
| `unavailability` | mismo fichero | propiedad derivada del ADR 0016: `ProfileUnavailabilityBlock.of(gate)`, `null` como E1 |
| `MainActivity.render` | `android/.../MainActivity.kt` | síncrono. Llama a `overview.execute()` en cada repintado |
| `profile.changed` | `MainActivity.onCreate` | repinta solo si el destino actual es `SETTINGS` |
| `ScreenRenderer.details` | `android/.../ui/ScreenRenderer.kt` | cuatro `code` v1 con `R.string.technical_codes` (`%1$s\n%2$s\n%3$s\n%4$s`), oculto en cada repintado. Lo usan `BOLUS`, `MANUAL`, `OFFLINE_BOLUS` y `DIAGNOSTICS` |
| `ReadOverview` | `android/.../application/ReadOverview.kt` | perfil v1 estático `policy_not_approved` (D7). `verify.ps1` le prohíbe el perfil y los tipos v2 |
| `verify.ps1` | `scripts/verify.ps1` | lista cerrada de consumidores del traductor. `MainActivity` no construye informes v2. `ScreenRenderer` no referencia el perfil, confirmaciones ni tipos v2 |
| Elevación v1 → v2 | `InputUnavailabilityReport.from`, `InputUnavailability.from` | total sobre las 52 combinaciones v1, sin detalle, sin `@Throws` |
| Pruebas que fijan el estado actual | `ClinicalProfileUnavailabilityDeviceTest` (Bolo sigue estático), `NavigationDeviceTest` y `ClinicalProfileDeviceTest` (`blocking_codes` contiene `input.profile.policy_not_approved`) | la primera debe cambiar. Las otras siguen siendo válidas porque el nuevo informe conserva ese código |

## 3. Origen del estado del perfil en Bolo (B4)

### R1. Reutilizar `ClinicalProfileModel` (recomendada)

Bolo usa el mismo modelo y la misma propiedad `unavailability` que Ajustes.

- Un solo estado del perfil por actividad: Bolo y Ajustes nunca muestran
  lecturas distintas.
- Reutiliza el orden del `worker`, el descarte por `stateToken` y la
  resolución de operaciones pendientes ya probados.
- Refleja siempre el **estado guardado**. Un editor con cambios sin guardar
  en Ajustes no altera el informe de Bolo.

### R2. Caso de uso de lectura propio para Bolo

Segundo lector del mismo almacenamiento, con su propio estado.

- En contra: dos estados que pueden divergir (Ajustes guarda, Bolo conserva
  la lectura anterior), duplica el ciclo de vida y la resolución de
  pendientes, y saltarse `resolvePending` contradice el ADR 0014 §9.3.

## 4. Estado antes de la primera lectura (B2)

### B2-a. E1 traducido (recomendada)

Mientras no hay lectura demostrada, Bolo muestra lo mismo que Ajustes:
`input.profile.unknown` [`profile.read.pending`] y
`input.profile.policy_not_approved` [`profile.not_approved_for_calculation`].

- Al pintar Bolo se invoca `ensureLoaded()`. Si Ajustes ya leyó, no se repite
  la lectura. Si no, empieza la lectura (o la resolución de la operación
  pendiente) por el camino ya existente.
- Al terminar, el bloque muestra exactamente las causas del estado leído, sin
  conservar las de la lectura pendiente.

### B2-b. Código estático hasta la primera lectura

Mantener `policy_not_approved` sin detalle hasta tener estado.

- En contra: mezcla dos fuentes en el mismo bloque y oculta que la lectura no
  está demostrada. El estático no es falso, pero describe menos que E1.

### B2-c. Omitir el perfil hasta la primera lectura

Rechazada: un bloque de causas sin la del perfil es un fallo abierto de
información.

## 5. Combinación v1/v2 (B3)

### B3-a. Un único informe v2 (recomendada)

Se construye un `InputUnavailabilityReport` con:

1. glucosa, IOB y comida de `ReadOverview` elevadas con
   `InputUnavailability.from` (mismo `code`, sin detalle).
2. las causas del perfil del traductor, tal cual.

La causa v1 estática del perfil de `ReadOverview` **no** entra en el informe:
el traductor ya emite siempre `input.profile.policy_not_approved` con su
detalle, así que el estático queda contenido en él. Una prueba JVM fija esa
inclusión para E1 a E11.

- Formato A5 del ADR 0016: un `code` por línea, con sus detalles entre
  corchetes, en el orden del informe (por `code`, orden técnico, no
  prioridad). Las causas elevadas salen sin corchetes, igual que hoy.
- `R.string.technical_codes` deja de usarse en Bolo. Se elimina solo si B10
  se aprueba y ningún destino lo necesita. Las líneas se unen
  con salto de línea. No se añade texto para el usuario.
- La construcción no puede fallar con las entradas actuales: las causas del
  perfil y las elevadas tienen entradas distintas, así que no se unen
  detalles. Si fallara, la excepción **no** se captura (sección 6).

### B3-b. Dos bloques

Los tres códigos v1 y debajo el informe v2 del perfil.

- En contra: dos formatos en el mismo bloque técnico, y el `code` del perfil
  aparecería dos veces si se conserva el estático.

## 6. Errores (B5)

| Situación | Comportamiento propuesto |
|---|---|
| E2 lectura fallida, E3 historial inválido, E4 esquema no soportado | se muestran tal como los traduce el ADR 0015 (`persistence_failed`, `invalid`, `parse_failed` con su detalle). Bolo no ofrece reintento |
| Rechazo conocido del traductor (D11, `ProfileUnavailabilityBlock.Rejected`) | líneas de glucosa, IOB y comida elevadas, en orden, y como última línea el identificador `profile_unavailability.unreadable_reason_not_supported`. No se fabrica una causa del perfil. El estado recibido no cambia. Ningún productor actual lo genera |
| Otra excepción del traductor | no se captura. Se propaga como hoy (precisión de A4 del ADR 0016) |
| Fallo al construir el informe combinado | no se captura. Se propaga. Una prueba JVM demuestra que no ocurre con E1 a E11 y los 13 motivos de glucosa admitidos |

## 7. Reintentos y repintado (B6, B7)

- **B6. Reintentos.** Bolo nunca invoca `retry()` ni relee por su cuenta: solo
  `ensureLoaded()`. Un estado E2 a E4 permanece en Bolo hasta que el usuario
  reintente en Ajustes (enlace ya presente). Releer en cada apertura de Bolo
  multiplicaría lecturas y haría pasar el bloque por E1 en cada visita sin
  aportar consistencia, porque el único escritor local es el propio modelo.
- **B7. Repintado.** `profile.changed` repinta también cuando el destino
  actual muestra el bloque (`BOLUS`, `MANUAL`, `OFFLINE_BOLUS` y, solo si se
  aprueba la ampliación de alcance B10, `DIAGNOSTICS`), conservando la posición con
  `rememberScroll()`. Sin esto, Bolo se quedaría en E1 tras terminar la
  lectura.
- Abierto o plegado: hoy el bloque se pliega en cada repintado. Con B7 un
  resultado de lectura lo plegaría mientras se lee. Se propone
  `MainActivity.blockingDetailsOpen`, sin guardar en el estado, que sobrevive
  a repintados del mismo destino y se pliega al cambiar de destino y tras
  recreación, como A3 del ADR 0016.

## 8. Recreación (B8)

- Cambio de configuración: el `ViewModel` conserva `gate`. No hay lectura
  nueva. El bloque vuelve plegado.
- Muerte del proceso: el modelo nuevo empieza sin `gate`. Bolo muestra E1 y
  `ensureLoaded()` lee o, si `snapshot()` conservó una operación pendiente, la
  resuelve antes (solo lectura, sin escrituras). El aviso resultante se ve
  después en Ajustes, como si se hubiera abierto allí primero.
- Un resultado tardío de una lectura superada no pinta nada (`stateToken`).
- `onSaveInstanceState` no cambia.

## 9. Decisiones del propietario

Tabla de la propuesta. Las decisiones tomadas están en la sección 14.

| Id | Pregunta | Recomendación | Si no se decide |
|---|---|---|---|
| B2 | ¿Qué muestra Bolo antes de la primera lectura? | B2-a: E1 traducido y lectura con `ensureLoaded()` | Bolo mantiene el código estático |
| B3 | ¿Un informe v2 elevado o dos bloques? | B3-a: un informe, sin la causa estática del perfil | no se puede implementar |
| B4 | ¿Se retira «Bolo no lee el perfil»? | sí, acotada: Bolo solo consume `ClinicalProfileModel.unavailability` (R1) y puede iniciar lecturas con `ensureLoaded()`. `ScreenRenderer` y `ReadOverview` siguen sin perfil | Bolo mantiene el código estático |
| B5 | ¿Errores? | sección 6 | no se puede implementar |
| B6 | ¿Reintenta Bolo? | no: nunca `retry()` ni relectura propia | — |
| B7 | ¿Repintado y estado del bloque? | repintar destinos con el bloque y recordar abierto sin guardarlo | — |
| B8 | ¿Recreación? | sección 8, sin cambios de estado guardado | — |
| B9 | ¿Textos nuevos? | no: el título `technical_details` y la tarjeta «Perfil · no se usa para calcular» no cambian | — |
| B10 | ¿También Diagnóstico? | sí: `details()` es único y dos versiones del mismo bloque serían inconsistentes. **Amplía el alcance** más allá de Bolo, ver nota | solo destinos de Bolo. Diagnóstico conserva el estático |

Nota sobre B10: el ADR 0016 (B1) planificó solo Bolo. Incluir Diagnóstico
**amplía el alcance propuesto** a una pantalla que hoy no forma parte de B y
que tampoco lee el perfil. Si el propietario no lo aprueba de forma expresa,
la implementación se limita a `BOLUS`, `MANUAL` y `OFFLINE_BOLUS`, y
Diagnóstico conserva los cuatro códigos v1 con `R.string.technical_codes`.

Ninguna decisión conecta el perfil con el motor o `ReadOverview`, ni aprueba
límites, vigencia, DIA, IOB o P4 y P6 del ADR 0012.

## 10. Arquitectura y `verify.ps1` (para la implementación)

- Nuevo `android/.../ui/BlockingDetails.kt`, puro: recibe `UnavailableOverview`
  y `ProfileUnavailabilityBlock` y devuelve `List<String>`. Único punto que
  compone v1 elevado y v2. Entra en la lista cerrada de consumidores del
  traductor junto con su prueba JVM y la nueva prueba de dispositivo.
- `ScreenRenderer.render` recibe las líneas ya compuestas. Sigue prohibido
  que referencie el perfil, confirmaciones o tipos v2: la regla de
  `verify.ps1` se conserva y se reformula su mensaje («Bolo solo recibe líneas
  ya compuestas»).
- `MainActivity` llama a `ensureLoaded()` y a `BlockingDetails` con
  `profile.unavailability`. Sigue sin construir informes v2 (regla vigente).
- `ReadOverview` no cambia y su regla se conserva. Su causa estática del
  perfil queda solo como invariante comprobada, no se muestra.
- El motor y el perfil siguen sin depender del traductor.
- Nueva clase de instrumentación en la lista `-e class`.

## 11. Pruebas previstas (para la implementación)

- JVM, `BlockingDetails`: E1 a E11 combinados con estados de glucosa
  representativos. Orden por `code`. Líneas del perfil idénticas a las del
  traductor. Causa estática contenida en el informe y no duplicada. `Rejected`
  da tres líneas elevadas más el identificador, sin ninguna línea
  `input.profile.*`. Otra excepción se propaga.
  Construcción sin rechazo para los 13 motivos de glucosa.
- Dispositivo, con `profileRepositoryFactory` sintético:
  - abrir Bolo sin pasar por Ajustes: primero E1 y después exactamente las
    causas del estado leído.
  - lectura retenida por `ControlledProfileRepository`: E1 visible, sin
    reintento en Bolo.
  - lectura fallida: `persistence_failed`. Reintento en Ajustes que termina en
    otro estado: Bolo no conserva códigos anteriores.
  - sin versión, incompleta, sin confirmar, confirmada, retirada y zona
    retirada: códigos exactos. Confirmada sigue con `policy_not_approved`,
    tarjeta de perfil sin cambios y «Calcular» y «Confirmar» deshabilitados.
  - una sola lectura al alternar Bolo y Ajustes, contada en el repositorio.
  - recreación: bloque plegado y sin lectura nueva.
  - operación pendiente conservada y apertura directa en Bolo: se resuelve
    sin escrituras, contadas en el repositorio.
  - bloque abierto que sobrevive al repintado por fin de lectura.
  - Diagnóstico con las mismas líneas solo si se aprueba B10. Si no, prueba
    de que Diagnóstico conserva los cuatro códigos v1.
- Regresión: `ClinicalProfileUnavailabilityDeviceTest` deja de exigir el
  estático en Bolo. `NavigationDeviceTest` y `ClinicalProfileDeviceTest`
  siguen pasando. El bloque de Ajustes no cambia.
- iOS: no aplica. El traductor común sigue sin verificar en iOS (ADR 0004).

## 12. Consecuencias

- Bolo y Ajustes describen el perfil con el mismo informe.
- Se retira, acotada, la garantía «Bolo no lee el perfil». Lo que queda
  garantizado y comprobado: el motor, `ReadOverview` y `ScreenRenderer` no
  leen el perfil, y Bolo no escribe en él.
- Riesgo: que un usuario lea la desaparición de causas (perfil confirmado)
  como un paso hacia un bolo. Mitigación: bloque técnico plegado,
  `policy_not_approved` siempre presente, tarjeta visible y acciones sin
  cambios.
- Abrir Bolo puede iniciar la primera lectura del perfil de la sesión. Coste:
  una lectura local por proceso.
- Coste: un componente puro, cambios en `MainActivity` y `ScreenRenderer`,
  reglas de `verify.ps1`, pruebas y documentación.
- Rollback: revertir restaura el código estático. Sin efecto en datos.
- Al implementar, corregir en `unavailable-input-v2.md` la frase «Ningún
  consumidor usa el traductor todavía», ya superada por el ADR 0016.

## 13. Condiciones para revisar este ADR

Una puerta de cálculo en el motor, una aprobación clínica del perfil, un
segundo escritor del perfil (sync, importación o perfiles alternativos), un
cambio del contrato v2 o la verificación de iOS.

## 14. Decisiones del propietario (2026-10-04)

| Id | Decisión |
|---|---|
| B2 | aprobada: E1 traducido antes de la primera lectura |
| B3 | aprobada: un único informe v2, sin la causa estática del perfil |
| B4 | aprobada: retirada acotada de «Bolo no lee el perfil» mediante R1, con la precisión de abajo |
| B5 | aprobada: comportamiento ante errores de la sección 6 |
| B6 | aprobada: Bolo y Diagnóstico nunca invocan `retry()` ni releen por su cuenta |
| B7 | aprobada: repintado de los destinos con el bloque y estado abierto sin guardar |
| B8 | aprobada: recreación de la sección 8 |
| B9 | aprobada: sin textos nuevos |
| B10 | aprobada **expresamente**, incluida la ampliación de alcance a Diagnóstico |

Precisión de B4 y B6:

- `ensureLoaded()` se invoca **únicamente** al pintar las pantallas
  autorizadas que muestran este informe: Ajustes → Cálculo (como hoy) y
  `BOLUS`, `MANUAL`, `OFFLINE_BOLUS` y `DIAGNOSTICS`. Ningún otro destino
  inicia lecturas del perfil. `verify.ps1` fija los ficheros que pueden
  invocarlo y la lista cerrada de destinos.
- La resolución de operaciones pendientes que `ensureLoaded()` puede iniciar
  (`resolvePending`, ADR 0014 §9.3) es **exclusivamente de lectura**: lee el
  estado y lo compara con la operación conservada. No guarda versiones, no
  añade confirmaciones ni retiradas y no reenvía la petición pendiente.
- Bolo y Diagnóstico no invocan `retry()`, `record`, `save`, confirmaciones ni
  ninguna otra operación de escritura del modelo.

Pruebas añadidas a la sección 11 a petición del propietario:

- Cero escrituras al abrir Bolo y Diagnóstico, contadas en el repositorio de
  prueba (versiones guardadas y eventos de confirmación) y en filas de la base
  sintética, sin operaciones pendientes y con una operación pendiente
  conservada.
- Un modelo nuevo creado desde el estado guardado (muerte del proceso) pasa
  por E1 (`input.profile.unknown` [`profile.read.pending`]) antes de mostrar
  el resultado, también cuando conserva una operación pendiente, y sin
  escrituras.
- Abrir destinos sin el bloque (Inicio, Más, Escanear) no inicia lecturas del
  perfil.
