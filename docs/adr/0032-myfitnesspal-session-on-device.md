# ADR 0032: enlace MyFitnessPal por sesión en el propio móvil

- Estado: **aceptado; primera entrega implementada** (módulo común
  `shared/mfp-import`, sin red ni sesión). La segunda entrega (red, `WebView`,
  importación al borrador y pruebas en el Pixel) sigue pendiente y necesita al
  propietario
- Fecha: 2026-11-28
- Fase y criterio de aceptación: fase 8 (enlace MyFitnessPal con independencia
  del cálculo local)
- Responsables de decisión: propietario para el alcance y el acceso; Claude
  para lo técnico
- Evidencia clínica requerida: ninguna para el transporte. Los datos importados
  son nutrición declarada por un tercero y siempre pasan por revisión del
  usuario antes de entrar en un cálculo

## Decisiones del propietario (2026-11-28)

1. Health Connect queda descartado: en su uso real llega con mucho retraso y
   falla sin avisar.
2. El enlace se hace como en Hermes, con la sesión web de MyFitnessPal
   (cookies), pero dentro del móvil y sin servidor: ni NAS, ni Render, ni el
   mini PC.
3. **No puede crear conflicto con el sistema actual de `bolus_ai`.**

## Auditoría de Legacy

Solo lectura, `main` `e48c4ab896751091f323379b17c6211aff793487`.

Ruta actual, según `docs/HERMES_MYFITNESSPAL_BOLUS_SYNC.md`:

```text
MyFitnessPal Android -> Companion (detecta el cierre de la app)
  -> trigger en el mini PC (POST /mfp/sync-now)
  -> Hermes lee el diario web con cookies
  -> POST /api/integrations/nutrition en bolus_ai
  -> revisión en Telegram -> cálculo
```

En el repositorio de `bolus_ai` están el disparador
(`scripts/hermes/mfp_sync_trigger.py`) y el sincronizador
(`scripts/hermes/sync_to_bolus.py`). De ellos se obtiene el contrato:

- Lectura: `mfp_adapter.get_day(fecha)` devuelve `date` y `meals`. Cada comida
  trae `meal` (o `slot`), `carbs_g`, `fat_g`, `protein_g`, `fiber_g`,
  `calories` y `foods`; cada alimento, `name`, `quantity`, `unit` y los mismos
  macros.
- Se ignoran las comidas cuya suma de hidratos, grasa, proteína y fibra es 0.
- Identidad y revisión separadas: `meal_id = hermes-mfp:<fecha>:<comida>` no
  cambia al editar; `meal_revision` es el SHA-256 del JSON canónico de fecha,
  comida, totales redondeados a 2 decimales y alimentos.
- Estabilidad: dos lecturas separadas 4 s; si cambian, una tercera. Solo se
  acepta una comida con dos huellas consecutivas iguales (máximo 3 lecturas).
- Nunca se reutiliza una lectura antigua: si falla el envío, la siguiente
  activación vuelve a leer.
- `bolus_ai` bloquea el cálculo si faltan los alimentos, si el total de
  hidratos de MyFitnessPal y la suma de los alimentos difieren en más de 1 g o
  si la estabilidad no está confirmada.
- La lectura se dispara solo al cerrar MyFitnessPal, tras confirmar 4 s el
  cierre y esperar 2 s. No hay lectura periódica: leer con la app abierta puede
  traer una comida a medias.
- Las cookies se renuevan con un perfil de Chrome en el mini PC; captcha,
  desafío de Cloudflare o consentimiento exigen intervención manual.
- La hora de la comida no viene de MyFitnessPal: Legacy asigna una fija por
  tipo de comida (08:30, 14:00, 17:30, 21:00). Es un valor inventado por
  Legacy; Next no lo copia.

**Lo que no está en el repositorio:** el código que habla con MyFitnessPal
(`mfp_adapter.py`, `mfp_wrapper.py`, `cookie_auth.py`, `refresh_cookies.py`).
Vive en el mini PC, en `/opt/hermes-mcp/myfitnesspal`. Sin él no se conocen las
direcciones exactas ni el formato de la respuesta, y no se van a inventar.

### Adaptador del mini PC, leído el 2026-11-28

Con permiso del propietario se leyó por SSH, en solo lectura y con la clave ya
configurada (alias `evo-x2`), el código del adaptador en el EVO-X2. No se abrió
su `.env`, ni `config/`, ni `logs/`, ni ninguna cookie.

