# Registro inicial de riesgos y aprobaciones

Este registro cubre el esqueleto técnico de Fase 0. No aprueba reglas clínicas ni
autoridad de tratamiento.

| ID | Riesgo | Prioridad | Mitigación actual | Prueba o puerta | Estado/aprobación |
|---|---|---|---|---|---|
| R-001 | Duplicar fórmulas entre Android e iOS | Seguridad/consistencia | Un solo módulo KMP en `shared/bolus-engine` | CI compila JVM e iOS desde `commonMain` | Mitigación técnica aceptada; clínica pendiente |
| R-002 | Introducir defaults clínicos al crear el motor | Seguridad | El esqueleto no contiene parámetros ni cálculo y declara reglas no implementadas | Test `EngineIdentityTest` y revisión de diff | Bloqueado hasta auditoría y aprobación |
| R-003 | Aceptar un cambio que solo funciona en Windows | Consistencia | Verificación JVM en Windows e iOS en runner macOS | Workflow `verify.yml` | Configurado; pendiente de ejecución CI |
| R-004 | Comprometer artefactos o datos sensibles | Seguridad | Ignorar outputs del build; fixtures reales prohibidos | `git status`, revisión y política del repositorio | Activo, sin datos clínicos en alcance |
| R-005 | Confundir artefacto compilable con motor clínico válido | Seguridad | Nombre/flag explícito y ADR sin autorización clínica | Gate G1 y revisión del propietario | Bloqueo obligatorio |
| R-006 | Dependencia de red en el futuro motor | Disponibilidad | `commonMain` sin clientes de red ni SDKs de plataforma | Revisión de dependencias/arquitectura | Activo |

## Decisiones pendientes

- Contratos versionados de glucosa, IOB, perfil, comida y resultado.
- Esquema de serialización y huella canónica.
- Reglas, parámetros, límites, redondeos y políticas de vigencia aprobadas.
- Versiones mínimas de Android e iOS, que pertenecen al trabajo de clientes.
- Responsables nominales y evidencia de aprobación clínica antes de cada regla.
