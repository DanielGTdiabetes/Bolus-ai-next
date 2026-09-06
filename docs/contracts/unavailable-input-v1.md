# Entrada no disponible, versión 1

Fase 2: primer contrato en memoria, compartido por Android e iOS mediante el
módulo KMP del ADR 0001. No incluye evaluación clínica, valores, cálculo ni
autorización de tratamiento. No depende de Legacy ni deriva reglas de su código.

`UnavailableInput` requiere `InputKind` y `UnavailabilityReason` explícitos.
No admite valores sustitutivos ni un estado válido. `contractVersion = 1` es
una constante técnica. `code` tiene la forma `input.<entrada>.<motivo>`; la UI
podrá traducirlo sin usar nombres de enums, ordinales o mensajes como identidad.

Entradas: `glucose`, `profile`, `iob`, `meal`.

| Motivo | Significado que debe demostrar el productor |
|---|---|
| `missing` | Dato ausente |
| `invalid` | Dato inválido conforme al contrato aplicable |
| `expired` | Vigencia excedida según una política aprobada |
| `unknown` | Estado desconocido |
| `incomplete` | Información incompleta, incluido historial IOB |
| `conflicting` | Identidades o datos en conflicto |
| `permission_denied` | Permiso denegado |
| `source_unavailable` | Fuente no disponible |
| `authentication_failed` | Fallo de autenticación |
| `clock_anomaly` | Anomalía de reloj |
| `parse_failed` | Fallo de interpretación del formato |
| `persistence_failed` | Fallo de almacenamiento |
| `policy_not_approved` | Falta una política aprobada necesaria |

El contrato comunica una causa ya determinada: no valida glucosa, perfil o IOB,
no selecciona precedencias y no convierte errores en «sin conexión». No basta
por sí solo para implementar la puerta de cálculo. Cada futuro productor debe
aportar validación y pruebas del motivo reportado. No se debe declarar `expired`
cuando la política de vigencia está pendiente.

Los códigos publicados quedan fijados por pruebas comunes. Añadir motivos exige
revisar consumidores; renombrar o cambiar su significado exige nueva versión.
La acumulación de causas se define en [el contrato de informe v1](unavailable-input-report-v1.md).
La serialización, huella, procedencia y snapshots
validados quedan pendientes de sus contratos. Este objeto no debe persistirse
como registro clínico completo ni usarse como prueba de integridad del historial.

## Verificación y límites

`UnavailableInputTest` comprueba códigos estables y únicos, versión e identidad
de los estados desconocido/incompleto y política pendiente/caducidad. Se ejecuta
con `scripts/verify.ps1` en JVM. Los targets y pruebas iOS permanecen en las
mismas fuentes, pero el ADR 0004 retiró su ejecución macOS automática y clasifica
iOS como plataforma futura no soportada. Deben ejecutarse explícitamente antes
de promover el binding o afirmar compatibilidad iOS actualizada. Los casos son
técnicos y sintéticos, no golden vectors clínicos. No se incorporan reglas
clínicas.
