# Informe de entradas no disponibles, versión 1

Fase 2, contrato en memoria sobre `UnavailableInput`, compartido por Android e
iOS conforme al ADR 0001. No evalúa reglas clínicas ni usa fuentes de Legacy.

`UnavailableInputReport` recibe una lista explícita y no vacía de causas ya
determinadas por sus productores. Captura una copia, elimina solo pares idénticos
de entrada/motivo y ordena por el código estable completo en orden lexicográfico.
Este orden es técnico; no determina prioridad clínica ni selecciona una causa
principal. Dos motivos diferentes para la misma entrada se conservan, incluso
si el productor debe investigar una contradicción entre ellos.

- `contractVersion = 1` identifica este contrato técnico.
- `entries` devuelve una copia de las causas capturadas.
- Una lista vacía lanza `IllegalArgumentException` con el identificador estable
  `unavailable_input_report.empty`. Es un error de uso del contrato, no un estado
  clínico válido ni un resultado de cálculo.
- Cambiar la lista original o la lista devuelta no modifica el informe.

Ejemplo sintético: `input.iob.unknown` y `input.iob.incomplete` permanecen como
dos causas distintas. Repetir `input.iob.unknown` no añade otra causa. Esta
deduplicación de informes no deduplica eventos de insulina ni acredita historial
completo. Un permiso denegado tampoco se transforma en «sin conexión».

El informe no demuestra que se hayan evaluado todas las entradas necesarias.
No expone dosis, estado válido, autorización ni una puerta de cálculo; su
ausencia no demuestra aptitud para calcular. Tampoco constituye un registro
clínico durable. Persistencia, procedencia, serialización, huellas y validación
de snapshots siguen pendientes de contratos específicos.

## Criterios y verificación

`UnavailableInputReportTest` cubre rechazo de vacío, conservación de causas,
deduplicación exacta, orden determinista para todas las combinaciones del
contrato v1 y aislamiento frente a mutaciones del productor y consumidor.
Los casos son técnicos y sintéticos, sin parámetros ni golden vectors clínicos.

`scripts/verify.ps1` ejecuta las pruebas comunes en JVM. CI ejecuta también
`iosSimulatorArm64Test` en macOS y enlaza el framework compartido. La ejecución
de pruebas iOS requiere macOS; no se puede sustituir por el resultado de Windows.

Verificación local del 2026-09-05: `scripts/verify.ps1` finalizó con
`BUILD SUCCESSFUL`; 11 pruebas JVM, cero fallos y cero errores (6 del informe,
4 de causas individuales y 1 de identidad). `git diff --check` no detectó
errores. El diff solo incorpora contratos, pruebas, documentación y CI de Next:
no introduce dependencias de red, reglas clínicas ni escrituras a Legacy.
La ejecución CI de cada SHA se consulta en la PR y en el workflow de `main`.
