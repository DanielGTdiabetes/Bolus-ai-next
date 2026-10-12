# Inventario funcional de Bolus AI Legacy

## Snapshot y límites

- Repositorio: `https://github.com/DanielGTdiabetes/bolus_ai`
- Rama resuelta: `main`
- SHA fijado: `3182dac25f7ecc60a5da4d6ec61d08b60f7f3834`
- Commit: `2026-08-30T09:46:57+02:00 — Refresh Render frontend artifacts`
- Fecha de auditoría: 2026-08-30
- Clasificación: comportamiento/superficie **observada**, no regla clínica aprobada

La inspección se hizo en una copia temporal detached con el push de `origin`
configurado como `DISABLED`. No se ejecutaron aplicaciones, migraciones, tests,
webhooks, clientes, escrituras ni tratamientos de Legacy.

## Método y cobertura

Se enumeraron archivos con `rg --files`, routers con `include_router` y
decoradores HTTP, páginas frontend, componentes declarados en el manifest
Android y suites de tests. Resultado reproducible del SHA fijado:

| Superficie | Conteo observado |
|---|---:|
| Decoradores de ruta backend | 123 |
| Archivos API con rutas | 30 |
| Servicios backend (sin `__init__.py`) | 53 |
| Páginas frontend JSX | 22 |
| Fuentes Kotlin Android | 55 |
| Tests backend | 96 |
| Tests Android | 19 |
| Tests frontend | 15 |

Los conteos impiden confundir una lista manual corta con cobertura completa, pero
no prueban que cada ruta esté activa, accesible o utilizada. La siguiente pasada
debe seguir callers, flags, jobs y navegación para cada capacidad.

## Matriz de capacidades

`Estado Next` distingue inventario de implementación. Ninguna fila está marcada
como migrada, aprobada o segura de retirar en este lote.

