# Investigación de recepción local Dexcom G7 en Android — 2026-09-06

## Decisión corregida con la evidencia del productor

El propietario confirma que la fuente primaria es su aplicación Dexcom G7
modificada, que ya produce localmente el broadcast usado por Bolus AI Legacy.
Bolus AI Next debe sustituir el sistema actual ejecutando el flujo completo en el
móvil; la red y la Dexcom Web API no forman parte del camino crítico.

El código del productor controlado por el propietario demuestra la acción, la
forma del `Bundle` y su distribución a las aplicaciones que poseen el permiso.
Esta evidencia se fija en el
[contrato de transporte observado v1](../contracts/modified-dexcom-g7-broadcast-observed-v1.md).
La autorización del propietario resuelve qué productor local se pretende usar,
pero aún no resuelve autenticidad robusta, release reproducible, políticas
clínicas, vigencia, identidad o persistencia. En consecuencia:

- `LocalGlucoseSourceResult` continúa sin variante de lectura disponible;
- `PendingDexcomSource` continúa devolviendo
  `input.glucose.policy_not_approved`;
- Next no registra un receiver, no solicita permisos Dexcom y no interpreta,
  persiste ni muestra payloads;
- R-010 y R-014 siguen bloqueando una lectura disponible.

Orden de fuentes acordado:

| Prioridad | Fuente | Papel |
|---|---|---|
| 1 | aplicación Dexcom G7 modificada | fuente local primaria y offline |
| 2 | Dexcom Web API | contingencia de emergencia, online y con retraso explícito |
| 3 | ausencia/error | estado bloqueante específico; nunca reutilizar un dato antiguo como actual |

La contingencia web deberá conservar procedencia, timestamp de medida y de
recepción, retraso y estado propios. No puede sustituir silenciosamente al
productor local ni presentarse como glucosa actual sin una política aprobada.

## Alcance, privacidad y snapshots

- Repositorio Next de partida:
  `930051cd58d8aacb45932e566f2f94e975cd62e3` (`origin/main`).
- Legacy se consultó solo como referencia histórica ya trazada. Antes de usarla
  se resolvió de nuevo `DanielGTdiabetes/bolus_ai@main` en
  `f5417721d8019a9831126f4d843edfc4de87653d`.
- Se usó ADB únicamente para metadatos de sistema, paquetes, permisos y filtros
  de intents. No se registró el número de serie, no se inspeccionaron extras de
  broadcasts, bases de datos, logs Dexcom ni lecturas clínicas.
- Se inspeccionó además, en solo lectura, el workspace de la aplicación modificada
  facilitada por el propietario. No se le hicieron cambios ni se leyeron datos
  clínicos.
- Fuentes web consultadas el 2026-09-06. La ausencia de documentación pública
  limita la integración oficial del proveedor, pero no invalida el contrato del
  productor modificado autorizado por su propietario.

## Evidencia oficial Dexcom

