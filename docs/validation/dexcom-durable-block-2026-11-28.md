# Pixel: bloqueo durable de la recepción Dexcom — 2026-11-28

Diagnóstico de por qué Next no registraba lecturas de Dexcom en el Pixel desde
el 2026-11-15, y decisión del propietario que lo resuelve. Fase 5, ADR 0023.
`main` de Legacy ese día: `e48c4ab896751091f323379b17c6211aff793487`; no se
consultó ni modificó Legacy, Dexcom ni el reloj.

## Resumen

- **Causa:** un bloqueo durable `dexcom_reception.producer_version_changed`,
  escrito por la puerta de permiso el 2026-11-15 a las 07:15:26 (hora local,
  +01:00) bajo la versión de política `adr0023-i2b-2026-10-12`. En ese instante
  llegó un mensaje con `com.dexcom.g7` instalado en un `versionCode` distinto
  de 4537. Es el comportamiento aprobado (ADR 0023, parte I, punto 5): el
  bloqueo se conserva aunque después vuelva la versión anterior.
- **No es la causa:** `com.gluroo.app` y `com.socialdiabetes.android` no tienen
  el permiso; el firmante de Dexcom no cambió.
- **Decisión del propietario, 2026-11-28:** «el valor de la glucosa se debe
  leer directamente de la app de Dexcom modificada; desbloquéalo». Se levanta
  con la versión de política `adr0023-i2b-2026-11-28`, con las mismas anclas.

## Método

Pixel 10 Pro Fold (`rango`), Android 17, build `CP3A.261005.005`, usuario 0,
único en `adb devices`. Solo lectura: `dumpsys package`, `pm path`,
`cmd package query-receivers`, `run-as org.bolusai.next ls/stat/grep` sobre
`databases/` y `adb pull` de las APK para `apksigner verify --print-certs`
(copias borradas del PC al terminar). No se abrió la app, no se cambió
ningún permiso, componente ni ajuste, y no se leyó ni copió ningún valor de
glucosa: de `dexcom-observations.db` solo se extrajeron, por coincidencia de
texto en el propio teléfono, las cadenas `dexcom_reception.*` y `adr0023-*`.

## Observado

**Titulares efectivos de `com.dexcom.cgm.EXTERNAL_PERMISSION`** (`granted=true`
en `dumpsys package packages`): exactamente `com.dexcom.g7`,
`org.bolusai.companion`, `com.wtachtsugar` y `org.bolusai.next`. Definición:
declarante `com.dexcom.g7`, protección `dangerous`.

| Paquete | UID | `versionCode` | Firmante SHA-256 (APK instalada) | Instalación / última actualización |
|---|---|---|---|---|
| `com.dexcom.g7` | 10481 | 4537 | `d30e0d3f…c74145`, igual al ancla | 2026-11-15 08:25:20 / 2026-11-15 09:47:25 |
| `org.bolusai.companion` | 10450 | 14 | `1d1f25bf…4f6a8d`, igual al ancla | 2026-06-25 / 2026-10-17 |
| `com.wtachtsugar` | 10350 | 3 | `1d1f25bf…4f6a8d`, igual al ancla | 2026-06-27 / 2026-08-09 |
| `org.bolusai.next` | 10495 | 1 | `1d1f25bf…4f6a8d` | 2026-09-06 / 2026-11-28 |

Ninguno comparte UID. Con esta lectura, la comparación de la política da
puerta abierta; lo único que cerraba la recepción era el bloqueo guardado.

**Dexcom se desinstaló y se volvió a instalar el 2026-11-15.** Su fecha de
primera instalación es de ese día y su UID pasó de 10451 (inventario L1) a
10481. La APK base instalada hoy tiene SHA-256
`3ffd191e678551433d0f7f4f1f973b0253358ce6c7f3f545debc4aa8f615142d`, distinto
del `6746ac78…` del inventario L1: es otro binario con el mismo paquete, la
misma versión 4537 y el mismo firmante (certificado `CN=Debug`). Las anclas
aprobadas no prueban igualdad binaria (riesgo ya descrito en el ADR 0023).

