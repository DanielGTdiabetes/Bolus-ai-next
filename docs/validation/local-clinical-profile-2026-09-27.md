# Perfil clínico local versionado — 2026-09-27

Base Next: `fe466bf` (PR #27 integrada). CI de esa base comprobado antes de
editar: [Verify 36331996354](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36331996354),
conclusión `success` para el SHA exacto. Primer paso del hito 6 conforme al
[ADR 0012](../adr/0012-local-clinical-profile.md), aprobado por el propietario
junto con las ocho recomendaciones de su sección 12 y la regla de unidades de la
sección 4.4. Legacy no se consulta ni se modifica.

## Comportamiento y límites

Ajustes → Cálculo muestra «Perfil clínico local». Sin perfil guardado todo aparece
«Sin configurar». Crear o editar abre un editor que se guardará como la versión
siguiente: unidad de glucosa (sin configurar, mg/dL o mmol/L), zona IANA con
botón explícito para usar la del dispositivo y un valor de día completo para
ratio de hidratos (g/U), sensibilidad y objetivo. Cada campo muestra el valor
canónico que se guardará o el código del error. Un campo vacío es «sin
configurar» y `0` es un valor.

Guardar crea una versión inmutable con fecha, origen, escritor y huella. La
consulta de versiones, en solo lectura, muestra cada versión con su propia unidad.
Restaurar exige confirmación en pantalla, avisa si la unidad difiere de la actual
o si se descartará un editor pendiente, carga el contenido exacto y al guardar
crea una versión nueva `restored`. Editar tras restaurar la convierte en `manual`
con la versión de partida registrada.

Cambiar de unidad vacía y bloquea sensibilidad y objetivo. Esa versión se guarda
sin valores dependientes y los valores en la unidad nueva se introducen en la
versión siguiente. Nada se convierte.

Las versiones con varias franjas se muestran completas y no se pueden editar
desde la UI. Bolo sigue mostrando «Perfil · sin versión confirmada», no lee el
perfil y mantiene cálculo y confirmación bloqueados. No hay propuestas del
sistema, aprendizaje, conversión, exportación, importación, sync ni red.

## Garantías revisadas

| Área | Garantía | Evidencia |
|---|---|---|
| Creación de base | `clinical-profile.db` v1 en la transacción del helper. Un fichero con objetos ajenos o `user_version` futura falla con `profile.storage.unsupported_schema` sin tocarse | `unknownFilesAndFutureSchemasFailClosedWithoutBeingTouched` |
| Datos existentes | `meal-drafts.db` sigue en v3, sin objetos de perfil, con revisiones y selección intactas | `closedFileCopyReopensAndValidatesAndMealDatabaseIsIndependent` |
| Inmutabilidad | triggers abortan `UPDATE`, `DELETE` e inserción de franjas en versiones antiguas. Filas comparadas byte a byte | `triggersKeepEveryVersionImmutable` |
| Atomicidad | fallo a mitad de franjas revierte versión y franjas. El reintento funciona | `failedWriteRollsBackVersionAndSegmentsThenRetrySucceeds` |
| Concurrencia | editor obsoleto → `revision_conflict` sin filas. Dos conexiones simultáneas: exactamente una escribe la versión siguiente | `identicalRetryAndStaleEditorAddNoRows`, `twoConnectionsCannotBothWriteTheNextVersion`, UI de conflicto |
| Idempotencia | reintento idéntico devuelve la versión confirmada, también tras versiones posteriores | pruebas comunes y SQLite |
| Restauración | copia exacta con huella igual. `restored` con huella distinta, `manual` idéntico a la fuente, fuente inexistente u origen reservado rechazados | `ProfileVersioningTest`, `restorationPersistsProvenance…` |
| Huellas | SHA-256 puro en común con vectores FIPS 180-4 y comparación con `MessageDigest` en 301 tamaños | `ProfileFingerprintTest`, `ProfileFingerprintPlatformTest` |
| Integridad al leer | valor alterado, unidad alterada, decimal no canónico, parámetro desconocido, franja ausente o incompleta, huella falsa, origen reservado, fuente colgante, huecos y esquema de contenido desconocido fallan cerrado | `tamperedRowsFailClosedOnRead` y pruebas de historial |
| Unidades (4.4) | nadie puede leer un valor con otra unidad: editor, política en la transacción y validación de la cadena al leer | ver tabla siguiente |
| Backup | Auto Backup y transferencia siguen excluidos y `verify.ps1` lo comprueba. Copia cerrada del fichero reabre y valida. Copia manipulada falla | `verify.ps1`, prueba de copia |
| Motor | `shared/bolus-engine` y `ReadOverview` no referencian el perfil (`verify.ps1`). Bolo no muestra vistas de perfil | `bolusStaysBlockedAndDoesNotUseTheSavedProfile` |

### Pruebas específicas de unidad

| Caso | Resultado esperado | Pruebas |
|---|---|---|
| Valores dependientes sin unidad | rechazado (`unit_required`) en dominio, edición y lectura | `ProfileUnitTest`, lectura manipulada |
| Cambiar mg/dL → mmol/L en el editor | ISF y objetivo vacíos y bloqueados, ratio intacto, nada convertido | común y UI |
| Volver a la unidad inicial en el mismo editor | los valores borrados no vuelven | común y UI |
| Mismos números con otra unidad escritos a mano | `unit_change_with_values`, cero filas nuevas | común y SQLite |
| Unidad nueva con valores nuevos en la misma versión | `unit_change_with_values` | común y SQLite |
| Guardar el cambio de unidad solo | versión nueva sin dependientes. Versión anterior idéntica byte a byte | SQLite y UI |
| Valores en la unidad nueva | aceptados en la versión siguiente | común, SQLite y UI |
| Declarar unidad por primera vez | permitido, no reinterpreta nada | común |
| Restaurar versión en otra unidad | copia exacta con su unidad original y aviso en la confirmación | común y UI |
| Restaurar y pasar sus valores a otra unidad | rechazado | común |
| Fila manipulada con huella recalculada que reinterpreta valores | historial falla con `invalid_record` | común y SQLite |

## Verificación

Comandos ejecutados en Windows desde la rama de trabajo:

- `scripts/verify.ps1`: clean, compilación, lint con avisos como errores, pruebas
  comunes y Android/JVM, manifests, exclusiones de backup, independencia del
  motor, workflow y `git diff --check`. Resultado: correcto.
- `scripts/verify.ps1 -DeviceTests` en Pixel 10 Pro Fold (API 37), único
  dispositivo autorizado, desbloqueado y con `KEYCODE_WAKEUP` cada 10 s, sin
  cambiar ajustes, red ni modo avión. Resultado: `OK (72 tests)` en 56,9 s.

| Suite | Casos | Nuevos |
|---|---:|---:|
| Motor KMP/JVM | 11 | 0 |
| Borradores de comida KMP/JVM | 17 | 0 |
| Perfil clínico KMP/JVM | 43 | 43 |
| Android/JVM | 42 | 2 |
| Instrumentación USB | 72 | 20 (12 SQLite, 8 UI/modelo) |

Las clases nuevas `SqliteClinicalProfileRepositoryDeviceTest` y
`ClinicalProfileDeviceTest` están en la lista `-e class` de `verify.ps1`.
`NavigationDeviceTest` y `DarkThemeDeviceTest` usan ahora también un perfil en
memoria.

Incidencias durante la entrega:

- lint `PluralsCandidate` en tres textos con `%1$d` seguido de palabra. Se
  reformularon;
- primera ejecución USB con 71/72: una prueba insertaba desde una conexión sin
  claves foráneas y esperaba que la FK actuase. La prueba activa ahora las FK como
  el adaptador y añade el caso contrario: una fila colgante escrita sin FK falla
  al leer.

## Alcance de la evidencia

Bases sintéticas aisladas y repositorios en memoria. No se abre la base normal
del teléfono. Los valores de prueba son sintéticos y no son recomendaciones ni
valores por defecto. La recreación se prueba con el `ViewModel` retenido y el
estado guardado del editor usa la codificación canónica. La muerte del proceso no
se simula. iOS sigue pendiente de verificación.
