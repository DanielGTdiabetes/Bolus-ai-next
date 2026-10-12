# ADR 0024: motor candidato único y cálculo de prueba en Bolo

- Estado: aceptado para desarrollo y pruebas, según la autorización del
  propietario de 2026-10-12 (AGENTS.md §3). No aprueba ningún uso terapéutico
- Fecha: 2026-10-12
- Fase y criterio de aceptación: fase 3 (motor único con desglose, versión,
  huella y fail-closed) y fase 7 (recorrido local de cálculo con revisión), en
  modo de prueba
- Responsables de decisión: propietario. Las decisiones técnicas reversibles las
  toma el agente (EXECUTION_PLAN §3.3)
- Evidencia clínica requerida: pendiente. Las reglas son candidatas observadas
  en Legacy y no tienen aprobación clínica

## Contexto

El propietario autoriza a desarrollar y ejecutar cálculos de prueba antes de
terminar la app, también con lecturas reales, y a usar como candidatas las
reglas observadas y trazadas en Legacy (AGENTS.md §3). Los resultados no se
utilizan para tratamiento. Esta autorización no da autoridad clínica a Next.

La auditoría `docs/audit/legacy-clinical.md`, fijada en `f5417721`, describe
tres implementaciones divergentes (backend, web manual y Android Companion) y
las decisiones pendientes LC-001 a LC-012. Antes de implementar se resolvió de
nuevo `main` de Legacy: `bcac891d85e6fb3e84398c118061e6aeae3f73f1` (2026-10-09).
Entre los dos SHA, `git diff` no muestra cambios en
`backend/app/services/bolus_engine.py`, `backend/app/services/bolus_calc_service.py`,
`frontend/src/lib/manualBolusCore.js` ni
`android-companion/src/main/java/org/bolusai/companion/bolus/`.

## Decisión

### 1. Un único motor en `shared/bolus-engine`

`org.bolusai.engine.candidate.CandidateEngine` es la única implementación del
cálculo. Es puro y determinista. No usa reloj, base de datos, red ni Android.
Recibe una `CandidateRequest` con todas las entradas explícitas: instante,
minuto local resuelto por la plataforma, versión y huella del perfil,
confirmación de datos, unidad, zona, franjas, hidratos con su origen, glucosa
con valor, unidad, origen e instante de registro, IOB, paso de redondeo, bolo
máximo y corrección máxima.

Cada entrada es `Input.Provided` o `Input.Unavailable(causa)`. Una ausencia nunca
se convierte en `0` ni en un valor por defecto. La salida contiene cada paso
como `Computed` con sus hechos o `Blocked` con causas estables, el total, los
avisos, las reglas de Legacy no aplicadas, la versión del motor
(`0.2.0-candidate`), el conjunto de reglas, el SHA de Legacy y la huella SHA-256
de una codificación canónica de todas las entradas. `usableForTreatment` es
siempre `false`.

### 2. Conjunto candidato `legacy-backend-core-candidate-v1`

Procede de `backend/app/services/bolus_engine.py::_calculate_core` en
`bcac891`. Es el núcleo del camino online de producción (§2.1 de la auditoría).
Se elige de forma explícita y queda registrado. No se elige en silencio: las
divergencias con la web manual y con Android Companion siguen pendientes en
LC-006 a LC-010.

| Paso | Regla observada | Datos necesarios | Si faltan |
|---|---|---|---|
| A comida | `carbs / cr`; `0` g no necesita ratio | hidratos, ratio de la franja | bloquea comida y lo que depende |
| B corrección | `(bg - objetivo) / isf` en mg/dL | glucosa mg/dL, perfil en mg/dL, objetivo, ISF | bloquea corrección; no convierte unidades |
| B2 límite | si `corr > max_corr`, `max_corr` | corrección máxima | bloquea |
| C IOB | resta IOB solo de la corrección positiva; la corrección negativa reduce la comida; `max(0, …)` | IOB | IOB desconocido bloquea; nunca `0` |
| D redondeo | `round(x / paso) * paso`, mitad al par como Python | paso > 0 | bloquea |
| E bolo máximo | si `total > max`, `max` | bolo máximo | bloquea |
| F parada | glucosa `< 70 mg/dL` → total `0` | glucosa mg/dL | el aviso aparece aunque el total esté bloqueado |

Diferencias deliberadas con Legacy, todas más restrictivas:

- Legacy sustituye `cr <= 0.1` por `10` e `isf <= 5` por `30`. Next bloquea con
  `profile.carb_ratio.invalid_for_candidate` o
  `profile.sensitivity.invalid_for_candidate`.
- Legacy calcula sin glucosa y sin IOB (IOB `0`). Next bloquea lo que depende de
  ellos (LC-010, LC-011).
- Legacy aplica la vigencia de 10 min antes de corregir. No hay política aprobada
  (ADR 0023, C5), así que Next no la aplica: avisa
  `glucose.freshness_policy_not_approved` y muestra la antigüedad desde la
  recepción.
