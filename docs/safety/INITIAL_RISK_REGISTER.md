# Registro inicial de riesgos y aprobaciones

Este registro cubre el esqueleto técnico de Fase 0. No aprueba reglas clínicas ni
autoridad de tratamiento.

| ID | Riesgo | Prioridad | Mitigación actual | Prueba o puerta | Estado/aprobación |
|---|---|---|---|---|---|
| R-001 | Duplicar fórmulas entre Android e iOS | Seguridad/consistencia | Un solo módulo KMP en `shared/bolus-engine`; no existe motor Swift separado | JVM automática; iOS no soportado hasta superar prueba Mac explícita, según ADR 0004 | Mitigación estructural aceptada; no declarar soporte iOS actual |
| R-002 | Introducir defaults clínicos al crear el motor | Seguridad | El esqueleto no contiene parámetros ni cálculo y declara reglas no implementadas | Test `EngineIdentityTest` y revisión de diff | Bloqueado hasta auditoría y aprobación |
| R-003 | Aceptar un cambio que solo funciona en Windows | Consistencia | Verificación JVM/Android automática; conservar pruebas KMP/Swift para Mac autorizado | Workflow `verify.yml` + ADR 0004 | Riesgo aceptado para la ruta futura; iOS no es plataforma soportada |
| R-004 | Comprometer artefactos o datos sensibles | Seguridad | Ignorar outputs del build; fixtures reales prohibidos | `git status`, revisión y política del repositorio | Activo, sin datos clínicos en alcance |
| R-005 | Confundir artefacto compilable con motor clínico válido | Seguridad | Nombre/flag explícito y ADR sin autorización clínica | Gate G1 y revisión del propietario | Bloqueo obligatorio |
| R-006 | Dependencia de red en el futuro motor | Disponibilidad | `commonMain` sin clientes de red ni SDKs de plataforma | Revisión de dependencias/arquitectura | Activo |
| R-007 | Elegir silenciosamente una de las tres fórmulas Legacy divergentes | Seguridad/consistencia | Auditoría por componente; ninguna variante se considera aprobada | Decisiones LC-006 a LC-009 + vectores revisados | Bloqueado hasta aprobación clínica |
| R-008 | Perfil ausente o ambiguo sustituido por defaults históricos | Seguridad | Contrato Next fail-closed; no portar `UserSettings.default` ni `BolusProfile.defaultSlots` | LC-001 + tests de perfil incompleto/slot ausente | Bloqueado |
| R-009 | IOB parcial o historial incompleto tratado como verificable | Seguridad | Exigir integridad de ventana, eventos, perfil y algoritmo; desconocido nunca es cero | LC-004/LC-005 + tests de huecos, conflictos y reinicio | Bloqueado |
| R-010 | Aceptar un broadcast G7 basándose solo en extras controlables | Seguridad | El puerto Android v1 no tiene rama de lectura disponible; exigir contrato Dexcom autorizado e identidad de emisor verificable, sin confiar en action, package extra, paquete instalado o permiso `dangerous` | LC-003 + tests del puerto + [investigación oficial/dispositivo](../validation/dexcom-g7-android-local-reception-2026-09-06.md); faltan contrato Dexcom y tests Android de emisor/permiso | Parcial: fail-closed integrado; no se demostró contrato local autorizado |
| R-011 | Resultado no reproducible por relojes globales o huella incompleta | Consistencia/seguridad | Reloj inyectado y huella canónica que incluya inputs, perfil, reglas y motor | LC-012 + tests deterministas | Diseño Fase 2 pendiente |
| R-012 | Una UI temprana parece apta para tratamiento antes de existir validación | Seguridad | Banner no autoritativo, cálculo bloqueado y política pendiente explícita; sin acciones de confirmación o registro | Tests del presentador + lint/compilación Android + instalación/arranque/revisión visual | Mitigado para la pantalla actual; reevaluar cada ampliación |
| R-013 | La versión Android provisional excluye el dispositivo objetivo o no coincide con Dexcom | Disponibilidad/consistencia | `minSdk 26` se documenta como provisional; no se declara compatibilidad Dexcom hasta auditar y probar el dispositivo autorizado | ADR 0002 + prueba en Pixel 10 Pro Fold API 37; falta Dexcom | APK instalado; decisión de compatibilidad Dexcom pendiente |
| R-014 | Añadir una lectura «válida» antes de aprobar su contrato | Seguridad | Resultado sellado sin variante disponible; la ampliación exige cambio exhaustivo y revisión del ADR/contrato | `LocalGlucoseSourcePortTest` + revisión de tipos | Bloqueado por diseño hasta resolver decisiones pendientes |

## Decisiones pendientes

- Contratos versionados de glucosa, IOB, perfil, comida y resultado.
- Esquema de serialización y huella canónica.
- Reglas, parámetros, límites, redondeos y políticas de vigencia aprobadas.
- Versiones mínimas de Android e iOS, que pertenecen al trabajo de clientes.
- Responsables nominales y evidencia de aprobación clínica antes de cada regla.
- Decisiones LC-001 a LC-012 de `docs/audit/legacy-clinical.md`.
