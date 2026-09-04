# Mapa de fuentes de Bolus AI Legacy

## 1. Finalidad y alcance

Este documento dirige la auditoría de solo lectura de Bolus AI Legacy para que
Bolus AI Next aproveche la información real disponible en GitHub sin modificar
producción ni copiar supuestos históricos.

Repositorio primario:

```text
https://github.com/DanielGTdiabetes/bolus_ai
```

Snapshot inspeccionado al crear este mapa:

```text
rama: main
commit: 3182dac25f7ecc60a5da4d6ec61d08b60f7f3834
fecha del commit: 2026-08-30T09:46:57+02:00
asunto: Refresh Render frontend artifacts
```

El SHA anterior es evidencia reproducible de este inventario, no una referencia
que deba quedarse congelada para siempre. Al iniciar cada lote de auditoría se
consulta `main` en GitHub y se registra el nuevo SHA. No mezclar archivos de dos
SHAs en un mismo vector o conclusión.

Lotes posteriores registrados:

| Fecha | SHA fijado | Alcance | Resultado |
|---|---|---|---|
| 2026-09-04 | `f5417721d8019a9831126f4d843edfc4de87653d` | cálculo, perfil, IOB y glucosa/Dexcom G7 local | [`audit/legacy-clinical.md`](audit/legacy-clinical.md); observado, sin reglas aprobadas ni vectores |

Entre el snapshot inicial y el lote de 2026-09-04 solo cambiaron tres archivos
de DB/autenticación; los archivos clínicos auditados no cambiaron. Cada informe
mantiene aun así su propio SHA fijado.

## 2. Regla de uso

La información del repositorio Legacy se clasifica así:

1. **observada:** el código o test demuestra que Legacy hace algo;
2. **documentada:** un documento lo describe, pendiente de verificar contra el
   camino ejecutado;
3. **aprobada:** el propietario ha confirmado que debe conservarse y existe un
   vector/requisito trazable;
4. **defecto o incógnita:** no debe migrarse hasta resolverla.

Solo la categoría **aprobada** puede convertirse en regla del motor de Next. Un
test Legacy puede confirmar el comportamiento observado, pero no convierte un
fallback o literal en parámetro clínico válido.

No ejecutar aplicaciones, scripts de despliegue, migraciones, jobs, webhooks,
clientes de Nightscout/Telegram/NAS/Render ni tests que necesiten credenciales o
produzcan efectos externos. Para extraer vectores, aislar funciones puras o usar
fixtures saneados en un entorno sin secretos y sin red de escritura.

## 3. Hallazgos de orientación del snapshot

Estos hallazgos sirven para priorizar la auditoría; no son todavía decisiones de
implementación:

- Legacy tiene al menos dos implementaciones de cálculo: el backend Python y el
  cálculo offline del Android Companion.
- Ambas rutas contienen fallbacks/literales clínicos históricos. Next no debe
  copiar esos defaults ni asumir que representan el perfil actual.
- El backend ya separa parte del núcleo matemático, pero la adaptación y el
  servicio siguen resolviendo configuración, fuentes, estados y fallbacks. Deben
  auditarse juntos.
- IOB ya modela estados de fuente, caché y deduplicación. Es material valioso para
  descubrir casos, pero Next debe bloquear si no puede demostrar integridad del
  historial requerido.
- Android ya recibe el broadcast local de Dexcom G7 y dispone de cola/diagnóstico
  y tests asociados. Es la fuente inicial para el hito offline, sujeta a auditoría
  de permisos, origen, timestamp, persistencia y frescura.
- Nutrición ya usa conceptos de identidad, fingerprint, reconciliación, revisión,
  shadow matching y outbox. Son candidatos para contratos de Next, no código para
  copiar sin revisar.
- La documentación Legacy contiene arquitectura y planes históricos que pueden
  estar desfasados respecto del código; comprobar siempre el camino ejecutable.

Riesgo principal observado: copiar el Android Companion como “motor local”
perpetuaría un segundo motor y sus fallbacks. Next debe usarlo como fuente de
requisitos, contratos y pruebas, y construir una única implementación compartida.

La pasada de inventario funcional de este snapshot localizó 123 decoradores de
ruta en 30 archivos API, 53 archivos de servicio backend, 22 páginas frontend,
55 fuentes Kotlin Android, 96 tests backend, 19 tests Android y 15 tests frontend.
Son superficies observadas, no garantía de uso en producción ni aprobación
clínica. La cobertura y su clasificación están en
[`audit/legacy-functional-inventory.md`](audit/legacy-functional-inventory.md).
La primera trazabilidad clínica P0 y las divergencias entre backend, frontend y
Android están en [`audit/legacy-clinical.md`](audit/legacy-clinical.md).

