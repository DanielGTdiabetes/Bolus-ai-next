# Responsabilidades y aprobaciones

Este mapa define roles, no inventa personas ni sustituye una aprobación
registrada. Los nombres pendientes deben confirmarse antes de la puerta que los
requiera.

| Decisión | Responsable | Aprobación requerida | Estado |
|---|---|---|---|
| Prioridad y alcance del producto | propietario de Bolus AI Next | propietario | rol conocido; identidad a registrar explícitamente |
| Regla, parámetro o cambio clínico | revisor clínico designado + propietario | ambos, con evidencia y vector | responsables nominales pendientes; implementación bloqueada |
| Arquitectura y toolchain | responsable técnico | revisión de código y CI | ADR 0001 aceptado técnicamente; CI pendiente |
| Acceso de solo lectura a Legacy | propietario del repositorio Legacy | propietario y límites de `AGENTS.md` | permitido solo para auditoría reproducible |
| Escritura o modificación de Legacy | propietario de Legacy | petición separada, backup y rollback | fuera de alcance y bloqueada |
| Cambio de autoridad de tratamiento | propietario + revisor clínico + responsable operativo | criterios de sombra, exclusión mutua y rollback | bloqueado hasta Fase 11 |
| Retirada de función, dato o servicio | propietario funcional y responsable de datos/operación | inventario de dependencias, retención y rollback | no autorizado por defecto |

Una aprobación debe enlazar el requisito, versión/SHA, decisión, pruebas y
persona que la concede. Una conversación informal o el comportamiento observado
de Legacy no convierte una regla clínica en aprobada.
