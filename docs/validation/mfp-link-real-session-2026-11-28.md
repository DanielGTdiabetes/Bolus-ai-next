# Validación del enlace MyFitnessPal con la sesión real — 2026-11-28

ADR 0032, segunda entrega. Fase 8. Con el propietario presente, en el Pixel 10
Pro Fold (`rango`, Android 17, API 37), con la versión de depuración de la rama
`claude/mfp-android`. Ningún dato del diario, nombre de usuario, cookie ni
contraseña entra en el repositorio: aquí solo hay lo observado sobre la forma
de la página y el comportamiento.

Legacy no se modificó. El adaptador de Hermes se releyó en el mini PC en solo
lectura (`mfp_adapter.py`, `main.py`), sin abrir su `.env` ni su configuración;
el registro del sincronizador se leyó con permiso expreso del propietario.

## Qué hizo el propietario y qué se vio

| Paso | Resultado |
|---|---|
| Encender el enlace y abrir MyFitnessPal | La página de inicio de sesión carga dentro de Next |
| Iniciar sesión, solo con hosts de MyFitnessPal | Falla: error de verificación de captcha. Bloqueados: `challenges.cloudflare.com` y cuatro de analítica, telemetría y consentimiento |
| Iniciar sesión tras admitir `challenges.cloudflare.com` como recurso | Correcto: se ve el diario |
| Leer el día de hoy | `mfp.page.date_not_confirmed`: la dirección final es `/food/diary`, sin `date=` |
| Leer el día anterior | Correcto: tres comidas, dos cargas iguales; la dirección conserva `date=` |
| Leer hoy tras confirmar el día con `input#date_selector` | Correcto: dos comidas, dos cargas iguales |
| Comparar totales con MyFitnessPal | Coinciden (comprobado por el propietario) |
| Importar una comida | «Importada como borrador nuevo (revisión 1)» |
| Cerrar la app de MyFitnessPal (disparador de Hermes) | Hermes leyó con su sesión: 200, mismas comidas, `no_changes`, 0 fallos. Visto también en el log de la app de Legacy |
| Cambiar en la web la columna de sodio por fibra y leer de nuevo | Columnas: calories, carbohydrates, fat, protein, fiber, sugar. Fibra con valor en las dos comidas, igual que en la web |
| Importar una comida no importada antes | «Importada como borrador nuevo (revisión 1)» |
| Reimportar la misma versión | «Ya estaba importada esta misma versión»; sin escritura |
| Reimportar la comida importada antes sin fibra | «Importada como revisión 2 del mismo borrador»; la revisión 1 conserva la fibra sin dato y la 2 guarda `0` |

## Qué se aprendió de la página real

- La tabla tiene la forma descrita en el ADR 0032: `tr.meal_header`, filas de
  alimentos sin `class`, valores legibles con las reglas del módulo común.
- Las cabeceras llegan en inglés, aunque la web del propietario en el
  ordenador esté en español. Columnas del diario al empezar: calorías,
  hidratos, grasa, proteína, sodio y azúcar, **sin fibra**. El propietario
  cambió después sodio por fibra desde la web, sin versión de pago.
- La página declara su día en `<input type="hidden" id="date_selector"
  value="AAAA-MM-DD">`. También lleva enlaces al día anterior y al siguiente
  con `date=`, que Next no usa.
- Para el día de hoy MyFitnessPal quita `date=` de la dirección. Hermes recibe
  la misma dirección sin fecha.
- La página pide más de una docena de dominios de publicidad y analítica. Next
  no deja salir ninguno y el diario se lee igual.
- La página contiene enlaces que escriben (`copy_meal`, con método POST). El
  guion de Next no pulsa nada y la sesión nunca envía un POST por su cuenta.

## Cambios que salieron de esta validación

1. `MfpHosts`: `challenges.cloudflare.com` admitido solo como recurso de una
   página.
2. `MfpDiaryPage.declaredDates` y nueva regla de confirmación del día: al menos
   una fuente declara el día y ninguna declara otro. Protocolo de extracción
   `mfp-extract-v2` con líneas `day`.
3. La pantalla muestra las columnas del diario y, si la lectura falla, la
   dirección final de la última página leída.

## Fibra: diferencia con Legacy

Mientras la tabla no tuvo columna de fibra, Legacy recibió fibra `0` en todas
las comidas de MyFitnessPal: el adaptador de Hermes devuelve `0.0` cuando el
nutriente no está. Next, en el mismo caso, la deja sin dato y Bolo la pide.

Desde que el propietario añadió la columna, Hermes envía a `bolus_ai` la fibra
real. Si el descuento de fibra está activo allí, las comidas con fibra pueden
calcularse con menos hidratos netos que antes, y Hermes puede volver a
presentar como revisión las comidas del día ya importadas. Se le advirtió de
ambas cosas. Next no modificó nada de Legacy.

No se ha decidido ninguna regla para la fibra ausente: es una decisión del
propietario, menos urgente ahora que la columna existe.

## Límites de esta evidencia

- La comprobación de Hermes cubre los minutos posteriores al inicio de sesión
  en Next. Una invalidación tardía de la sesión se vería como fallos de sesión
  en Hermes; conviene vigilarlo unos días.
- No se guardó una página real saneada como fixture.
- La página se sirvió en inglés; el separador de miles de la versión `/es/`
  sigue sin comprobar.
- No se comprobó qué ocurre cuando un recurso permitido redirige a otro
  dominio (ADR 0032, «Riesgo abierto: redirecciones de recursos»).
- No se probó cerrar la sesión desde Next ni una lectura con la app de
  MyFitnessPal editando a la vez.