- Autosens, fibra, Warsaw, ejercicio, Techne, bolo dual, techo de IOB y aviso de
  apilamiento no se aplican, y el resultado lo declara en `notApplied`.

La parada de 70 mg/dL es un literal de Legacy que solo existe en el backend y en
la web (LC-007). Se incluye porque es la variante más prudente del camino
elegido. Sigue pendiente de aprobación.

### 3. Vectores capturados de Legacy

`scripts/legacy-vectors/capture_candidate_vectors.py` carga
`_calculate_core` desde una copia local separada de Legacy en el SHA fijado. Sustituye solo dos importaciones de modelos
que la función no usa y la evalúa con entradas sintéticas, con el resto de
funciones de Legacy desactivadas explícitamente. No importa ajustes, bases de
datos ni clientes, y no escribe en Legacy. El resultado se guarda en
`CandidateLegacyVectors.kt` con los bits exactos de cada `double`. `CandidateEngineTest`
exige igualdad exacta en comida, corrección limitada, IOB aplicado y total para
los 12 vectores, incluidos el redondeo a par (`2,5 → 2`, `3,5 → 4`), el límite de
corrección, el bolo máximo, la parada y la corrección negativa. Son vectores de
paridad observada, no golden vectors aprobados clínicamente.

### 4. Recorrido Android de prueba en Bolo

`application/TestCalculation` traduce a una `CandidateRequest`, sin calcular, lo
siguiente:

- perfil: la última versión guardada, con su huella. `dataConfirmed` solo si su
  confirmación está activa (ADR 0014). El minuto local se resuelve con la zona
  IANA del perfil, y una zona ausente o desconocida bloquea la selección de franja;
- hidratos: se introducen a mano o se toman de la comida seleccionada en Bolo,
  solo si la revisión es la vigente y su base es gramos totales;
- glucosa: se introduce a mano en mg/dL o se toma de la última lectura Dexcom
  que la presentación muestra con número (ADR 0023). En ese caso conserva su
  origen, instante de recepción y hora de medida no verificada;
- IOB: manual. Vacío significa desconocido;
- paso, bolo máximo y corrección máxima: **sustituido por el
  [ADR 0025](0025-profile-calculation-limits.md)**. Ahora proceden de la versión
  del perfil (esquema de contenido 2) y no se teclean en cada cálculo. Una versión
  de esquema 1 los deja desconocidos y bloquea solo los pasos que los necesitan.

La pantalla muestra «PRUEBA · NO UTILIZAR PARA TRATAMIENTO», el total o su
bloqueo, cada paso con sus hechos o causas, los avisos, las reglas no aplicadas,
la identidad del motor y la huella. El resultado solo vive en memoria: no se
registra, confirma ni sincroniza. Cualquier cambio en el formulario lo descarta.
Confirmar y registrar siguen deshabilitados. `ReadOverview` sigue sin glucosa ni
perfil y la ruta terapéutica permanece bloqueada.

## Alternativas consideradas

- **Elegir la variante de Android Companion.** Descartada: resta IOB de los
  hidratos y no tiene parada por hipoglucemia (LC-006, LC-007), así que es menos
  prudente que el backend.
- **Implementar solo componentes sin total.** Descartada: la autorización pide
  resultados de prueba completos cuando hay datos, y el bloqueo por componente ya
  impide un total con entradas ausentes.
- **Ampliar ahora el esquema del perfil con límites.** Pospuesta en esta entrega
  y resuelta después en el ADR 0025, sin valores por defecto.
- **Fijar a mano los valores esperados.** Descartada por AGENTS.md §9: se
  capturan de Legacy en el SHA fijado.

## Consecuencias

- Android y un futuro iOS comparten el mismo motor KMP. `verify.ps1` exige que
  `commonMain` del motor no importe Android, JVM, Dexcom ni comidas.
- La precisión es la de IEEE `double`, la misma que en Legacy. La interfaz
  formatea con tres decimales, lo que no es un redondeo clínico.
- Rollback: quitar el bloque de prueba de Bolo. El motor no tiene estado
  persistido.

## Seguridad y pruebas

- `CandidateEngineTest` (17 pruebas): paridad exacta con 12 vectores de
  Legacy; bloqueos por glucosa, IOB, hidratos, perfil, unidades, franjas y
  límites ausentes; guardas de ratio y sensibilidad; parada; huella
  determinista; SHA-256 con vectores estándar.
- `TestCalculationTest` (8): perfil confirmado o no, zona, campos vacíos
  frente a inválidos, glucosa Dexcom solo con número y con sus limitaciones,
  hidratos de la comida, descarte al cambiar el formulario y formato no clínico.
- Queda pendiente la aprobación clínica de cada regla (LC-006 a LC-011) y de los
  límites. El cálculo de prueba en el Pixel tampoco está hecho todavía.
