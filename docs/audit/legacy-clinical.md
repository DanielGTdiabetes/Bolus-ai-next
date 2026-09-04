# Auditoría clínica de Bolus AI Legacy

## 1. Snapshot, alcance y clasificación

- Repositorio: `https://github.com/DanielGTdiabetes/bolus_ai`
- Rama resuelta: `main`
- SHA fijado: `f5417721d8019a9831126f4d843edfc4de87653d`
- Commit: `2026-08-31T06:55:40+02:00 — Add Neon auth schema regression tests`
- Fecha de auditoría: 2026-09-04
- Lote: cálculo de bolo, perfil, IOB y glucosa/Dexcom G7 local
- Clasificación de todos los hallazgos: **observado**, no aprobado clínicamente

El SHA se resolvió con `git ls-remote` y se inspeccionó en una copia temporal
detached cuyo push de `origin` quedó configurado como `DISABLED`. No se
ejecutaron aplicaciones, tests, migraciones, clientes remotos, webhooks,
tratamientos ni escrituras de Legacy.

Respecto del snapshot anterior
`3182dac25f7ecc60a5da4d6ec61d08b60f7f3834`, solo cambian
`backend/app/core/db.py`, `backend/app/services/auth_repo.py` y
`backend/tests/test_auth_repo_schema.py`. Por tanto, los archivos clínicos de
este lote no cambiaron entre ambos SHAs; las afirmaciones de este documento
quedan fijadas al SHA nuevo.

Este lote no crea golden vectors: ningún comportamiento descrito tiene todavía
aprobación clínica registrada. Fabricar esperados a partir del código observado
confundiría paridad histórica con validez clínica.

## 2. Caminos ejecutables observados

### 2.1 Cálculo online

```text
frontend/src/pages/BolusPage.jsx::BolusPage
  -> frontend/src/hooks/useBolusCalculator.js::useBolusCalculator
  -> frontend/src/lib/onlineBolusPayload.js::buildOnlineBolusPayload
  -> frontend/src/lib/api.ts::calculateBolusWithOptionalSplit
  -> frontend/src/lib/api.ts::calculateBolus
  -> POST /api/bolus/calc
  -> backend/app/api/bolus.py::calculate_bolus_stateless
  -> backend/app/services/bolus_calc_service.py::calculate_bolus_stateless_service
       -> backend/app/services/glucose_source_service.py::resolve_current_glucose
       -> backend/app/services/iob.py::compute_iob_from_sources
       -> backend/app/services/iob.py::compute_cob_from_sources
       -> backend/app/services/bolus_engine.py::calculate_bolus_v2
       -> backend/app/services/bolus_engine.py::_calculate_core
```

La UI online deja CR, ISF, objetivo, DIA, curva, límites y redondeo en el
servidor. Sí envía comida, glucosa manual opcional, slot, ejercicio, alcohol y
overrides explícitos de autosens/estrategia dual.

### 2.2 Calculadora manual web

```text
frontend/src/pages/ManualCalculatorPage.jsx::ManualCalculatorPage
  -> frontend/src/lib/manualBolusCore.js::calculateManualBolus
```

Es una tercera implementación local, separada del backend y deliberadamente más
pequeña. Exige glucosa, hidratos, objetivo, ISF, ICR e IOB, pero aporta defaults
propios para bolo máximo y paso de redondeo.

### 2.3 Calculadora offline Android Companion

```text
android-companion/src/main/java/org/bolusai/companion/MainActivity.kt::BolusScreen
  -> android-companion/src/main/java/org/bolusai/companion/bolus/BolusProfileRepository.kt::current/save
  -> android-companion/src/main/java/org/bolusai/companion/bolus/BolusProfile.kt::isSynced
  -> android-companion/src/main/java/org/bolusai/companion/bolus/BolusCalculator.kt::calculate
```

La pantalla impide calcular si `configHash` está vacío o `updatedAt` es nulo.
Una vez superada esa comprobación, campos vacíos de hidratos, fibra e IOB se
convierten en `0.0`. El calculador conserva además fallbacks internos de CR, CF,
objetivo y redondeo.

