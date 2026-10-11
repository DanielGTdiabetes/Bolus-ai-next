# Contrato de transporte observado de la app Dexcom G7 modificada, versión 1

- Fecha de evidencia: 2026-09-06
- Plataforma: Android
- Estado: transporte observado y autorizado por el propietario como fuente local
  primaria; todavía no autorizado como lectura clínica disponible
- Productor: aplicación `com.dexcom.g7` modificada y controlada por el
  propietario, no la API pública de Dexcom

## Alcance

Rectificación de alcance del propietario, 2026-10-11: la aplicación Dexcom ya
modificada e instalada es el productor que Next debe aprovechar para sustituir
Bolus AI. Su modificación no forma parte del trabajo de Next. La exigencia de
identity sharing pertenece al diseño estricto del consumidor y debe revisarse
en Next, no convertirse en una obligación de cambiar el productor. Véase la
rectificación del [ADR 0022](../adr/0022-dexcom-g7-receiver-disabled.md).

Este documento describe únicamente lo que construye y distribuye el productor
modificado. No aprueba una política de vigencia, un rango clínico, una regla de
tendencia, una identidad de lectura ni el uso del dato para cálculo. Tampoco
convierte la aplicación modificada en una integración soportada oficialmente por
Dexcom.

El propietario ha confirmado que esta aplicación es el origen local primario que
debe sustituir la dependencia de servidores. La Dexcom Web API se reserva para
contingencia y nunca puede hacerse pasar por una lectura local o actual.

## Evidencia fijada

| Evidencia | Identificador |
|---|---|
| APK instalada en el dispositivo autorizado | SHA-256 `6746ac78f5c6eaa4b4ad802f0dec59e55a64b05821d0ff43de4e0f39c8e4b572` |
| Paquete/versión instalada | `com.dexcom.g7` / `1.6.1.4537` (`versionCode 4537`) |
| Método productor en el workspace local no versionado `Deexom` | `apktool_smali/smali/com/dexcom/coresdk/g7appcore/persistence/repositories/TxServiceRoomRepository.smali`, SHA-256 `83e372b64132923b1aba02bcffaacdd3c45f5309bfc3bdd8b7f8604ef8a05e14` |
| Especificación del productor en `Deexom` | `BOLUS_AI_INTEGRATION_SPEC.md`, SHA-256 `06f2537094549a1eabcc02016d6686c4cc8ca993ddaf19f62c6b148e46332eed` |
| Release estable `v23` conservada en `Deexom` | SHA-256 `8c21fef7711d238a58246c85bc8de35c4b1bed55ae56701f3a54cb1c2f6f566b` |

El hash de la APK instalada no coincide con el artefacto estable `v23`. El
handoff del productor la identifica como una compilación posterior que mueve el
envío explícito a Legacy fuera de una rama ligada al inicio de sesión. Antes de
fijar una referencia de la versión instalada, esa APK debe archivarse e
identificarse mediante solo lectura. No reconstruirla ni reinstalarla para
satisfacer este inventario. Verificar un artefacto instalado no atribuye por sí
solo la autoría de los mensajes recibidos.

## Forma del transporte observada

El método productor construye un `Intent` con:

| Nivel | Nombre | Tipo Android observado | Construcción observada |
|---|---|---|---|
| acción | `com.dexcom.cgm.EXTERNAL_BROADCAST` | `String` | literal de la acción |
| extra superior | `sensorType` | `String` | literal `G7` |
| extra superior | `packageName` | `String` | `Context.getPackageName()` |
| extra superior | `glucoseValues` | `Bundle` | bundles de lectura indexados por cadenas `"0"`, `"1"`, etc. |
| lectura | `glucoseValue` | `Int` | valor EGV interno |
| lectura | `timestamp` | `Long` | timestamp interno dividido entre `1000` por el productor |
| lectura | `trendArrow` | `String` | nombre del enum interno |
| lectura | `transmitterId` | `String` | resultado de `getTxId()` |
| lectura | `egvTxTime` | `Long` | resultado de `getEgvTxTime()`, sin transformación observable |

La especificación del productor denomina `glucoseValue` en `mg/dL`. Next no
inferirá esa unidad por región, preferencias o por el tipo entero: debe
conservarla como afirmación versionada del productor y validarla en el límite
antes de crear una lectura disponible.

El productor filtra internamente valores fuera de `1..400`. Esto se registra
solo como comportamiento observado. No constituye un rango clínico aprobado ni
autoriza a Next a copiarlo, completarlo o usarlo como única validación.

La semántica y unidad de `egvTxTime`, la estabilidad real de `transmitterId`, el
orden, los duplicados, el backfill y el significado de todos los nombres de
tendencia siguen pendientes de contrato y pruebas. Un campo presente no equivale
a un dato válido.

## Distribución y autenticidad

La implementación observada:

1. envía una copia explícita a
   `org.bolusai.companion.dexcom.GlucoseReceiver` de Legacy;
2. consulta los paquetes que poseen
   `com.dexcom.cgm.EXTERNAL_PERMISSION`;