## 4. Inventario dirigido

Las rutas son relativas a la raíz de `DanielGTdiabetes/bolus_ai`.

### 4.1 Cálculo y orquestación

| Prioridad | Fuente Legacy | Qué extraer/verificar |
|---|---|---|
| P0 | `backend/app/services/bolus_engine.py` | núcleo `_calculate_core`, adaptación `calculate_bolus_v2`, target, CR/ISF, corrección, IOB aplicado, fibra, Warsaw, ejercicio, redondeo, límites y bloqueos |
| P0 | `backend/app/services/bolus_calc_service.py` | reunión de settings, glucosa, IOB/COB, autosens y errores antes de invocar el motor |
| P0 | `backend/app/api/bolus.py` | endpoints/caminos realmente usados, snapshots, confirmación y efectos laterales |
| P0 | `backend/app/dtos/math_models.py` | contrato matemático actual y campos implícitos/obligatorios |
| P0 | `backend/app/models/bolus_v2.py` | requests/responses, desglose, glucose used y used params |
| P0 | `backend/app/models/settings.py` | procedencia/esquema de perfil y defaults históricos que no deben portarse |
| P0 | `android-companion/.../bolus/BolusCalculator.kt` | alcance y divergencias del cálculo offline Android |
| P0 | `android-companion/.../bolus/BolusProfile.kt` | schema, slots y fallbacks locales que Next debe reemplazar por validación estricta |
| P1 | `frontend/src/lib/manualBolusCore.js` | posibles decisiones o transformaciones adicionales en UI |
| P1 | `frontend/src/lib/onlineBolusPayload.js` | adaptación de inputs desde frontend al backend |
| P1 | `frontend/src/lib/bolusTrace.js` | trazabilidad y comparación de cálculos |
| P1 | `backend/app/services/bolus_trace.py` | persistencia/representación del trace |

Pruebas iniciales a inventariar, sin limitar la búsqueda:

```text
backend/tests/test_bolus_calc.py
backend/tests/test_bolus_calc_carb_profile.py
backend/tests/test_bolus_v2.py
backend/tests/test_target_resolution.py
backend/tests/test_bolus_trace.py
backend/tests/test_bolus_confirm.py (si existe en el SHA auditado)
android-companion/src/test/java/org/bolusai/companion/bolus/BolusCalculatorTest.kt
frontend/tests/manualBolusCore.test.js
frontend/tests/onlineBolusPayload.test.js
frontend/tests/bolusTrace.test.js
```

No asumir que la lista es exhaustiva. Confirmarla con búsquedas por símbolo,
imports y llamadas desde API/UI en el SHA fijado.

### 4.2 IOB, insulina y tratamientos

| Prioridad | Fuente Legacy | Qué extraer/verificar |
|---|---|---|
| P0 | `backend/app/services/iob.py` | `InsulinActionProfile`, curva, `compute_iob`, carga/mezcla de fuentes, identidades, deduplicación, caché, estados y breakdown |
| P0 | `backend/app/services/math/curves.py` | curvas adicionales y parámetros usados |
| P0 | `backend/app/services/treatment_logger.py` | identidad/idempotencia de tratamientos y frontera de escritura |
| P0 | `backend/app/models/treatment.py` | modelo persistido, timestamps, source e IDs |
| P0 | `backend/app/api/injection.py` y `backend/app/api/events.py` | rutas de registro/confirmación y efectos laterales |
| P1 | `backend/app/services/async_injection_manager.py` | asincronía, reintentos y estado de entrega |
| P1 | `android-companion/.../network/DexcomBolusEventClient.kt` | eventos de bolo procedentes de Dexcom y ledger local |
| P1 | `android-companion/.../dexcom/DexcomEventSyncRepository.kt` | deduplicación/estado de sincronización |

Pruebas relevantes incluyen los grupos `test_iob*`,
`test_treatment_logger_idempotency.py`, `test_mobile_bolus_events.py`,
`DexcomEventSyncLedgerTest.kt` y `DexcomBolusEventClientTest.kt`.

La auditoría debe responder:

- qué fuentes forman el historial requerido;
- cómo se detectan huecos, errores, datos antiguos y duplicados;
- qué curva/DIA y perfil se aplican a cada evento;
- cuándo el resultado es `ok`, `stale`, `unavailable` o parcial;
- dónde un error/ausencia termina actualmente convertido en cero o caché;
- qué conducta será paridad aprobada y qué conducta cambiará a fail-closed.