No hay ninguna interfaz privada. El adaptador usa la librería pública
`myfitnesspal` 2.1.2 (`requirements.txt`), que descarga la página web del diario
y la analiza:

- **Petición.** `GET https://www.myfitnesspal.com/food/diary?date=AAAA-MM-DD`
  con las cookies de la sesión. La librería construye `/food/diary/<usuario>` y
  el adaptador quita el usuario (`_rewrite_diary_url`); admite también
  `/es/food/diary`. Cabeceras de navegador normales.
- **Otras dos peticiones que Next no necesita:**
  `https://www.myfitnesspal.com/user/auth_token?refresh=true` y
  `https://api.myfitnesspal.com/v2/users/<id>`, solo para obtener el nombre de
  usuario. La segunda falla a veces con 500 y el adaptador lo tolera.
- **Tabla.** Cada comida empieza en una fila `tr` con clase `meal_header`. Su
  primera celda es el nombre de la comida, en minúsculas; las demás son los
  nombres de las columnas, en minúsculas, y `carbs` se lee como
  `carbohydrates`. Las columnas las elige el usuario en MyFitnessPal: la fibra
  solo aparece si la tiene configurada. La librería usa las columnas de la
  primera cabecera para todas las comidas.
- **Alimentos.** Las filas siguientes sin atributo `class` son los alimentos;
  la primera fila con clase termina la comida. El nombre es el texto del enlace
  `a` de la primera celda. El valor de cada celda es el texto del
  `span.macro-value` si la celda tiene hijos, o su texto. Las celdas que
  sobran (botón de borrar) se ignoran.
- **Números.** Se eliminan todos los caracteres salvo dígitos, `-` y `.` y se
  convierte a número. **Si no se puede, la librería devuelve 0.** Next no copia
  eso: un valor ilegible es desconocido.
- **Cantidad y unidad.** Salen del nombre con la expresión
  `(.+), (\d[\d\.]*) ([\w\(\)]+)(?: \(.*\))?`: nombre corto, cantidad y unidad.
- **Totales de la comida.** La librería los calcula sumando los alimentos; no
  lee un total de la página. Por eso el control de Legacy «total frente a suma
  de alimentos, 1 g» compara dos valores que salen de las mismas filas.
- **Respuestas que no son el diario** (`_detect_bad_response`): la dirección
  final contiene `/account/ad_consents` (hay que aceptar el consentimiento en un
  navegador); el título contiene `myfitnesspal | challenge` o `captcha`, o el
  HTML contiene `cf-challenge` (desafío); título `myfitnesspal | blocked`
  (sesión, IP o cookies bloqueadas); HTTP 401 o 403 (sesión rechazada); 429
  (límite de peticiones); 500 o más. Si llega la página de inicio de sesión no
  hay ninguna `meal_header`. El texto `diary is locked with a key` indica un
  diario protegido.
- El adaptador reintenta hasta 3 veces con espera creciente.

## Diseño propuesto

### 1. Sin conflicto con `bolus_ai`

- **Sesión propia.** El usuario inicia sesión en MyFitnessPal dentro de Next.
  Next nunca lee, copia ni modifica las cookies del mini PC.
- **Solo lectura.** Únicamente peticiones de lectura del diario. Nada se
  escribe en MyFitnessPal.
- **Nada hacia Legacy.** Lo importado se guarda como borrador local. Next no
  llama a `/api/integrations/nutrition`, ni al disparador de Hermes, ni a
  Telegram.
- **Solo a petición.** Una lectura empieza con una acción explícita del
  usuario. Sin lecturas periódicas ni en segundo plano, para no provocar
  límites de MyFitnessPal que afecten a Hermes.
- **Interruptor.** El enlace nace apagado y se puede apagar; apagado, Next no
  abre ninguna conexión.
- Riesgo abierto: que MyFitnessPal reaccione a una segunda sesión desde otro
  dispositivo cerrando la primera o pidiendo verificación. Hay que probarlo de
  forma controlada, con el propietario presente, antes de darlo por bueno.

### 2. Sesión y red

- Primer uso de red en Next: permiso `INTERNET`, limitado por código a los
  dominios de MyFitnessPal. El cálculo local sigue funcionando sin red.
- Inicio de sesión en un `WebView` de la app. La contraseña la teclea el
  usuario en la página de MyFitnessPal; Next no la ve ni la guarda.
- Las cookies se quedan en el almacén privado de la app. Nunca en registros,
  copias de seguridad, fixtures ni en el repositorio. Auto Backup sigue
  desactivado.