3. excluye Legacy para evitar el duplicado y envía una copia dirigida a cada
   paquete restante.

Las dos rutas observadas llaman a `Context.sendBroadcast(Intent)` sin opciones.
Por tanto, la compilación instalada no solicita a Android que comparta su
identidad con los receivers.

El permiso está definido por la aplicación modificada con nivel `dangerous`.
Esto explica cómo Next podría descubrirse como destinatario, pero no autentica
al emisor: otra aplicación puede solicitar el permiso y los extras/action son
controlables.

En Android 14/API 34 o superior, la identidad solo estará disponible si el
productor opta expresamente por compartirla. La ruta estricta implementada en
Next presupone que el productor cree `BroadcastOptions`, llame a
`setShareIdentityEnabled(true)` y pase el `Bundle` resultante a
`Context.sendBroadcast(Intent, String, Bundle)`. Android documenta que la opción
es `false` por defecto. El envío actual sin opciones no cumple ese requisito
del consumidor. No se exige modificar el productor: corresponde adaptar la
política de Next al transporte existente y documentar sus garantías reales.

En esa ruta estricta, después del opt-in el adaptador exige `getSentFromUid()` y
`getSentFromPackage()`, resolver el certificado con `PackageManager` y comparar
paquete, certificado y versión con una ancla aprobada del productor. Cualquier
identidad ausente, múltiple o no coincidente debe fallar cerrada con su motivo
específico, antes de parsear o persistir el payload. Véanse
[`BroadcastOptions.setShareIdentityEnabled`](https://developer.android.com/reference/android/app/BroadcastOptions#setShareIdentityEnabled(boolean))
y [`BroadcastReceiver.getSentFromUid`](https://developer.android.com/reference/android/content/BroadcastReceiver#getSentFromUid()).

El digest de firma anotado en el handoff debe contrastarse mediante solo lectura
con la APK instalada antes de fijar su referencia; no se requiere reconstruirla.
En API 33 o inferior, la ruta estricta permanece no disponible
hasta documentar y probar otro mecanismo de autenticación; no se degradará a
confiar en extras.

## Puertas aún bloqueantes

Antes de añadir `LocalGlucoseSourceResult.Available` deben aprobarse y probarse:

- identificación de la APK instalada (paquete/firma/versión), sin modificarla;
- política de recepción de Next compatible con el envío existente, con sus
  garantías, limitaciones y pruebas explícitas; no atribuir identidad por extras
  ni confundir la verificación de la APK con autenticación de cada mensaje;
- contrato de campos obligatorios, opcionales y desconocidos;
- unidad, timestamps de medida y recepción, zona/offset y anomalías de reloj;
- identidad, duplicados, reordenación y backfill;
- política clínica de vigencia y validación, separada del filtro observado;
- persistencia local durable y recuperación tras reinicio;
- permiso denegado, fuente ausente, emisor inválido, parseo e invalidez como
  estados distintos;
- prueba Android real sin dependencia de Internet y con datos minimizados,
  preservando el Bluetooth del sensor según la decisión del ADR 0022.

Las pruebas de la ruta estricta implementada deben conservar estas garantías;
no demuestran compatibilidad con el productor instalado. La política compatible
necesitará sus propias pruebas en la misma entrega:

- el broadcast genuino con identity sharing expone el paquete y la UID esperados;
- omitir identity sharing produce identidad no disponible y bloqueo explícito;
- un emisor distinto que copie action y extras no supera la validación de firma;
- el fallo de identidad ocurre antes del parseo y no persiste ningún payload.

Hasta entonces, `PendingDexcomSource` sigue devolviendo
`input.glucose.policy_not_approved` y la app no interpreta payloads. El parser
estructural de esta forma existe desde el
[ADR 0018](../adr/0018-dexcom-g7-payload-parser.md) en
`shared/dexcom-g7-payload`, sin consumidor: rechaza el mensaje completo ante
cualquier lectura mal formada, lista campos desconocidos por nombre, colapsa
duplicados idénticos, rechaza los conflictivos, no aplica rango al valor y
marca toda lectura como `NOT_APPROVED_FOR_USE`. El
[ADR 0019](../adr/0019-dexcom-g7-reception-composition.md) compone
autenticación, lectura de extras y parser sin receiver: todo resultado sigue
bloqueando la glucosa. El
[ADR 0022](../adr/0022-dexcom-g7-receiver-disabled.md) declara el receiver de
`com.dexcom.cgm.EXTERNAL_BROADCAST` desactivado por defecto y sin pedir
`com.dexcom.cgm.EXTERNAL_PERMISSION`: el productor no lo descubre y, con la
política `Pending`, todo emisor se rechaza antes de leer extras.

Next dispone de un [verificador preparatorio de identidad](android-dexcom-sender-authentication-v1.md)
que modela estos rechazos sin anclas reales ni acceso a payloads. Todavía falta
resolver en Next la compatibilidad con el envío existente y probarla; el
verificador JVM por sí solo no abre este gate ni justifica modificar Dexcom.
