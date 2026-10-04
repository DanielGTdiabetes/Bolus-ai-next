# ADR 0016: primer consumidor del informe de indisponibilidad del perfil

- Estado: **aceptado** el 2026-10-04 con A1 a A6 según su recomendación, A4
  precisada y B1 solo como planificación (sección 10), e integrado como
  documentación mediante la PR #38. Opción A **implementada e integrada** en
  `main` mediante la PR #39 (merge commit `c583e6a`, sección 11). Bolo y el
  motor quedan fuera.
- Fecha: 2026-10-04.
- Fase y criterio de aceptación: fases 2 y 6. Decide si el informe v2 del
  perfil ([ADR 0015](0015-profile-unavailability-contract.md)) se conecta con
  algún consumidor, con cuál y con qué límites. Es la condición que el ADR 0014
  (C7) y el ADR 0015 (§11) exigen antes de conectar la puerta del perfil.
- Responsables de decisión: propietario del proyecto.
- Evidencia clínica requerida: no aplica. No se introduce ninguna regla,
  límite, vigencia, valor por defecto ni conversión.
- Base Next: `464350f2013360a0527a1affe4ed95ff39190fcd` (PR #37 integrada).
  CI del SHA exacto:
  [Verify 37143225634](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37143225634),
  evento `push`, conclusión `success`.
- Legacy: no se consulta.
- Relacionados: ADR 0007 (navegación), ADR 0012, ADR 0014, ADR 0015 y
  [`unavailable-input-v2`](../contracts/unavailable-input-v2.md).

Invariantes que ninguna opción modifica:

- `allowsCalculation = false` y `allowsTreatment = false` en todo estado.
  `policy_not_approved` acompaña siempre al perfil.
- El motor no lee el perfil ni el informe. No hay fórmulas, límites, vigencia,
  DIA, IOB ni conversiones.
- El consumidor muestra causas ya determinadas. No decide nada, no las ordena
  por importancia y no las convierte en un permiso.
- Sin cambios de SQLite, huellas, serialización, confirmaciones ni contrato v2.

## 1. Problema

El traductor `ProfileUnavailability.report` existe y está probado, pero
`verify.ps1` impide que Android lo use. Hoy:

- Ajustes → Cálculo presenta E1 a E11 con los textos del ADR 0014, sin los
  códigos del contrato.
- Bolo muestra en «Detalles del bloqueo» cuatro códigos v1. El del perfil es
  `input.profile.policy_not_approved` estático (D7 del ADR 0015) y no refleja
  el estado real: una lectura fallida o una confirmación retirada no aparecen.

Sin consumidor, el traductor solo se comprueba en JVM y su salida no se
valida en dispositivo. Pero conectar el perfil con Bolo rompe una garantía
vigente desde el ADR 0012: Bolo no lee el perfil.

## 2. Evidencia

| Elemento | Ubicación | Qué implica |
|---|---|---|
| `ClinicalProfileModel.gate` | `android/.../ui/ClinicalProfileModel.kt` | ya mantiene el `ProfileGateState` leído en segundo plano, con `null` mientras lee (`reading = true`) |
| `ClinicalProfileScreen.render` | `android/.../ui/ClinicalProfileScreen.kt` | presenta E1 a E11. No muestra códigos de contrato |
| `ReadOverview` | `android/.../application/ReadOverview.kt` | síncrono, sin perfil. `verify.ps1` le prohíbe referenciar el perfil y los tipos v2 |
| `ScreenRenderer.details` | `android/.../ui/ScreenRenderer.kt` | «Detalles del bloqueo»: cuatro `code` v1 en texto seleccionable, oculto por defecto. `verify.ps1` le prohíbe el perfil y los tipos v2 |
| `MainActivity.render` | `android/.../MainActivity.kt` | llama a `overview.execute()` de forma síncrona en cada render. La lectura del perfil es asíncrona |
| `verify.ps1` | `scripts/verify.ps1` | ningún fuente o build de `android/` puede usar `profile-unavailability` |
| Elevación v1 → v2 | `InputUnavailabilityReport.from` | total: glucosa, IOB y comida pueden convivir con el perfil v2 en un solo informe |

## 3. Opciones

### A. Pantalla del perfil, detalle técnico (recomendada)

Ajustes → Cálculo añade un bloque plegado «Detalles del bloqueo» con el
informe v2 del estado que ya presenta: un `code` por línea con su detalle.

- Fuente: `ClinicalProfileModel.gate`. Mientras `reading`, el estado traducido
  es `ProfileGateState.ReadPending` (E1), como ya muestra la pantalla. Sin
  lecturas nuevas.
- La traducción vive en el modelo de presentación, no en la vista, y se
  recalcula solo cuando cambia `gate`.
- Bolo, `ReadOverview`, el motor y `ScreenRenderer` no cambian.
- A favor: primer consumidor real con riesgo mínimo. La pantalla ya lee el
  perfil. Valida en dispositivo la salida del traductor para E1 a E11 con
  repositorios sintéticos.
- En contra: Bolo sigue mostrando un código estático para el perfil.

### B. Bolo, detalle técnico

«Detalles del bloqueo» de Bolo muestra el informe v2 real del perfil.

- Exige que Bolo obtenga el estado del perfil: reutilizar
  `ClinicalProfileModel` (hoy creado para Ajustes) o un caso de uso de lectura
  propio. Cualquiera de los dos deroga la regla «Bolo no lee el perfil» del
  ADR 0012 y el ADR 0014, y cambia `verify.ps1`.
- La lectura es asíncrona y `render` es síncrono: Bolo pasaría por E1 en cada
  apertura. Hay que decidir qué se muestra antes de la primera lectura (B2).
- Mezcla v1 y v2: o se elevan glucosa, IOB y comida a v2 en un solo informe, o
  se muestran dos bloques (B3).
- A favor: el detalle de Bolo dejaría de ser estático.
- En contra: más superficie (ciclo de vida, recreación, lectura repetida), una
  garantía de seguridad retirada y el riesgo de que el usuario interprete
  causas del perfil en la pantalla de cálculo como pasos hacia un bolo.

### C. A y después B

A en este incremento y B en otro, con su propia decisión sobre B2 y B3, tras
validar A en dispositivo.

### D. No conectar todavía

Mantener el traductor sin consumidores hasta el ADR de la puerta de cálculo
del motor. Coste nulo. El traductor solo se valida en JVM.

## 4. Recomendación

**Opción C, implementando ahora solo A.** Precisiones:

- Un único punto de traducción: una propiedad derivada del modelo de
  presentación (`ClinicalProfileModel`), sin estado propio ni persistencia.
- Si el traductor rechaza el estado (D11 del ADR 0015), la pantalla muestra el
  identificador `profile_unavailability.unreadable_reason_not_supported` en el
  bloque técnico y no se cierra el proceso. Ningún productor actual lo genera.
- Solo códigos de contrato. No se añaden mensajes nuevos para el usuario: los
  textos de E1 a E11 del ADR 0014 siguen siendo la explicación visible.
- Bloque plegado por defecto, texto seleccionable, sin copiar al portapapeles
  ni exportar. Sin telemetría.
- B queda fuera y exige su propio ADR o una revisión de este.

## 5. Cambios de reglas de arquitectura (`verify.ps1`)

Para A:

- Se permite `profile-unavailability` en `android/app/build.gradle.kts` y en
  `ClinicalProfileModel.kt` y `ClinicalProfileScreen.kt`. Cualquier otro fuente
  de `android/` o de `shared/meal-drafts` sigue rechazado.
- `ReadOverview` y `ScreenRenderer` siguen sin referenciar el perfil ni los
  tipos v2.
- El motor y el perfil siguen sin depender del traductor.

## 6. Pruebas previstas (para la implementación)

- JVM (Android): la propiedad derivada del modelo devuelve el informe del
  traductor para E1 (lectura en curso) a E11, y el identificador estable
  cuando el traductor rechaza.
- Dispositivo: Ajustes → Cálculo con repositorios sintéticos (`profileRepositoryFactory`):
  bloque plegado por defecto, códigos exactos para sin perfil, incompleta,
  completa sin confirmar, confirmada, retirada y zona retirada, y lectura
  fallida con `ControlledProfileRepository`, historial inválido y esquema no
  soportado como ya provocan las pruebas de confirmación. Recreación conserva el estado del
  bloque solo si el propietario lo decide (A3).
- Regresión: Bolo sigue mostrando `input.profile.policy_not_approved`
  estático. `NavigationDeviceTest` sin cambios. Cálculo y confirmación siguen
  deshabilitados.
- `verify.ps1`: las nuevas reglas de la sección 5, con su lista de ficheros.
- iOS: no aplica. A es una pantalla Android. El traductor común sigue sin
  verificar en iOS (ADR 0004).

## 7. Consecuencias

- El traductor gana un consumidor real y validación en dispositivo.
- La garantía «Bolo no lee el perfil» se mantiene.
- Coste: una propiedad derivada, un bloque de UI, recursos de texto para el
  título del bloque, pruebas y la regla de `verify.ps1`.
- Rollback: revertir elimina un bloque de presentación sin efecto en datos.

## 8. Decisiones del propietario

| Id | Pregunta | Recomendación | Si no se decide |
|---|---|---|---|
| A1 | ¿Qué consumidor? | C, implementando ahora solo A | D: sin consumidor |
| A2 | ¿Dónde se traduce? | propiedad derivada en `ClinicalProfileModel`, nunca en la vista | — |
| A3 | ¿El bloque recuerda si estaba abierto tras recreación? | no: siempre plegado al abrir, como el detalle de Bolo | — |
| A4 | ¿Qué se muestra si el traductor rechaza? | el identificador estable en el bloque técnico, sin cerrar el proceso | no se puede implementar sin decidir |
| A5 | ¿Formato del bloque? | un `code` por línea seguido de sus detalles entre corchetes, en el orden del informe | — |
| A6 | ¿Se añade texto explicativo nuevo? | no: solo el título del bloque. La explicación sigue en los textos de E1 a E11 | — |
| B1 | ¿Se planifica B? | sí, como ADR propio tras validar A, con B2 (estado antes de la primera lectura) y B3 (un informe v2 elevado o dos bloques) | Bolo mantiene el código estático |

Ninguna decisión conecta el perfil con el motor, `ReadOverview` o Bolo, ni
aprueba límites, vigencia, DIA, IOB o P4 y P6 del ADR 0012.

## 9. Condiciones para revisar este ADR

La conexión de Bolo (B), una puerta de cálculo en el motor, una aprobación
clínica del perfil, un cambio del contrato v2 o la verificación de iOS.

## 10. Decisiones del propietario (2026-10-04)

| Id | Decisión |
|---|---|
| A1 | aprobada: opción C, implementando ahora solo A (pantalla del perfil) |
| A2 | aprobada: propiedad derivada en `ClinicalProfileModel`, nunca en la vista |
| A3 | aprobada: el bloque se abre siempre plegado |
| A4 | aprobada **con precisión**, ver abajo |
| A5 | aprobada: un `code` por línea con sus detalles entre corchetes, en el orden del informe |
| A6 | aprobada: sin textos explicativos nuevos, solo el título del bloque |
| B1 | aprobada **solo como planificación** de un ADR posterior. No autoriza conectar Bolo |

Precisión de A4:

- Se captura **únicamente** el rechazo conocido del traductor:
  `IllegalArgumentException` con el mensaje exacto
  `profile_unavailability.unreadable_reason_not_supported`.
- El bloque muestra ese identificador estable. No se fabrica un informe válido
  ni una causa sustitutiva.
- Los bloqueos de cálculo y tratamiento se conservan: el estado del perfil no
  cambia y `allowsCalculation` y `allowsTreatment` siguen en `false`.
- Cualquier otra excepción no se captura ni se oculta: se propaga como hoy.

Pruebas añadidas a la sección 6 a petición del propietario:

- Transición de lectura pendiente al resultado: el bloque muestra primero
  `input.profile.unknown` [`profile.read.pending`] y después exactamente las
  causas del estado leído, sin conservar las de la lectura pendiente.
- Reintento tras un error: tras una lectura fallida (E2) y un reintento que
  pasa por la lectura pendiente y termina en otro estado, el bloque nunca
  conserva códigos del estado anterior.

## 11. Estado de implementación (2026-10-04)

Base: `14093c3e51a87b36031007a8d84f950f8665a51c` (PR #38 integrada).

| Elemento | Implementación |
|---|---|
| A2 | `ProfileUnavailabilityBlock` (puro, `android/.../ui`) y la propiedad derivada `ClinicalProfileModel.unavailability`, recalculada en cada acceso desde `gate`. `gate` nulo se traduce como E1, igual que lo presenta la pantalla |
| A3 | `MainActivity.profileDetailsOpen`, sin guardar en el estado: el bloque sigue abierto mientras la pantalla se repinta, se pliega al cambiar de sección o destino y tras recreación |
| A4 | solo se captura `IllegalArgumentException` con el mensaje `profile_unavailability.unreadable_reason_not_supported`. Se muestra ese identificador. Cualquier otra excepción se propaga. El estado recibido no cambia |
| A5 | un `code` por línea, con sus detalles entre corchetes separados por coma, en el orden del informe |
| A6 | título del bloque `R.string.technical_details` (el de Bolo). Sin textos nuevos |
| Pantalla | `ClinicalProfileScreen.current()` añade el bloque en todos los estados de la vista principal (E1 a E11). Revisión, editor e historial no lo muestran |
| `verify.ps1` | lista cerrada de ficheros de Android que pueden usar el traductor. `MainActivity` no construye informes v2. `ReadOverview` y `ScreenRenderer` siguen sin perfil ni tipos v2. Nueva clase de instrumentación en la lista |

Bolo, `ReadOverview`, el motor, SQLite, el contrato y las confirmaciones no
cambian. Cálculo y tratamiento siguen bloqueados en todos los estados.

Integración: el propietario aprobó la implementación en
`a267f67f3e3e834b317d2a17dd5f6fe6f2be3ed9` (Windows verification
[37177046158](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37177046158)
y GitGuardian en verde). La PR #39 se integró el 2026-10-04 mediante merge
commit `c583e6a3cdeb34fb4ddaf75bbcffdc620c629f0b`, comprobando esa cabeza exacta. CI del merge commit en `main`:
[Verify 37177820636](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37177820636), evento `push`, conclusión `success`.

Evidencia: [validación](../validation/profile-unavailability-screen-2026-10-04.md).