## 3. Perfil y parámetros clínicos

### Evidencia y contrato observado

| Fuente y símbolo | Comportamiento observado |
|---|---|
| `backend/app/models/settings.py::UserSettings` | esquema `2`, unidad fija `mg/dL`, zona `Europe/Madrid`, CR/CF/objetivos/DIA/curva/límites/redondeo y múltiples reglas con defaults |
| `UserSettings::migrate` | transforma formatos anteriores; puede invertir CR menores de `2` y convierte exactamente `1.0` en `10.0` |
| `UserSettings::compute_hash` | SHA-256 de un subconjunto ordenado de configuración; no incluye todos los límites que usa el motor |
| `backend/app/services/store.py::load_settings` | crea un JSON con `UserSettings.default()` cuando falta el archivo y luego migra el resultado |
| `backend/app/services/bolus_calc_service.py::calculate_bolus_stateless_service` | precedencia: settings inyectados en request, overrides planos, DB y finalmente almacén JSON/defaults |
| `android-companion/src/main/java/org/bolusai/companion/bolus/BolusProfile.kt::BolusProfile` | perfil local con defaults completos; `slot()` cae a snack y luego a literales |

### Defaults y transformaciones que Next no puede portar

Se observaron defaults para CR, CF/ISF, objetivos, DIA, curva/pico, zona horaria,
vigencia de glucosa, límites, redondeo, fibra, Warsaw, ejercicio y autosens. Hay
además defaults distintos según el camino: el modelo de settings, el adaptador
stateless, el núcleo matemático, Android y la calculadora manual no usan siempre
los mismos literales.

El hash Legacy aporta trazabilidad parcial, pero omite al menos
`max_bolus_u`, `max_correction_u`, `max_iob_u`, `min_bolus_interval_min` y
`round_step_u`, aunque esos campos afectan el resultado. Tampoco identifica una
versión inmutable del perfil histórico.

### Decisión Next

**Bloquear.** El contrato Next deberá exigir versión inmutable, unidad, zona,
vigencia, periodos completos y cada parámetro clínico requerido. Ausencia,
migración ambigua o hash incompleto no se sustituirán por `UserSettings.default`,
slot snack ni literales. La política de migración y los límites necesitan
aprobación separada.

## 4. Glucosa y Dexcom G7 local

### Recepción Android observada

| Fuente y símbolo | Inputs/validación/estado |
|---|---|
| `android-companion/src/main/AndroidManifest.xml::.dexcom.GlucoseReceiver` | receiver exportado para `com.dexcom.cgm.EXTERNAL_BROADCAST`; declara `com.dexcom.cgm.EXTERNAL_PERMISSION` como permiso usado, pero el receiver no declara `android:permission` |
| `android-companion/src/main/java/org/bolusai/companion/dexcom/GlucoseReceiver.kt::onReceive` | exige action, flag local, extras `sensorType == G7` y `packageName == com.dexcom.g7`; acepta bundle directo o colección de bundles |
| `android-companion/src/main/java/org/bolusai/companion/dexcom/GlucoseReading.kt::isValid` | acepta glucosa `1..400` y timestamp positivo; no valida futuro, antigüedad ni unidad en el límite Android |
| `android-companion/src/main/java/org/bolusai/companion/dexcom/GlucoseReading.kt::dedupeKey` | usa UID o `source:session:sequence:timestamp:value` |
| `android-companion/src/main/java/org/bolusai/companion/dexcom/GlucoseQueueRepository.kt::GlucoseQueueRepository` | persiste cola y última lectura en SharedPreferences, deduplica, ordena y limita a 2.016 elementos; `latest` rechaza futuro y antigüedad mediante un límite aportado por el caller |

El extra `packageName` es contenido de la intención, no evidencia por sí mismo de
la identidad del emisor. La autenticidad efectiva del broadcast y el contrato
autorizado de Dexcom deben demostrarse antes de reutilizar esta integración.

### Normalización y selección backend observadas

