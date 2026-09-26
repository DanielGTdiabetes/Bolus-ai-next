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
| USB | 29 | 8 autenticación, 7 navegación, 8 UI/ciclo de vida/Fold de comidas, 6 SQLite. |

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
`OK (29 tests)` en USB (33,547 s), incluido el ajuste de reanudación y la pantalla
interior del Fold. Las 57 pruebas JVM también pasan. Los APKs de
test y emisor sintético fueron retirados; `adb shell pm path` para ambos paquetes
no devolvió rutas. Next queda instalada con la versión verificada.

## Revisión de reanudación del editor

La revisión de `d5902f5c19` detectó que refrescar la selección incondicionalmente
en `onResume` reconstruía Comidas/Mis platos y perdía foco y scroll. Ahora se
refresca al reanudar solo los destinos de Bolo. Las notificaciones de lectura de
selección se separan y solo reconstruyen esos destinos; una lectura en vuelo
tampoco reconstruye el editor después de navegar fuera de Bolo. Una regresión
recorre los editores de plato y borrador con texto sin guardar, cursor, foco y
scroll, pausa/reanuda y comprueba que se conserva la misma vista y su estado.

Compilación, lint, las 57 pruebas JVM y la regresión USB del ajuste pasan. Una
ejecución previa quedó detenida por la guarda de dispositivo único al haber dos
dispositivos autorizados conectados; se repitió con el único teléfono autorizado
cuando el propietario lo dejó conectado.

La preparación de la regresión también reveló reposicionamientos durante el
dibujo y al recuperar foco. Se descartó un cambio de tamaño por teclado mediante
mediciones del viewport y de los insets. La prueba espera el dibujo antes de
medir, y la app captura la posición al pausar y la restaura después del dibujo,
sin relajar la igualdad de scroll ni las comprobaciones de identidad/foco/cursor.

## Pantalla interior del Pixel 10 Pro Fold

El propietario solicitó verificar la pantalla interior y desplegó el teléfono.
Las consultas de solo lectura identificaron `Pixel 10 Pro Fold`, densidad 390 y
pantalla exterior de 1080 × 2364 píxeles antes de abrirlo; después expuso
2076 × 2152 píxeles. No se cambiaron resolución, orientación, fuente ni ajustes
del dispositivo.

Una prueba adicional mide shells completos de Bolo con selección sintética y
editor local en viewports representativos de 840 × 900 y 900 × 840 dp, con
fuente normal y escala 1,8. Comprueba scroll utilizable, navegación dentro de
pantalla, textos sin recorte vertical ni elipsis, campos literales, selección
inmutable y bloqueos. Guarda imágenes de esos Views sintéticos, no capturas del
dispositivo ni datos personales. Se inspeccionaron visualmente las imágenes de
Bolo y editor con fuente 1,8, sin recortes; el contenido inferior sigue accesible
mediante scroll. Las pruebas existentes de recreación cubren
recuperación de selección y navegación; no se afirma haber automatizado la bisagra
ni todas las transiciones de plegado en pleno uso.

## Memoria de verificación remota

La ejecución [36214578011](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36214578011)
de `d5902f5c19` quedó atascada tras `OutOfMemoryError: Metaspace` y se canceló para
sustituirla por la verificación del ajuste. Su log confirma los límites implícitos
de Gradle: heap de 512 MiB y metaspace de 384 MiB. `gradle.properties` fija límites
técnicos de heap 2 GiB y metaspace 1 GiB, y `ExitOnOutOfMemoryError` para terminar
con fallo en lugar de dejar un daemon agotado indefinidamente. No se suprimen
tests, lint ni comprobaciones del workflow; estos valores no son parámetros
clínicos.

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