### 4.3 Glucosa y Dexcom G7 local

| Prioridad | Fuente Legacy | Qué extraer/verificar |
|---|---|---|
| P0 | `android-companion/.../dexcom/GlucoseReceiver.kt` | contrato del broadcast G7, validación de sensor/package y extracción de lecturas |
| P0 | `android-companion/.../dexcom/GlucoseReading.kt` | validaciones, unidades, identidad y timestamps |
| P0 | `android-companion/.../dexcom/GlucoseQueueRepository.kt` | persistencia/deduplicación de cola y comportamiento tras reinicio |
| P0 | `android-companion/src/main/AndroidManifest.xml` | receiver, permisos, exportación y filtros |
| P0 | `backend/app/services/glucose_ingest_service.py` | UID, normalización, validación temporal e idempotencia de ingesta |
| P0 | `backend/app/services/glucose_source_service.py` | selección de fuentes, frescura, precedencias y fallbacks |
| P1 | `android-companion/.../worker/GlucoseSyncWorker.kt` | drenado, retry y dependencia de servidor que Next debe sacar del camino crítico |
| P1 | `backend/app/services/glucose_sync_service.py` | sincronización y conflictos |

Documentación de apoyo:

```text
docs/GLUCOSE_SOURCES_AND_WATCH_UPSTREAM.md
docs/ANDROID_COMPANION_OFFLINE_BOLUS.md
docs/COMPANION_LOCAL_RELEASE.md
docs/android/ANDROID_UNIFIED_CLIENT_ROADMAP.md
docs/android/BOLUS_AI_COMPANION_TECHNICAL_STUDY.md
```

Pruebas iniciales: `test_glucose_ingest.py`, `test_glucose_source_service.py` o
nombres equivalentes presentes en el SHA; en Android,
`GlucoseQueueCodecTest.kt`, `GlucoseQueueDrainerTest.kt`,
`GlucoseSyncResultPolicyTest.kt`, `GlucoseSyncWakePolicyTest.kt` y
`GlucoseIngestClientTest.kt`.

El primer hito de Next debe reutilizar el conocimiento del contrato local, no el
envío al backend: recibir -> validar -> persistir local -> mostrar, todo en modo
avión.

### 4.4 MyFitnessPal y nutrición

| Prioridad | Fuente Legacy | Qué extraer/verificar |
|---|---|---|
| P0 | `docs/HERMES_MYFITNESSPAL_BOLUS_SYNC.md` | flujo real Hermes/MFP, detección de revisiones, secretos y fallos conocidos |
| P0 | `scripts/hermes/mfp_sync_trigger.py` | disparo y semántica observable, sin ejecutarlo contra servicios reales |
| P0 | `scripts/hermes/sync_to_bolus.py` | contrato enviado, identidad/reintentos y errores |
| P0 | `android-companion/.../accessibility/MyFitnessPalAssistantService.kt` | interacción Android, permisos y riesgos de accesibilidad |
| P0 | `android-companion/.../health/NutritionRecordReader.kt` | fuente Health Connect/Auto Export y ventanas de lectura |
| P0 | `android-companion/.../worker/NutritionSyncRunner.kt` | cola, cambios/revisiones y reintentos |
| P0 | `backend/app/services/imported_meal_service.py` | normalización, fingerprints, reconciliación, edición/descarte y conflictos |
| P0 | `backend/app/services/nutrition_shadow_matcher.py` | clasificación shadow de identidades/revisiones |
| P0 | `backend/app/services/nutrition_notification_outbox.py` | outbox, claim, retry y entrega |
| P0 | `backend/app/models/imported_meal.py` | estados e identidad persistida |
| P1 | migraciones `clin001_*`, `b5d9e3f7a1c2_*`, `c6e0f4a8b2d3_*`, `9e2f4a6b8c1d_*` | evolución de identidad, reconciliación, revisiones rechazadas y outbox |

Pruebas iniciales:

```text
backend/tests/test_imported_meal_reconciliation.py
backend/tests/test_imported_meal_telegram_review.py
backend/tests/test_integrations_nutrition.py
backend/tests/test_nutrition_notification_outbox.py
scripts/hermes/tests/test_mfp_sync_trigger.py
scripts/hermes/tests/test_sync_to_bolus.py
android-companion/.../queue/MealQueueRepositoryTest.kt
android-companion/.../network/NutritionIngestClientTest.kt
android-companion/.../health/NutritionReadWindowTest.kt
android-companion/.../health/NutritionRecordSnapshotTest.kt
```

