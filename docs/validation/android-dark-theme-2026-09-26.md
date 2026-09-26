# Tema oscuro Android — 2026-09-26

Base Next: `ddd29aac1e239d0278dad2c28575bb6c98a5788b` (PR #22 integrada).
CI de esa base:
[Verify 36229771764](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36229771764),
conclusión `success` para el SHA exacto. Incremento visual sobre ADR 0007 y ADR
0009; no introduce arquitectura de dominio ni cambia capacidades clínicas.

## Comportamiento

La aplicación sigue el modo claro/oscuro indicado por la configuración de
Android. `values-night` define la paleta y usa el tema nativo oscuro para los
controles; la versión diurna conserva su tema nativo claro. La barra de estado y
la de navegación adaptan el color de sus iconos. Los atributos de API 27/29 se
separan en sus recursos de versión, conservando minSdk 26.

Se usa una paleta explícita, con Force Dark desactivado desde API 29 para evitar
transformaciones automáticas adicionales. No se añade selector propio, preferencia
persistida, dependencia nueva ni permisos. Android gestiona los cambios de
configuración; los mecanismos existentes conservan borradores y selección.

Referencia técnica: [temas oscuros de Android](https://developer.android.com/develop/ui/views/theming/darktheme).
La comprobación de contraste usa 4,5:1 como mínimo para los pares de texto de la
paleta, conforme a la referencia de
[contraste mínimo de W3C](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html).
Es un criterio visual técnico, no un parámetro clínico ni una certificación
completa de accesibilidad.

## Verificación

Comando: `scripts/verify.ps1 -DeviceTests`, seguido de `git diff --check`.
El arnés incluye `DarkThemeDeviceTest` y conserva todas las suites anteriores.

- La primera prueba resuelve recursos y atributos nativos en claro/oscuro y
  comprueba 12 pares de texto/fondo por tema: texto principal, secundario, error,
  aviso de no autoridad, acentos y extremos del gradiente de Inicio.
- La segunda renderiza Inicio, Bolo con snapshot y editor de borrador en ambos
  temas, anchos de 360/840 dp y escalas de texto 1/1,8. Comprueba ausencia de
  recortes/elipsis, scroll utilizable, datos literales y cálculo/confirmación
  deshabilitados. El borrador conserva texto sin guardar y la selección no cambia.
- Se generan imágenes de Views sintéticos para revisar el aspecto nocturno en
  pantalla ancha. No son capturas del dispositivo ni contienen datos personales.
- La instrumentación usa bases en memoria o fixtures aisladas. No cambia el modo
  oscuro del sistema, conectividad, resolución, fuentes ni ajustes del teléfono.

Resultado final: compilación, lint y 57 pruebas JVM correctas; instrumentación
`OK (31 tests)` en 36,794 s sobre el Pixel 10 Pro Fold conectado por USB,
desplegado con pantalla interior de 2076 × 2152 píxeles. El APK actualizado queda
instalado; el arnés retira los APK de pruebas y emisor de fixtures al terminar.
`git diff --check` correcto. Revisadas visualmente las tres imágenes sintéticas
nocturnas de Inicio, Bolo y Comidas: textos, avisos y controles legibles.

Lint detectó inicialmente una referencia de test a un atributo de API 29 sin
guarda de versión y un índice de TypedArray sin recurso styleable. Se corrigieron
usando guardas y resolución explícita de atributos; no se suprimieron checks.

## Límites

No hay cambios en fórmulas, unidades, validación nutricional, almacenamiento,
ingestas, tratamientos, sync ni autoridad. No se consulta ni modifica Legacy.
Los estados ausentes, obsoletos y fallidos mantienen sus motivos y bloqueos.
Se prueba la resolución de temas mediante contextos Android aislados; no se
automatiza el ajuste global de tema del teléfono ni la bisagra del Fold. iOS sigue
fuera de la verificación de este host Windows.