| ID | Capacidad observable | Evidencia primaria en Legacy | Dependencias/efectos que deben auditarse | Dirección Next | Estado Next |
|---|---|---|---|---|---|
| LF-001 | Sesión, login, refresh, logout, perfil y contraseña | `backend/app/api/auth.py`; páginas `LoginPage`, `ProfilePage`, `ChangePasswordPage` | tokens, usuario local/remoto, expiración y recuperación | definir identidad local y acceso opcional a servicios | inventariada; decisión pendiente |
| LF-002 | Perfil/configuración clínica e importación | `api/settings.py`, `models/settings.py`, `services/settings_service.py`, `SettingsPage.jsx` | precedencia, defaults, unidades, horarios, secretos y versiones | perfil local versionado, sin defaults | inventariada; auditoría clínica P0 |
| LF-003 | Glucosa actual, historial y estado de fuentes | `api/glucose.py`, `glucose_source_service.py`, `glucose_ingest_service.py` | origen, frescura, timestamps, selección y caché | dominio local con estados explícitos | inventariada; auditoría P0 |
| LF-004 | Dexcom Share/cloud y prueba de conexión | `api/routes/dexcom.py`, `dexcom_client.py` | credenciales, red, zona temporal y fallos | integración opcional, fuera del camino crítico | inventariada; paridad pendiente |
| LF-005 | Recepción local Dexcom G7 y cola Android | `GlucoseReceiver.kt`, `GlucoseReading.kt`, `GlucoseQueueRepository.kt`, manifest | permiso, package/origen, duplicados, reinicio y caducidad | migrar conocimiento al adaptador Android local | inventariada; hito P0 |
| LF-006 | Cálculo de bolo backend | `api/bolus.py`, `bolus_calc_service.py`, `bolus_engine.py`, DTOs/modelos | CR, ISF, target, IOB, fibra, Warsaw, ejercicio, límites y redondeo | reimplementar solo reglas aprobadas en motor común | inventariada; no aprobada |
| LF-007 | Cálculo offline Android Companion | `BolusCalculator.kt`, `BolusProfile.kt`, `BolusProfileRepository.kt` | fallbacks de slot/perfil y divergencia frente a backend | sustituir por el motor común tras paridad | inventariada; fallbacks bloqueados |
| LF-008 | Calculadora manual/emergencia frontend | `ManualCalculatorPage.jsx`, `manualBolusCore.js`, `onlineBolusPayload.js` | tercera ruta de fórmula, datos manuales y comportamiento offline | conservar necesidad funcional sin duplicar fórmula | inventariada; divergencia pendiente |
| LF-009 | IOB/COB y curvas de acción | `api/bolus.py::get_iob`, `services/iob.py`, `services/math/curves.py` | fuentes, historial suficiente, DIA, deduplicación y caché | motor común; desconocido nunca equivale a cero | inventariada; auditoría clínica P0 |
| LF-010 | Tratamientos, confirmación y eventos de bolo | `api/bolus.py`, `api/events.py`, `api/injection.py`, `treatment_logger.py` | escritura Nightscout/local, identidad, idempotencia y doble escritor | eventos locales durables y autoridad única | inventariada; escritura Next bloqueada |
| LF-011 | Importación nutricional MFP/Health Connect/Auto Export | `api/integrations.py`, `imported_meal_service.py`, Android `health/`, `MyFitnessPalAssistantService.kt`, Hermes | autenticación, identidad, revisiones, permisos y reintentos | borrador local revisable; servicio opcional | inventariada; contrato pendiente |
| LF-012 | Sesiones de comida larga y carbohidratos añadidos | `api/meal_sessions.py`, `meal_session_service.py`, `mealSessionFlow.js` | enlace a tratamientos, revisión, cierre/cancelación | preservar caso de uso mediante eventos locales | inventariada; semántica pendiente |
| LF-013 | Escáner/estimación visual de comida | `api/vision.py`, `services/vision.py`, `ScanPage.jsx`, `CameraCapture.jsx` | proveedor remoto, imagen, privacidad y revisión humana | importación opcional nunca autoritativa | inventariada; proveedor pendiente |
| LF-014 | Base de alimentos y favoritos | `api/user_data.py`, `FoodDatabasePage.jsx`, `FavoritesPage.jsx`, `foodData.json` | identidad, edición, unidades y datos embebidos | preservar localmente tras normalización | inventariada; datos pendientes |
| LF-015 | Pronóstico y simulación de glucosa | `api/forecast.py`, `forecast_engine.py`, `forecast_params_resolver.py`, `ForecastPage.jsx` | IOB/COB, parámetros, reloj, ML y presentación de incertidumbre | no usar para dosis hasta aprobación independiente | inventariada; clínica bloqueada |
| LF-016 | Basal, sueño, check-ins, consejo y evaluación | `api/basal.py`, `basal_engine.py`, `basal_context_service.py`, `BasalPage.jsx` | historial, escritura, reglas, jobs y recomendaciones | capacidad separada del primer flujo de bolo | inventariada; clínica bloqueada |
| LF-017 | ISF dinámico, análisis y autosens | `api/isf.py`, `api/autosens.py`, `dynamic_isf_service.py`, `autosens_*` | aprendizaje, límites, ventana y aplicación al cálculo | solo tras evidencia y aprobación explícita | inventariada; clínica bloqueada |
| LF-018 | Patrones, aprendizaje, sugerencias y evaluaciones | `api/analysis.py`, `api/learning.py`, `api/suggestions.py`, servicios homónimos | datos históricos, explicabilidad, aceptación/rechazo y efectos | modo análisis no autoritativo inicialmente | inventariada; alcance pendiente |
| LF-019 | Entrenamiento/inferencia ML y estado de modelo | `api/ml.py`, `ml_*service.py`, `ml_training_pipeline.py` | datasets, versión, drift, fallback y reproducibilidad | servicio auxiliar, no camino crítico | inventariada; criterios pendientes |
| LF-020 | Rotación y mapa de zonas de inyección | `api/injection.py`, `injection_sites.py`, `rotation_service.py`, `BodyMapPage.jsx` | historial, mutaciones y endpoint legacy | preservar UX si se valida; separar de dosis | inventariada; uso pendiente |
| LF-021 | Insumos y notificaciones | `api/user_data.py`, `api/notifications.py`, `SuppliesPage.jsx`, `NotificationsPage.jsx` | inventario, permisos push y reglas de alerta | función local no clínica priorizable después | inventariada; paridad pendiente |
| LF-022 | Báscula Bluetooth Prozis | Android `scale/`, `bleScale.js`, `ScalePage.jsx` | Bluetooth, parseo, unidades y asociación a comida | adaptador opcional de plataforma | inventariada; hardware pendiente |
| LF-023 | Nightscout: glucosa, tratamientos, secretos y config | `api/nightscout*.py`, `nightscout_client.py`, `NightscoutSettingsPage.jsx` | secretos, lecturas/escrituras, conflictos y compatibilidad | destino/fuente opcional tras outbox | inventariada; escritura bloqueada |
| LF-024 | HA, monitor de estabilidad, rescue sync y backup NAS/Render | `stability_monitor.py`, `rescue_sync.py`, `deploy/`, `scripts/migration/` | doble instancia, replay, migraciones y recuperación | congelar solo tras operación local y rollback probados | inventariada; no retirar |
| LF-025 | Importación/exportación de datos | `api/data.py`, `export_service.py`, `import_service.py` | formato, versiones, secretos, datos personales y conflictos | contrato local versionado y saneado | inventariada; contrato pendiente |
| LF-026 | Telegram, agente y notificaciones proactivas | `backend/app/bot/`, `api/agent.py`, `api/bot_*` | LLM, webhooks, líderes, secretos y posibles acciones | interfaz opcional; sin autoridad clínica inicial | inventariada; efectos pendientes |
| LF-027 | Companion: snapshot, episodios, portal y diagnóstico | `api/companion.py`, Android `portal/`, `diagnostics/`, `data/` | preferencias, logs, servidor y privacidad | absorber UX útil en Android local | inventariada; selección pendiente |
| LF-028 | Modo enfermo | `api/events.py`, `sickModePolicy.js`, UI/configuración | multiplicadores/reglas y duración | no implementar sin decisión clínica | inventariada; clínica bloqueada |
| LF-029 | Historial y gráficos | `HistoryPage.jsx`, `MainGlucoseChart.jsx`, `BasalGlucoseChart.jsx` | retención, fuentes, timezone y tratamientos | lectura local auditable | inventariada; contrato pendiente |
| LF-030 | Salud operativa, jobs, cambios y estado | `api/health.py`, `api/db.py`, `api/changes.py`, `StatusPage.jsx` | despliegue, DB, scheduler y disponibilidad remota | diagnóstico local + estado específico por integración | inventariada; diseño pendiente |

