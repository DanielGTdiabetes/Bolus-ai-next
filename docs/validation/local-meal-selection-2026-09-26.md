# Selección local para revisión en Bolo — 2026-09-26

Base Next: `79f6ee397d13c9f2d3cc74ea109c606b9739717e`.
Su CI integrado se comprobó antes de empezar:
[Verify 34748787390](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/34748787390),
conclusión `success` para ese SHA exacto.
Alcance: [ADR 0009](../adr/0009-local-meal-selection.md).
No fue necesario consultar Legacy ni sus despliegues.

## Contrato comprobado

- Selección explícita de un plato o borrador por ID y revisión guardada.
- Presentación de nombre, cuatro macros, notas, base/unidades declaradas, origen
  manual/copia y revisión; ausencia sigue siendo distinta de texto `"0"`.
- Persistencia transaccional y lectura consistente de snapshot/última revisión.
- Corrección append-only: la selección anterior queda obsoleta, conserva sus
  campos y requiere reselección. Un intento de seleccionar contenido obsoleto o
  distinto devuelve conflicto y no reemplaza la referencia durable.
- Fallo de lectura visible sin macros de una selección anterior presentados como
  actuales; fallo de escritura conserva la selección persistida.
- Cálculo, confirmación clínica y tratamiento bloqueados en todos los estados.

## Comandos y cobertura

Comando principal: `scripts/verify.ps1 -DeviceTests`. Ejecuta clean, pruebas
KMP/JVM y Android/JVM, compilación, lint, APKs, checks de manifests/arquitectura y
la instrumentación USB con datos sintéticos. Se ejecuta también `git diff --check`.

| Suite | Casos | Cobertura |
|---|---:|---|
| Núcleo KMP/JVM | 11 | Contratos de bloqueo existentes. |
| Borradores y selección KMP/JVM | 6 | Campos exactos, invariantes, obsolescencia, fallos y bloqueo universal. |
| Android/JVM | 40 | Navegación y fronteras existentes. |
| USB | 27 | 8 autenticación, 7 navegación, 6 UI/ciclo de vida de comidas, 6 SQLite. |

SQLite se prueba con bases de nombre aleatorio: cierre/reapertura, reintento,
conflicto, escritura abortada mediante trigger sintético y fallo de lectura. Una
fixture SQL independiente congela el esquema v1 con dos revisiones; la migración
v1 → v2 preserva ambas. Se crea una copia de esa fixture antes de migrar y se
comprueba que también puede reabrirse y migrarse conservando el contenido. No se
introduce una función de backup de producto ni una migración descendente.

La UI recorre tanto platos como borradores copiados: selección ausente, selección
guardada, recreación de Activity, corrección, retorno a Bolo con snapshot obsoleto,
cierre/relanzamiento con nuevo ViewModel y reselección explícita. No equivale a una
prueba de apagado físico ni de pérdida de energía.

## Incidencias de verificación

La primera compilación completa detectó dos errores de lint: redacción que se
interpretaba como plural y uso de `setText` literal en el test. Se corrigieron sin
suprimir checks. La primera instrumentación pasó SQLite y contratos, pero dos
pruebas de recreación no alcanzaron RESUMED: la ventana permanecía STOPPED en el
teléfono bloqueado. Se aplicaron los mismos flags locales de ventana que en
NavigationDeviceTest, con guarda de API 27 exigida por lint. No se modificó la
configuración del teléfono ni se desactivó su bloqueo.

Una ejecución posterior falló en la espera de dibujo de una prueba existente de
navegación; la repetición íntegra sin cambiar ni omitir esa prueba pasó los 26
casos. Durante revisión se detectó y corrigió un caso adicional: cerrar el
ViewModel mientras termina un guardado podía programar una lectura sobre el
ejecutor ya cerrado. Una prueba con barreras deterministas comprueba que el
guardado termina y no se solicita esa lectura tras cerrar.

Resultado final del arnés: código de salida 0, `BUILD SUCCESSFUL` y
`OK (27 tests)` en USB (26,956 s). Las 57 pruebas JVM también pasan. Los APKs de
test y emisor sintético fueron retirados; `adb shell pm path` para ambos paquetes
no devolvió rutas. Next queda instalada con la versión verificada.

## Aislamiento y límites

Hay un único dispositivo USB autorizado, API 37. Las actividades de prueba usan
la factoría interna del proceso para abrir bases sintéticas o SQLite en memoria;
no leen la biblioteca normal del dispositivo. Se conservan las comprobaciones
que impiden sobrescribir APKs de prueba preexistentes y que retiran los APKs
sintéticos instalados por el arnés.

No se lee logcat ni datos personales, no se cambia la conectividad y no se accede
a Legacy, Dexcom, NAS, Render o MFP. El manifest sigue sin permiso INTERNET,
receivers productivos ni permisos Dexcom. No se afirma una prueba en modo avión
real: se verifica un flujo exclusivamente local sin capacidad de red de la app.

No se añade parseo nutricional, regla clínica, hora de ingesta, perfil/IOB,
recomendación, confirmación clínica, historial clínico, outbox o sync. iOS sigue
sin verificación en este host Windows. Retirar explícitamente la selección y
consultar revisiones históricas en UI quedan pendientes.