1. El [programa de desarrolladores de Dexcom](https://developer.dexcom.com/docs/)
   describe una API REST con OAuth 2.0. La autorización completa depende de la
   revisión de Dexcom y del estado de Digital Health Partner.
2. [Scopes & Access](https://developer.dexcom.com/docs/dexcom/scopes-access)
   exige autenticación del usuario y autorización para datos de producción. El
   acceso Limited/Full se concede por aplicación después de una solicitud y una
   revisión de Dexcom.
3. El [resumen de endpoints V3](https://developer.dexcom.com/docs/dexcomv3/endpoint-overview/)
   incluye G7 y EGV, exige bearer token OAuth 2.0 y endpoints HTTPS. La misma
   fuente indica que los datos subidos por las apps móviles aparecen con una
   hora de retraso en Estados Unidos y tres horas fuera de Estados Unidos.
4. La ayuda oficial para
   [aplicaciones partner conectadas](https://www.dexcom.com/en-us/faqs/not-seeing-dexcom-data-in-third-party-connected-partner-applications)
   exige consentimiento, Share activo, autorización de la aplicación partner y
   conexión a Internet.
5. La documentación pública revisada no define la acción
   `com.dexcom.cgm.EXTERNAL_BROADCAST`, su payload, unidades, timestamps,
   versionado, semántica offline ni un procedimiento para autenticar al emisor.

Por tanto, la Web API es una vía oficial posible únicamente para la contingencia
online. No puede sustituir la recepción local ni utilizarse como dato actual en
el hito Android offline.

## Evidencia oficial Android y análisis de autenticidad

La [guía de broadcasts de Android](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
explica que un receiver exportado puede recibir intents de otras aplicaciones y
que los permisos pueden limitar remitentes o destinatarios. También advierte que
otras aplicaciones pueden enviar broadcasts maliciosos a un receiver y que un
intent implícito no debe transportar información sensible sin protección.

La documentación de [custom permissions](https://developer.android.com/privacy-and-security/risks/custom-permissions)
indica que los niveles `normal` y `dangerous` aportan poca seguridad porque otras
aplicaciones pueden solicitarlos; recomienda controles de firma. El
[`android:permission` de un receiver](https://developer.android.com/guide/topics/manifest/receiver-element)
restringe qué broadcasters pueden invocarlo, pero la fuerza real depende del
nivel del permiso. En API 34 o superior,
[`getSentFromPackage()` y `getSentFromUid()`](https://developer.android.com/reference/android/content/BroadcastReceiver#getSentFromPackage())
permiten consultar la identidad inicial que Android atribuye al emisor. Sin
embargo, para aplicaciones con UID distinto el productor debe habilitar
[`BroadcastOptions.setShareIdentityEnabled(true)`](https://developer.android.com/reference/android/app/BroadcastOptions#setShareIdentityEnabled(boolean));
la opción es `false` por defecto y, sin ella, la identidad no está disponible.

Implicaciones para un adaptador futuro del productor modificado:

- un extra `packageName`, la acción del intent o el nombre del paquete instalado
  nunca bastan para autenticar al emisor;
- el receiver debe exigir un control de emisor aprobado por el propietario y
  fallar si Android no proporciona identidad;
- en API 34+, el productor debe compartir la identidad mediante
  `setShareIdentityEnabled(true)` y el receiver debe verificar que efectivamente
  la recibe; el envío observado usa `sendBroadcast(Intent)` sin esa opción y no
  cumple todavía el gate;
- la UID emisora debe resolverse con `PackageManager` y validarse contra anclas
  de paquete, firma y versión de una release reproducible del productor;
- en API 33 o inferior se debe bloquear la recepción hasta aprobar un mecanismo
  alternativo; nunca degradar a confiar en extras;
- el payload seguirá en cuarentena hasta validar esquema/versionado, tipos,
  unidad, timestamps, identidad y persistencia; ningún fallo de autenticación o
  parseo se relabelará como ausencia o desconexión.

## Evidencia del dispositivo, sin datos clínicos

En un único dispositivo conectado y autorizado por ADB se observó:

| Evidencia | Resultado |
|---|---|
| Dispositivo | Pixel 10 Pro Fold, Android 17, API 37 |
| Paquete | `com.dexcom.g7` |
| Versión | `1.6.1.4537` (`versionCode 4537`) |
| SHA-256 del APK instalado | `6746ac78f5c6eaa4b4ad802f0dec59e55a64b05821d0ff43de4e0f39c8e4b572` |
| SDK del paquete | `minSdk 29`, `targetSdk 31` |
| Permiso definido | `com.dexcom.cgm.EXTERNAL_PERMISSION` |
| Propietario del permiso | `sourcePackage=com.dexcom.g7` |
| Nivel observado | `dangerous` |
| Receivers para la acción histórica | cuatro paquetes instalados; Next no estaba entre ellos |
| Permisos Health Connect en el paquete Dexcom | no se observaron permisos `android.permission.health.*` |

Estos metadatos, aislados, no demuestran un contrato. La inspección separada del
productor sí demuestra que su código construye y distribuye el broadcast, pero
no demuestra por sí sola qué aplicación originó un intent recibido. Que el
permiso sea `dangerous` refuerza la necesidad de validar identidad y firma.

Health Connect sí define un tipo Android
[`BloodGlucoseRecord`](https://developer.android.com/health-and-fitness/health-connect/data-types),
pero eso no demuestra que G7 lo publique. La ayuda de Dexcom encontrada para
[Health Connect](https://www.dexcom.com/en-us/faqs/what-is-activity-logging)
solo documenta entrada de actividad desde Health Connect hacia G7. Sin evidencia
del productor, contrato y procedencia de los registros, no es una alternativa
autorizada en este lote.

## Reproducción segura de la inspección ADB

Los comandos siguientes no imprimen el identificador del dispositivo y filtran
la salida a metadatos no clínicos:

```powershell
$rows = @(
    adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '\S+\s+(device|offline|unauthorized)$' }
)
$states = @($rows | ForEach-Object { ($_ -split '\s+')[1] })
Write-Output "ADB device entries: $($rows.Count)"
Write-Output "ADB states: $($states -join ',')"

adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk

$dexcom = adb shell dumpsys package com.dexcom.g7
$dexcom | Select-String -Pattern `
    'versionCode=', 'versionName=', 'minSdk=', 'targetSdk=', `
    'com.dexcom.cgm.EXTERNAL_PERMISSION', 'android\.permission\.health\.'

adb shell dumpsys package permissions |
    Select-String -Pattern 'com.dexcom.cgm.EXTERNAL_PERMISSION' -Context 0,6

$receivers = adb shell cmd package query-receivers --brief `
    -a com.dexcom.cgm.EXTERNAL_BROADCAST
$receiverPackages = @($receivers | Where-Object { $_ -match '/' } |
    ForEach-Object { ($_ -split '/')[0].Trim() })
Write-Output "Receivers matching observed action: $($receiverPackages.Count)"
Write-Output "Next registered: $($receiverPackages -contains 'org.bolusai.next')"
```

No debe añadirse `dumpsys activity broadcasts`, `logcat`, inspección de extras,
archivos internos o bases de datos a este procedimiento: podrían contener datos
clínicos.

## Evidencia y decisiones que debe cerrar el propietario

Para el canal local modificado deben quedar versionados:

1. release reproducible que corresponda con la APK instalada;
2. identity sharing del productor en API 34+ y prueba de que el receiver obtiene
   paquete y UID; o un mecanismo autenticado alternativo;
3. anclas autorizadas de paquete, firma y rango de versiones;
4. contrato completo de campos, tipos y comportamiento ante desconocidos;
5. unidad y semántica de cada timestamp y tendencia;
6. identidad, duplicados, reordenación, backfill y entrega offline/reinicio;
7. alcance aprobado para visualización y posterior uso clínico.

La aprobación técnica no se interpretará como aprobación clínica. Para la
contingencia Web API siguen aplicando registro, OAuth, Partnership/Limited Access
y [Support Requests](https://developer.dexcom.com/docs/support/requests).

## Puertas para un lote posterior

Un cambio posterior solo podrá proponer recepción real si adjunta:

- release reproducible y contrato versionado del productor modificado;
- prueba instrumentada de identity sharing en API 34+ o del mecanismo
  autenticado alternativo;
- ADR actualizado con threat model del emisor y matriz versión/región;
- contrato Next explícito para unidad, timestamps, identidad y procedencia;
- política clínica de vigencia y anomalías aprobada separadamente;
- persistencia local durable y pruebas de fallo/reinicio/duplicado/orden;
- pruebas instrumentadas en Android para permiso denegado, fuente ausente,
  identidad no disponible/no válida, parseo, reloj y política pendiente;
- prueba real offline con datos minimizados, sin convertirla en golden clínico;
- revisión explícita del propietario. iOS permanece fuera de alcance según ADR
  0004.
