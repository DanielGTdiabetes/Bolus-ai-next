# ADR 0032: enlace MyFitnessPal por sesión en el propio móvil

- Estado: **propuesto**. Recoge la auditoría, las decisiones ya tomadas por el
  propietario y lo que falta para poder implementar. No hay código todavía
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

Acceso intentado el 2026-11-28: el BMAX ya no existe; el mini PC actual
responde por SSH en su dirección de Tailscale, pero su clave de host no está
aceptada en este equipo y no se aceptó sin consultar al propietario.

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
- Mismo control de coherencia: total de hidratos frente a la suma de alimentos.
  El umbral de 1 g es un valor de Legacy; se adopta como candidato observado.
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

1. **El código del adaptador del mini PC**, en solo lectura y sin su `.env`:
   `mfp_adapter.py`, `mfp_wrapper.py` y `cookie_auth.py`. El propietario decide
   cómo darlo: aceptar la clave de host para leerlo por SSH o copiar los
   ficheros.
2. Una respuesta real del diario, saneada, para escribir el analizador contra
   algo verdadero y dejarla como fixture sin datos personales.
3. La prueba controlada de la segunda sesión (riesgo del apartado 1).

## Alternativas descartadas

- Health Connect: descartado por el propietario.
- Que Next lea de `bolus_ai` lo que Hermes ya importó: mantiene la dependencia
  del servidor que se quiere eliminar.
- Reutilizar las cookies del mini PC: crea justo el conflicto que hay que
  evitar y saca un secreto de su sitio.