`backend/app/services/glucose_ingest_service.py::validate_ingest` reconoce fuentes explícitas, rechaza
valor fuera de `1..400`, timestamp más de cinco minutos en el futuro, más de
siete días de antigüedad, `display_only` y estados de sensor bloqueados. Marca
histórico al superar quince minutos y solo permite dosificación entre `40..400`,
con timestamp cierto y fuente elegible.

`backend/app/services/glucose_source_service.py::resolve_current_glucose` selecciona fuentes mediante
modo configurado, flags, prioridad y fallback. Aplica otra política de frescura,
por defecto diez minutos, detecta conflicto solo cuando dos fuentes discrepan en
el mismo segundo y devuelve `ok`, `stale`, `conflict` o `unavailable`. Usa el
reloj global varias veces durante una misma resolución.

En el caso de uso de bolo, una lectura automática no utilizable se conserva para
presentación pero se transforma en `mgdl=None` antes del motor. Glucosa ausente o
inválida no bloquea toda la recomendación: Legacy calcula la parte de comida sin
corrección y añade un aviso. Una glucosa manual sí entra, pero su contrato no
incluye timestamp de medida/recepción, identidad ni vigencia.

### Pruebas observadas y huecos

`backend/tests/test_glucose_sources.py`,
`backend/tests/test_mobile_glucose_entry.py` y
`backend/tests/test_bolus_glucose_validation.py` cubren persistencia,
idempotencia, continuidad watch no dosificable, conflicto en el mismo segundo,
backfill, selección/fallback y exclusión de lecturas automáticas no utilizables.
`android-companion/src/test/java/org/bolusai/companion/dexcom/GlucoseQueueCodecTest.kt`,
`android-companion/src/test/java/org/bolusai/companion/worker/GlucoseQueueDrainerTest.kt`,
`android-companion/src/test/java/org/bolusai/companion/worker/GlucoseSyncResultPolicyTest.kt`
y
`android-companion/src/test/java/org/bolusai/companion/worker/GlucoseSyncWakePolicyTest.kt`
cubren codec, drenado y políticas de sync, pero no se encontró una prueba del
receiver que demuestre identidad real del emisor, timestamp futuro, unidad
desconocida o estado explícito de permiso ausente.

### Decisión Next

**Migrar solo el conocimiento del contrato; corregir y bloquear.** Next debe
separar recepción, validación, persistencia local y sync; usar reloj inyectado;
conservar timestamps de medida y recepción; representar permiso/origen/futuro/
caducidad/conflicto como estados; y no considerar confiable el extra de package.
La vigencia y el rango clínico quedan pendientes de aprobación. Una glucosa no
validada bloqueará la recomendación afectada conforme a `AGENTS.md`.

## 5. IOB e historial de insulina

### Fuentes, identidad y cálculo observados

`backend/app/services/iob.py::_load_iob_sources` reúne tratamientos de DB local, eventos JSON locales
y Nightscout dentro de una ventana `DIA + 1 hora`. Filtra basal por texto,
convierte timestamps sin zona a UTC y exige identidad estable en registros
Nightscout. `_merge_unique_boluses` deduplica por IDs/alias y usa timestamp+dosis
exactos solo cuando interviene un registro Legacy sin identidad.

`backend/app/services/iob.py::compute_iob_from_sources` construye el perfil desde DIA, curva y pico, mezcla
fuentes y calcula contribuciones. Un fallo de DB o Nightscout produce `stale` si
hay caché y `unavailable` si no la hay; la caché nunca se entrega como IOB actual.
Un fallo de eventos JSON locales produce `partial` y el valor calculado sigue
adelante. Fuentes vacías sin error se interpretan como IOB conocido de `0 U`.

Los eventos sin timestamp válido o con unidades no positivas se omiten. No hay
una prueba explícita de completitud de toda la ventana ni detección de huecos.
Timestamps futuros no se rechazan aquí. Para bolos extendidos se discretiza la
dosis y se cuenta la parte aún no entregada como IOB completo; el propio código
documenta incertidumbre sobre esa semántica.