Antes de adoptar una vía de acceso MyFitnessPal en Next, confirmar que sigue
siendo autorizada y viable. Si no lo es, conservar importación manual/Health
Connect como capacidades independientes; no automatizar credenciales ni usar un
scraper no aprobado por defecto.

### 4.5 Persistencia, sincronización y modo sombra

| Prioridad | Fuente Legacy | Qué extraer/verificar |
|---|---|---|
| P0 | `android-companion/.../queue/BolusCompanionDatabase.kt` y `MealQueue*` | esquema Room, estados de cola y “never lose a meal” |
| P0 | `backend/app/services/nutrition_notification_outbox.py` | patrón outbox existente y sus limitaciones |
| P0 | `backend/app/services/rescue_sync.py` | recuperación/replay y riesgos de duplicado |
| P0 | `backend/app/services/treatment_logger.py` | claves idempotentes en efectos clínicos |
| P0 | `backend/app/services/nutrition_shadow_matcher.py` | experiencia previa de matching en sombra |
| P1 | `docs/HA_BACKUP_STRATEGY.md` y `docs/ISSUES_SYNC_NS.md` | fallos conocidos de backup/sync y recuperación |
| P1 | migraciones Alembic de glucosa, companion, nutrición y tratamientos | evolución real del esquema y compatibilidad |

Extraer invariantes y casos de prueba; no trasladar el modelo servidor como base
local sin evaluar transacciones, privacidad, retención y migraciones Android/iOS.

### 4.6 Arquitectura, seguridad y experiencia

Consultar como índice, verificando cada afirmación contra código y tests:

```text
docs/ARCHITECTURE_MAP.md
docs/ANALISIS_SEGURIDAD_CALCULO.md
docs/MATRIZ_RIESGOS.md
docs/AUDITORIA_T1D.md
docs/audits/TEST_BASELINE_2026.md
docs/TEST_JOURNEYS.md
PROJECT_ANALYSIS.md
AGENTS.md
```

El `AGENTS.md` de Legacy explica cómo se trabajaba en ese repositorio; no se
hereda como instrucción para Next. Solo puede aportar contexto histórico.

## 5. Proceso reproducible de consulta

Resolver el estado de GitHub:

```powershell
$legacyUrl = "https://github.com/DanielGTdiabetes/bolus_ai.git"
$legacySha = (git ls-remote $legacyUrl refs/heads/main).Split()[0]
if (-not $legacySha) { throw "No se pudo consultar bolus_ai/main" }
Write-Output $legacySha
```

Crear una copia separada y sin destino de push. No clonar dentro de Bolus AI Next
ni usar la copia de producción como directorio de pruebas:

```powershell
$auditRoot = Join-Path ([System.IO.Path]::GetTempPath()) "bolus-ai-legacy-audit"
$auditRepo = Join-Path $auditRoot $legacySha.Substring(0, 12)
New-Item -ItemType Directory -Force -Path $auditRoot | Out-Null
if (-not (Test-Path $auditRepo)) {
    git clone --no-tags $legacyUrl $auditRepo
    git -C $auditRepo remote set-url --push origin DISABLED
}
git -C $auditRepo checkout --detach $legacySha
git -C $auditRepo status --short
```

Reglas para esa copia:

- no añadir credenciales ni archivos `.env`;
- no ejecutar comandos de deploy/migración/escritura;
- no hacer push (el push URL queda deshabilitado);
- no editarla para “corregir” Legacy;
- guardar en Next solo mapas, contratos, ADRs y fixtures saneados con procedencia;
- al terminar, registrar SHA y comandos de inspección en el informe.

## 6. Salida obligatoria de la auditoría

Para cada área (cálculo, IOB, glucosa, perfil, nutrición, persistencia y sync), la
auditoría en `docs/audit/legacy-clinical.md` debe contener:

```text
Legacy SHA
ruta y símbolo/contrato
callers y camino ejecutable
inputs, unidades y validaciones
outputs, desglose y efectos laterales
defaults/fallbacks encontrados
estados de error/ausencia/caducidad
identidad, deduplicación y timestamps
tests existentes y huecos
clasificación: observado | documentado | aprobado | defecto/incógnita
vector saneado asociado (si está aprobado)
decisión para Next: migrar | corregir explícitamente | bloquear | descartar
```

El criterio de salida no es “hemos leído los archivos”, sino poder trazar cada
regla aprobada desde GitHub hasta un requisito y un vector que el motor único de
Next reproduce sin defaults clínicos.