## Cobertura Next registrada por entrega

Estado de lo implementado en Next frente a cada `LF-*`. «Parcial» significa que
el caso de uso completo aún no está cubierto. Ninguna fila declara aprobación
clínica ni uso terapéutico.

| ID | Fuente Legacy y SHA | Caso de uso cubierto | Alternativa Next | Estado | Prueba |
|---|---|---|---|---|---|
| LF-005 | `android-companion/.../dexcom/GlucoseReceiver.kt` y manifest en `f5417721` (ADR 0023) | recibir la glucosa local de la Dexcom G7 instalada y mostrarla | receptor por permiso, registro v2 y presentación en Inicio y Bolo ([ADR 0023](../adr/0023-dexcom-existing-transport-trust.md)) | parcial: recepción real pendiente de P1 a P6 en el Pixel e I-3 | pruebas JVM y de dispositivo del ADR 0023; [validación](../validation/dexcom-permission-reception-and-test-calculation-2026-10-12.md) |
| LF-006 | `backend/app/services/bolus_engine.py::_calculate_core` en `bcac891` | cálculo con comida, corrección, límite de corrección, IOB, redondeo, bolo máximo y parada | motor candidato único y cálculo de prueba en Bolo ([ADR 0024](../adr/0024-candidate-engine-test-calculation.md)) | parcial: reglas candidatas, IOB manual y sin fibra, Warsaw, ejercicio, autosens, Techne, dual ni techo de IOB | `CandidateEngineTest` con 12 vectores de Legacy; `TestCalculationTest` |
| LF-002 | `backend/app/models/settings.py::UserSettings` (`round_step_u`, `max_bolus_u`, `max_correction_u`) en `bcac891` | guardar paso de redondeo, bolo máximo y corrección máxima una sola vez y usarlos en cada cálculo | límites de perfil en el esquema de contenido 2, versionados y confirmables, sin defaults ([ADR 0025](../adr/0025-profile-calculation-limits.md)) | parcial: cubiertos estos tres campos y, desde el [ADR 0027](../adr/0027-profile-insulin-action.md), DIA, curva y pico de `IOBConfig` en el esquema de contenido 3, sin defaults. Faltan `max_iob_u`, `min_bolus_interval_min`, fibra, Warsaw, ejercicio e importación | `ProfileLimitsTest`, `ProfileInsulinActionTest`, `TestCalculationTest`, `ClinicalProfileSchemaTextTest`; A1–A3 superadas en el Pixel, A4 no comprobada por falta de perfil real v3 (decisión del propietario) ([evidencia](../validation/profile-insulin-action-2026-10-12.md)) |
| LF-009 | `backend/app/services/iob.py::compute_iob_from_sources` y `backend/app/services/math/curves.py` en `bcac891` | calcular el IOB desde las dosis recientes con DIA, curva, pico y bolo extendido | IOB candidato en el motor único desde un historial explícito con cobertura verificada ([ADR 0026](../adr/0026-candidate-iob-from-dose-history.md)) | parcial: motor y, con el ADR 0027, DIA/curva/pico en el perfil. Faltan historial local de insulina, uso en Bolo, COB, techo de IOB y aviso de apilamiento | `CandidateIobTest` con 19 vectores de Legacy |

## Cobertura que aún falta demostrar

La matriz evita omisiones por categoría, pero no permite afirmar todavía que no
queda ninguna función pendiente. Para cerrar esa afirmación hacen falta, por cada
`LF-*`:

1. caller/ruta/UI/job realmente ejecutado en el SHA;
2. inputs, outputs, efectos laterales, permisos y estados de error;
3. tests existentes y huecos;
4. decisión aprobada: migrar, corregir, bloquear o retirar;
5. requisito/contrato Next y prueba o motivo de retirada;
6. propietario, retención y rollback cuando se retire algo.

Hasta entonces, “inventariada” no significa “implementada” y “candidata a
retirada” no significa “obsoleta”.

## Comandos de evidencia

```powershell
$legacyUrl = "https://github.com/DanielGTdiabetes/bolus_ai.git"
$legacySha = (git ls-remote $legacyUrl refs/heads/main).Split()[0]
git -C $auditRepo checkout --detach $legacySha
git -C $auditRepo remote set-url --push origin DISABLED
rg --files $auditRepo
rg -n "include_router|APIRouter\(" "$auditRepo/backend/app"
rg -n "@(?:router|app)\.(?:get|post|put|patch|delete|websocket)\(" "$auditRepo/backend/app/api"
rg -n "<activity|<service|<receiver|<provider|uses-permission" "$auditRepo/android-companion/src/main/AndroidManifest.xml"
```