El caso de uso bloquea `unavailable` y `stale` con códigos HTTP estables y exige
IOB manual para continuar. Sin embargo, `partial` entra en el motor. La
confirmación de estado no basta sola: si no se aporta `manual_iob_u`, también se
bloquea. Esta protección observada es valiosa, pero no constituye aprobación de
la semántica del IOB manual.

### Pruebas observadas y huecos

`backend/tests/test_iob.py` cubre curvas básicas, monotonicidad, mezcla de bolos,
identidades, deduplicación exacta, fallo de fuente distinto de cero conocido y
rechazo de un tratamiento Nightscout sin identidad. Faltan pruebas que demuestren
historial completo, evento futuro, conflicto de IDs, cambio de perfil dentro de
la ventana, suficiencia del estado `partial`, reinicio/corrupción de caché y la
semántica aprobada de bolos extendidos.

### Decisión Next

**Bloquear hasta demostrar integridad.** IOB desconocido, parcial, conflictivo o
basado en historial insuficiente no se convertirá en cero. Cada resultado deberá
enlazar eventos, perfil/versiones, curva, instante y ventana comprobada. DIA,
curva, pico, bolos extendidos e IOB manual requieren aprobación y vectores.

## 6. Fórmulas observadas y divergencias

Los siguientes comportamientos son evidencia del sistema actual, no reglas para
Next:

| Tema | Backend Python | Manual web | Android Companion | Estado Next |
|---|---|---|---|---|
| IOB | resta solo de corrección positiva | igual que backend | resta del total, incluidos hidratos | discrepancia bloqueante |
| Glucosa ausente | permite bolo de comida con aviso | no permite calcular | permite cálculo con aviso | decisión pendiente; Next fail-closed |
| Glucosa bajo objetivo | corrección negativa reduce comida; bajo 70 fuerza total cero | igual, con hard stop bajo 70 | no añade corrección negativa y no tiene hard stop | discrepancia bloqueante |
| CR/ISF/objetivo inválidos | sustituye CR/ISF y resuelve objetivo con fallbacks | rechaza fuera de rangos propios | sustituye los tres con literales | no portar defaults |
| Fibra | deduce si `fiber > threshold`; caso fibra >= hidratos no deduce | no implementa | deduce si `fiber >= threshold` | discrepancia pendiente |
| Redondeo | `round(value/step)` de Python, componentes por separado | `Math.round`, total combinado | `kotlin.math.round`, total combinado | vector de bordes obligatorio |
| Límite máximo | recorta el total y componentes con lógica propia | `min(total, maxBolus)` | `min(total, maxBolus)` | parámetro y semántica pendientes |
| Reglas avanzadas | autosens, Warsaw, ejercicio, Techne, dual | excluidas | excluidas | no implementar sin aprobación |

El núcleo backend también sustituye CR/ISF inválidos en vez de fallar, convierte
intensidad de ejercicio desconocida en moderada y permite continuar sin glucosa.
Su respuesta ofrece componentes y `config_hash`, pero no versión del motor ni
huella completa e inmutable de inputs/perfil.

Tests como `backend/tests/test_bolus_v2.py`,
`backend/tests/test_target_resolution.py`,
`backend/tests/test_bolus_request_validation.py`,
`frontend/tests/manualBolusCore.test.js` y
`android-companion/src/test/java/org/bolusai/companion/bolus/BolusCalculatorTest.kt`
preservan estas variantes. Son evidencia de divergencia, no golden vectors
aprobados.

## 7. Matriz de decisiones pendientes