- Sesión caducada, captcha o consentimiento: estado visible y específico con
  la acción que debe hacer el usuario. Nunca se muestra como «sin conexión» ni
  se reutiliza una lectura anterior como actual.

### 3. De la lectura al borrador

- Mismo contrato que Legacy: identidad `mfp:<fecha>:<comida>` y revisión por
  SHA-256 del contenido canónico, para distinguir comida nueva, revisión y
  reimportación idéntica.
- Misma regla de estabilidad: dos lecturas consecutivas iguales.
- El control de coherencia de Legacy (total de hidratos frente a la suma de
  alimentos, 1 g) no se adopta: la auditoría del adaptador mostró que compara
  dos valores salidos de las mismas filas. En Next el total es la suma.
- Importación a un borrador de comida revisable con hidratos, grasa, proteína y
  fibra como total en gramos, conservando origen, identidad, revisión, instante
  de lectura y lista de alimentos. Un macro que MyFitnessPal no dé queda sin
  dato, nunca `0`.
- Reimportar la misma revisión no crea nada. Una revisión nueva crea una
  revisión del mismo borrador, sin borrar la anterior.
- Sin hora de comida inventada.
- El usuario revisa, edita o descarta antes de usarlo en Bolo, donde ya se
  toman hidratos, fibra, grasa y proteína de la revisión (ADR 0029).

### 4. iOS

Mismo modelo de dominio y contrato. El inicio de sesión usaría `WKWebView` y su
almacén de cookies. Sin verificar.

## Lo que falta para implementar

1. Una página real del diario, saneada, para comprobar el analizador contra
   algo verdadero y dejarla como fixture sin datos personales. Hasta tenerla,
   el fixture se construye con la estructura descrita arriba.
2. El inicio de sesión del propietario en MyFitnessPal dentro de Next.
3. La prueba controlada de la segunda sesión (riesgo del apartado 1).

## Plan de implementación

Primera entrega, sin teléfono ni sesión: módulo común `shared/mfp-import`.

- Entrada: lo que la página trae, como texto sin interpretar (dirección final,
  título, marcas de desafío y de diario protegido, cabeceras y filas).
- Interpretación con las reglas de arriba y estas diferencias de seguridad:
  número ilegible o negativo es desconocido, no `0`; una columna que el usuario
  no tiene configurada deja ese nutriente desconocido; el total de una comida
  es desconocido si lo es alguno de sus alimentos, nunca una suma parcial.
- Fallos con código estable: consentimiento, desafío, bloqueo, sesión
  rechazada, límite de peticiones, diario protegido, página inesperada, host
  que no es `www.myfitnesspal.com` por HTTPS.
- Identidad `mfp:<fecha>:<comida>` y revisión SHA-256 del texto canónico, con
  la función de hash inyectada para no duplicar otra implementación.
- Estabilidad: dos lecturas consecutivas iguales, máximo tres, con la pausa de
  4 s de Legacy como constante observada. Se devuelven las comidas estables y
  la lista de las que cambiaron. Nunca se reutiliza una lectura anterior.
- Se omiten las comidas sin alimentos.
- El control de 1 g de Legacy no se adopta: en Next el total es la suma.

Segunda entrega, con el Pixel y el propietario: permiso `INTERNET` limitado a
MyFitnessPal, pantalla con `WebView` para iniciar sesión y leer el diario en la
misma sesión del navegador (la página se carga en el `WebView` y se extraen las
filas con un guion mínimo; la interpretación queda en el módulo común),
interruptor apagado por defecto, importación al borrador de comida con su
procedencia y pruebas en dispositivo con una página local saneada.

## Primera entrega implementada (2026-11-28)

Módulo `shared/mfp-import`, paquete `org.bolusai.mfp`, Kotlin Multiplatform
como `shared/meal-drafts`. No depende de ningún otro módulo ni de APIs de
plataforma, no abre conexiones y nada lo usa todavía; `scripts/verify.ps1` lo
comprueba. No se usó el teléfono, la red ni ninguna sesión. Legacy no se
volvió a leer: las reglas salen de este ADR.

- **Entrada** (`MfpDiaryPage`): dirección final, título, marca `cf-challenge`,
  marca de diario protegido, estado HTTP si la plataforma lo conoce (si no,
  `null`; nunca se supone 200) y, por comida, los textos de las celdas de la
  cabecera y las filas de alimentos (nombre y textos de celdas). Todo texto
  sin interpretar.
