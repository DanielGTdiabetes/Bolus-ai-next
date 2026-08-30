# Registro de candidatos a retirada de Legacy

Snapshot: `3182dac25f7ecc60a5da4d6ec61d08b60f7f3834`.

Este registro responde a la petición de purgar lo viejo sin convertir una
impresión de obsolescencia en una eliminación peligrosa. No autoriza modificar ni
borrar nada de Legacy. Toda acción en ese repositorio requiere un alcance
separado, backup y rollback conforme a `AGENTS.md`.

| ID | Candidato observado | Evidencia | Riesgo si se elimina pronto | Gate para retirar | Estado |
|---|---|---|---|---|---|
| LR-001 | Implementaciones duplicadas de cálculo backend, frontend manual y Android | `bolus_engine.py`, `manualBolusCore.js`, `BolusCalculator.kt` | perder emergencia/offline o copiar divergencias clínicas | motor Next aprobado, paridad por vector y clientes migrados | sustituir en Next; no tocar Legacy |
| LR-002 | Alias/endpoints marcados legacy o backwards compatibility | `nightscout.py::update_config`, `injection.py::rotate-legacy`, alias `/photo` | clientes ocultos pueden seguir llamándolos | telemetría saneada/inventario de callers, ventana de deprecación y rollback | candidato, uso no demostrado |
| LR-003 | Resultados y base de test en la raíz | `*_output.txt`, `iob_test_results.txt`, `test_integrity.db`, `output.txt` | pueden ser evidencia histórica o contener datos no revisados | clasificar contenido/propietario, sanear, conservar evidencia necesaria | candidato de higiene; no borrar |
| LR-004 | Scripts de diagnóstico/escritura Nightscout en raíz | `verify_ns_write*.py`, `diagnose_ns.py`, `check_db.py` | pueden escribir producción o ser runbooks de recuperación | documentar efectos, reemplazo seguro y aprobación del propietario | no ejecutar; retirada pendiente |
| LR-005 | Artefacto temporal frontend | `frontend/src/lib/temp_api.txt` | uso de build/import desconocido | búsqueda de callers y build reproducible sin el archivo | candidato, no aprobado |
| LR-006 | NAS/Render, rescue sync y migraciones | `deploy/`, `render.yaml`, `scripts/migration/`, `rescue_sync.py` | pérdida de producción, backup o rollback durante convivencia | Next como autoridad aprobada, backup/restore y rollback ensayados | conservar/congelar más adelante |
| LR-007 | Telegram/bot y agente remoto | `backend/app/bot/`, `api/agent.py`, `api/bot_*` | pérdida de avisos, flujos o acceso de emergencia | mapa de capacidades/callers, sustitución aceptada y autoridad delimitada | candidato parcial, no retirar |
| LR-008 | Dexcom/Nightscout cloud en camino servidor | `dexcom_client.py`, `nightscout_client.py` | perder fuentes redundantes e histórico | Dexcom local validado offline y política de fuentes aprobada | mantener como integración opcional |
| LR-009 | Documentación y auditorías históricas duplicadas | `AUDIT_*.md`, `PROJECT_ANALYSIS.md`, docs históricos | perder procedencia de decisiones/defectos | índice, clasificación de vigencia y archivo recuperable | archivar, no borrar por antigüedad |

## Regla de purga para Next

En Bolus AI Next sí se excluyen artefactos regenerables (`build/`, `.gradle/`,
`.kotlin/`) mediante `.gitignore`. Para código, datos, contratos o servicios, la
retirada solo se integra cuando la matriz funcional demuestra reemplazo o decisión
explícita, los tests confirman ausencia de regresión, y existen retención y
rollback proporcionales al riesgo.