| ID | Requisito o discrepancia | Evidencia Legacy | Vector | Aprobación | Decisión actual |
|---|---|---|---|---|---|
| LC-001 | perfil completo, versionado y sin defaults | `backend/app/models/settings.py::UserSettings`; `android-companion/src/main/java/org/bolusai/companion/bolus/BolusProfile.kt::BolusProfile` | no creado | pendiente | bloquear |
| LC-002 | unidad, rango y vigencia de glucosa | `backend/app/services/glucose_ingest_service.py::validate_ingest`; `backend/app/services/glucose_source_service.py::resolve_current_glucose` | no creado | pendiente | contrato explícito; bloquear |
| LC-003 | autenticidad y permiso del broadcast G7 | `android-companion/src/main/AndroidManifest.xml::.dexcom.GlucoseReceiver`; `android-companion/src/main/java/org/bolusai/companion/dexcom/GlucoseReceiver.kt::onReceive` | no aplica aún | técnica pendiente | no confiar en extra de package |
| LC-004 | integridad requerida del historial IOB | `backend/app/services/iob.py::_load_iob_sources`; `backend/app/services/iob.py::compute_iob_from_sources` | no creado | pendiente | bloquear `partial`/incompleto |
| LC-005 | DIA, curva, pico y bolo extendido | `backend/app/services/iob.py::compute_iob_from_sources`; `backend/app/services/math/curves.py::InsulinCurves` | no creado | pendiente | bloquear |
| LC-006 | IOB solo contra corrección positiva | `backend/app/services/bolus_engine.py::_calculate_core`; `frontend/src/lib/manualBolusCore.js::calculateManualBolus`; `android-companion/src/main/java/org/bolusai/companion/bolus/BolusCalculator.kt::calculate` | no creado | pendiente | resolver divergencia |
| LC-007 | corrección negativa e hipoglucemia | `backend/app/services/bolus_engine.py::_calculate_core`; `frontend/src/lib/manualBolusCore.js::calculateManualBolus`; `android-companion/src/main/java/org/bolusai/companion/bolus/BolusCalculator.kt::calculate` | no creado | pendiente | resolver divergencia |
| LC-008 | fibra/Warsaw/dual | `backend/app/services/bolus_engine.py::_calculate_core`; `frontend/src/lib/manualBolusCore.js::calculateManualBolus`; `android-companion/src/main/java/org/bolusai/companion/bolus/BolusCalculator.kt::calculate` | no creado | pendiente | bloquear |
| LC-009 | redondeo y límites | `backend/app/services/bolus_engine.py::_calculate_core`; `frontend/src/lib/manualBolusCore.js::calculateManualBolus`; `android-companion/src/main/java/org/bolusai/companion/bolus/BolusCalculator.kt::calculate` | no creado | pendiente | vector de fronteras requerido |
| LC-010 | cálculo sin glucosa | `backend/app/services/bolus_calc_service.py::calculate_bolus_stateless_service`; `frontend/src/lib/manualBolusCore.js::calculateManualBolus`; `android-companion/src/main/java/org/bolusai/companion/bolus/BolusCalculator.kt::calculate` | no creado | pendiente | Next fail-closed |
| LC-011 | IOB manual | `backend/app/models/bolus_v2.py::BolusRequestV2`; `backend/app/services/bolus_calc_service.py::calculate_bolus_stateless_service`; `android-companion/src/main/java/org/bolusai/companion/MainActivity.kt::BolusScreen` | no creado | pendiente | bloquear salvo política aprobada |
| LC-012 | huella de inputs/perfil/motor | `backend/app/models/settings.py::compute_hash`; `backend/app/models/bolus_v2.py::BolusResponseV2` | no creado | técnica pendiente | diseñar en Fase 2 |

## 8. Criterio cubierto y trabajo restante

Este lote cubre el primer trazado P0 de LF-002, LF-003, LF-005, LF-006, LF-007,
LF-008 y LF-009: callers, entradas, estados, fallbacks, tests y divergencias. No
cierra la Fase 1.

Queda pendiente:

1. auditar en detalle curvas y reglas avanzadas sin convertir literales en
   decisiones clínicas;
2. trazar confirmación/persistencia/idempotencia y autoridad de escritura;
3. completar nutrición, sync y el resto de capacidades `LF-*`;
4. obtener responsables y aprobaciones nominales;
5. crear vectores saneados únicamente para reglas aprobadas;
6. transformar decisiones aprobadas en contratos de Fase 2.
