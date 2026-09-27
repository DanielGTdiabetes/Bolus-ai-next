# Consulta de revisiones guardadas — 2026-09-27

Base Next: `dcb3a3d` (PR #24 integrada). CI de esa base comprobado antes de
editar: [Verify 36243680925](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36243680925),
conclusión `success` para el SHA exacto. Incremento preparatorio de fases 2, 4 y 7
conforme al [ADR 0010](../adr/0010-local-meal-revision-history.md).

## Comportamiento y límites

Mis platos y Comidas permiten abrir «Ver revisiones guardadas · solo lectura»
desde la biblioteca o desde el editor de un registro guardado. La vista muestra
todas las revisiones, de la más reciente a la más antigua, con recuento, última
revisión, identidad, origen, los seis campos exactos, ausencia frente a `0`
escrito y base declarada. Un fallo de lectura muestra el motivo y un reintento
explícito, nunca «sin revisiones». Una historia con huecos, duplicados o deriva
de identidad se rechaza como `meal.storage.invalid_record`.

No hay selección de revisiones antiguas, restauración, edición, borrado,
interpretación nutricional, ingesta, sync ni red. La selección de Bolo no se lee
ni se modifica. SQLite sigue en v2, sin migración. Cálculo y tratamiento
permanecen bloqueados en todos los estados.

## Verificación

Comando: `scripts/verify.ps1 -DeviceTests` (incluye clean, compilación, lint con
avisos como errores, pruebas comunes y Android/JVM, manifests y `git diff --check`).
Pruebas USB en el Pixel 10 Pro Fold (API 37) desplegado y desbloqueado. No se
cambia red, modo avión ni configuración del teléfono.

| Suite | Casos | Evidencia |
|---|---:|---|
| Núcleo KMP/JVM | 11 | Contratos de bloqueo existentes. |
| Borradores/selección/historial KMP/JVM | 11 | 4 nuevos: orden, texto exacto, ausencia frente a fallos, ID en blanco e historias inconsistentes. |
| Android/JVM | 40 | Navegación y fronteras existentes. |
| Instrumentación USB | 44 | 36 existentes, 2 SQLite y 6 UI/modelo nuevos. |

Casos nuevos SQLite: lectura tras reinicio, ausencia, clase incorrecta, texto
`0`, espacios y referencia de copia conservados; selección obsoleta y filas
intactas tras consultar. Una tabla perdida devuelve `meal.storage.read_failed` y
una base previa desconocida `meal.storage.unsupported_schema`, no ausencia.

Casos nuevos UI: plato y borrador con selección obsoleta en Bolo, orden
declarado, ausencia de acciones de escritura, recreación y Atrás hacia la
biblioteca; la selección de Bolo sigue obsoleta con su snapshot. Desde el editor,
la consulta y la recreación conservan los cambios sin guardar y no crean
revisión. Un fallo muestra el motivo y solo el reintento explícito carga la
historia; un ID sin revisiones muestra ausencia. Una lectura cerrada o
sustituida no muestra su resultado tardío. Layouts 840×900, 900×840 y 411×914 dp
con texto normal y 1,8× sin recortes ni elipsis.

Primera ejecución: build, lint y 62 pruebas JVM correctos; en USB `43/44` porque
el teléfono estaba plegado con la pantalla apagada y
`NavigationDeviceTest.captureAppOnlyReviewImagesAndVerifyNarrowLargeTextLayout`
midió la barra inferior con ancho 0. Con el teléfono desplegado y desbloqueado,
la repetición íntegra terminó con `BUILD SUCCESSFUL`, 62 pruebas JVM sin fallos y
`OK (44 tests)` en USB (41,081 s). Los paquetes de prueba se desinstalaron y Next
quedó actualizado por instalación conservando sus datos.

## Alcance de la evidencia

Bases sintéticas aisladas y repositorios programados en memoria; no se lee ni
modifica la biblioteca normal del teléfono. No se consulta ni modifica Legacy.
La recreación se prueba con el `ViewModel` retenido; la restauración tras muerte
del proceso usa el mismo `Bundle` que el editor pero no se simula. iOS sigue
pendiente de verificación.

## Corrección tras revisión de la PR #25

La revisión automática de Codex señaló que la continuidad se comprobaba
expandiendo `newest.revision downTo 1`. Una fila corrupta con una revisión
positiva enorme, admitida por la restricción SQLite, podía agotar memoria o
bloquear la app antes de detectar el hueco. La validación compara ahora el
recuento con la revisión más reciente, exige que la más antigua sea 1 y revisa
solo pares adyacentes. Una prueba común con `Long.MAX_VALUE`,
`Int.MAX_VALUE + 1` y 50 000 000, solas y tras revisiones válidas, confirma
`meal.storage.invalid_record` sin expandir el rango.

Verificación de la corrección con `scripts/verify.ps1 -DeviceTests`: 63 pruebas
JVM sin fallos y `OK (44 tests)` en USB (42,467 s). Un intento previo repitió el
fallo de `NavigationDeviceTest` porque la pantalla entró en reposo durante la
compilación; la ejecución válida envió `KEYCODE_WAKEUP` cada 10 s, sin cambiar
ajustes del teléfono.