- **Interpretación** (`MfpDiaryInterpreter`): nombre de comida y columnas sin
  espacios alrededor y en minúsculas, `carbs` = `carbohydrates`, columnas de
  la primera cabecera para todas las comidas, celdas sobrantes ignoradas. Se
  leen hidratos, grasa, proteína, fibra y calorías; las demás columnas se
  ignoran. Las comidas sin alimentos se omiten.
- **Números** (`MfpAmount`): milésimas exactas en `Long`, sin `Double`. Valor
  conocido o desconocido con motivo: `mfp.value.column_absent`,
  `column_ambiguous`, `cell_absent`, `unreadable`, `negative` y
  `food_unknown`. El total de una comida es la suma exacta de sus alimentos y
  es desconocido si lo es alguno. Desconocido nunca vale `0`.
- **Fallos** (`MfpFailure`), en este orden: `mfp.page.host_not_allowed`,
  `consent_required`, `challenge`, `blocked`, `session_rejected` (401, 403),
  `rate_limited` (429), `server_error` (500 o más), `diary_locked`,
  `unexpected` (otro estado que no sea 2xx o ninguna cabecera de comida),
  `date_not_confirmed` y `ambiguous_meals`. `mfp.read.transport_failed` queda
  para el puerto de lectura.
- **Identidad y revisión**: `mfp:<fecha>:<comida>` y SHA-256 del texto
  canónico `mfp-meal-revision-v1` (una línea por dato, orden fijo, `\`, salto
  de línea y retorno de carro escapados, desconocido escrito como `unknown`).
  El hash llega por el puerto `MfpSha256`. `compare` distingue comida nueva,
  revisión nueva y reimportación idéntica.
- **Estabilidad** (`ReadStableMfpDiary`): puertos `MfpDiaryReader` y
  `MfpPause`. Dos cargas separadas por la pausa de 4 s; si difieren, una
  tercera. Devuelve las comidas de la última carga cuya revisión coincide con
  la anterior y la lista de identidades que cambiaron, aparecieron o
  desaparecieron entre las dos últimas. Si una carga falla no se devuelve nada
  de las anteriores, y cada llamada vuelve a cargar.

Diferencias de seguridad añadidas al implementar, además de las del plan:

1. **Coma decimal.** La librería de Legacy borra las comas, así que `1,5` se
   leería como `15`. Aquí una coma solo se acepta como separador de miles
   (entre dígitos y seguida de exactamente tres dígitos); cualquier otra deja
   el valor desconocido.
2. **Forma del número.** Tras el filtrado solo se acepta `dígitos` o
   `dígitos.dígitos` con un máximo de 9 enteros y 3 decimales (límite de
   representación, no clínico). `.5`, `5.`, `1.2.3` o más decimales son
   desconocidos.
3. **Día confirmado.** La dirección final debe llevar `date=` con el día
   pedido; si falta o es otro, `mfp.page.date_not_confirmed`. Evita atribuir a
   un día el diario de otro si MyFitnessPal redirige.
4. **Identidades únicas.** Una comida sin nombre o dos con el mismo nombre dan
   `mfp.page.ambiguous_meals`. Una columna repetida en la cabecera deja ese
   nutriente desconocido.
5. **Cantidad y unidad.** La expresión es la de este ADR con las letras y
   dígitos Unicode escritos de forma explícita, porque `\w` solo es Unicode en
   Python; así `ración` no se corta. La cantidad se guarda como texto.

Pendiente de comprobar con una página real (punto 1 de «Lo que falta»): el
texto exacto de las cabeceras (el extractor debe entregar el texto anterior a
cualquier elemento hijo, como hace la librería) y si la versión `/es/` usa el
punto como separador de miles; en ese caso `1.234` se leería como 1,234 y no
como 1234. Si un nombre de columna no coincide, el nutriente queda
desconocido, no en `0`.

Pruebas: 28 en `commonTest` con datos sintéticos (interpretación, cada motivo
de desconocido, cada fallo, host, fechas, texto canónico, identidad y
revisión, estabilidad a la segunda y a la tercera, inestable, comidas añadidas
o quitadas, fallo sin reutilizar lecturas y reimportación idéntica).

## Alternativas descartadas

- Health Connect: descartado por el propietario.
- Que Next lea de `bolus_ai` lo que Hermes ya importó: mantiene la dependencia
  del servidor que se quiere eliminar.
- Reutilizar las cookies del mini PC: crea justo el conflicto que hay que
  evitar y saca un secreto de su sitio.