**Registro de Next.** `dexcom-observations.db` se modificó por última vez el
2026-11-15 07:15:26 +01:00 y contiene una única fila de bloqueo:
`adr0023-i2b-2026-10-12` / `dexcom_reception.producer_version_changed`.
La política compara firmantes antes que versión, así que en ese mensaje el
firmante coincidía y el `versionCode` no era 4537. Qué versión era no queda
guardado. Orden de los hechos: mensaje con otra versión a las 07:15, nueva
instalación de Dexcom a las 08:25 y actualización a la APK actual a las 09:47.

**Receptor de Next:** componente habilitado, permiso concedido,
`QUERY_ALL_PACKAGES` concedido, app no detenida.

**Otros receptores de la acción.** `cmd package query-receivers` lista también
`com.gluroo.app` y `com.socialdiabetes.android`. Declarar un receptor no da
el permiso: SocialDiabetes no lo solicita y Gluroo lo solicita pero no lo
tiene concedido. No forman parte de los titulares y no afectan a la puerta.

**Diagnóstico de Next (no visto en pantalla; deducido del código y del estado
anterior).** `dexcom_receiver.component=enabled`,
`dexcom_receiver.policy=pending`, `dexcom_reception.next_permission=granted`,
`dexcom_reception.gate_now=open` y los cuatro titulares. `gate_now` es una
comparación nueva y no consulta los bloqueos guardados: mostraba la puerta
abierta mientras la recepción estaba bloqueada. El bloqueo solo aparecía en
Inicio y Bolo («Recepción bloqueada por un cambio…»). Ver «Pendiente».

## Opciones y decisión

| Opción | Efecto | Riesgo |
|---|---|---|
| **A. Versión nueva de política con las mismas anclas (elegida)** | Levanta el bloqueo; Dexcom sigue anclada a 4537 y a su firmante | Se acepta el binario reinstalado el 15-nov sin revisarlo: las anclas no distinguen dos APK con igual paquete, versión y firmante. Las lecturas del 15 al 28-nov no se recuperan salvo las que Dexcom reenvíe |
| B. Mantener el bloqueo | Next sigue sin glucosa; Legacy no se ve afectada | Next no puede probarse con lecturas reales |
| C. Dejar de anclar la versión de Dexcom (como Legacy y reloj) | Una actualización de Dexcom con el mismo firmante ya no bloquea | Ancla más débil sobre un certificado de depuración de custodia no documentada. No se aplica: no se ha pedido |

El propietario eligió levantar el bloqueo. No se amplía el conjunto aprobado,
no se cambian anclas y no se toca Dexcom, Legacy, el reloj ni otras apps. La
aceptación mantiene el alcance del ADR 0023: recepción y presentación
técnica; sin autoridad de tratamiento.

## Cambio en Next

`DexcomApprovedPermissionSet.POLICY_VERSION` pasa a `adr0023-i2b-2026-11-28` y
la anterior queda en `PREVIOUS_POLICY_VERSIONS`. La fila de bloqueo se conserva
en el registro (tabla append-only) y deja de cerrar la puerta. La regla no
cambia: otra versión de Dexcom, otro firmante u otro titular vuelven a bloquear
de forma durable, ahora bajo la versión nueva. Prueba:
`DexcomPermissionGateTest.aBlockOfASupersededPolicyVersionNoLongerClosesTheGateAndIsKept`.

## Pendiente

- **Si Gluroo obtiene el permiso** (lo solicita), el conjunto de titulares
  cambia y la recepción se bloqueará otra vez de forma durable. Lo mismo si
  Dexcom se actualiza a otra versión. Ambos casos requieren decisión nueva.
- **Diagnóstico no muestra el bloqueo durable.** Conviene añadir a sus líneas
  la causa guardada para la versión vigente, con su prueba. No se cambia aquí.
- Comprobar en el Pixel, tras instalar esta versión, que vuelven a entrar
  mensajes (recuentos, sin valores).
