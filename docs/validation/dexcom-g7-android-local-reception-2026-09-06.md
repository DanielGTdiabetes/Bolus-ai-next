# Investigación de recepción local Dexcom G7 en Android — 2026-09-06

## Decisión

No se ha demostrado un contrato Dexcom autorizado para que Bolus AI Next reciba
lecturas locales de G7 en Android. La única integración pública oficial
localizada es la Dexcom Web API, que requiere OAuth 2.0, autorización del usuario
y aprobación de acceso de Dexcom. Además depende de Internet y publica los datos
del móvil con retraso, por lo que no cumple el primer hito local/offline.

El broadcast y el permiso observados en el dispositivo son evidencia técnica de
una superficie instalada, no un contrato autorizado ni una autenticación
suficiente del emisor. En consecuencia:

- `LocalGlucoseSourceResult` continúa sin variante de lectura disponible;
- `PendingDexcomSource` continúa devolviendo
  `input.glucose.policy_not_approved`;
- Next no registra un receiver, no solicita permisos Dexcom y no interpreta,
  persiste ni muestra payloads;
- R-010 y R-014 siguen bloqueando la recepción real.

## Alcance, privacidad y snapshots

- Repositorio Next de partida:
  `930051cd58d8aacb45932e566f2f94e975cd62e3` (`origin/main`).
- Legacy se consultó solo como referencia histórica ya trazada. Antes de usarla
  se resolvió de nuevo `DanielGTdiabetes/bolus_ai@main` en
  `f5417721d8019a9831126f4d843edfc4de87653d`.
- Se usó ADB únicamente para metadatos de sistema, paquetes, permisos y filtros
  de intents. No se registró el número de serie, no se inspeccionaron extras de
  broadcasts, bases de datos, logs Dexcom ni lecturas clínicas.
- Fuentes web consultadas el 2026-09-06. La ausencia de documentación pública no
  prueba que Dexcom no disponga de un contrato privado para partners; prueba que
  Next todavía no lo posee ni puede implementarlo de forma autorizada.

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

Por tanto, la Web API es una vía oficial posible para una integración online
futura, pero no puede sustituir la recepción local ni utilizarse como dato actual
en el hito Android offline.

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
permiten consultar la identidad inicial que Android atribuye al emisor, aunque
pueden devolver identidad no disponible.

Implicaciones para un adaptador futuro, solo si Dexcom autoriza el contrato:

- un extra `packageName`, la acción del intent o el nombre del paquete instalado
  nunca bastan para autenticar al emisor;
- el receiver debe exigir un control de emisor definido por Dexcom y fallar si
  Android no proporciona identidad;
- si Dexcom proporciona anclas de firma/versionado, la UID emisora debe
  resolverse con `PackageManager` y validarse contra esas anclas; no se inventará
  un certificado confiable a partir del APK observado;
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
| SDK del paquete | `minSdk 29`, `targetSdk 31` |
| Permiso definido | `com.dexcom.cgm.EXTERNAL_PERMISSION` |
| Propietario del permiso | `sourcePackage=com.dexcom.g7` |
| Nivel observado | `dangerous` |
| Receivers para la acción histórica | cuatro paquetes instalados; Next no estaba entre ellos |
| Permisos Health Connect en el paquete Dexcom | no se observaron permisos `android.permission.health.*` |

Estos metadatos no demuestran que la aplicación emita actualmente un broadcast,
que lo haga offline, que alguno de los receivers reciba datos auténticos ni que
Dexcom autorice su uso. Que el permiso sea `dangerous` refuerza el bloqueo: una
aplicación distinta podría solicitarlo y su mera posesión no equivale a una
identidad Dexcom aprobada.

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

## Información que debe proporcionar o confirmar Dexcom

La solicitud de soporte/partnership debe pedir una respuesta escrita y
versionada para:

1. si `com.dexcom.cgm.EXTERNAL_BROADCAST` y
   `com.dexcom.cgm.EXTERNAL_PERMISSION` forman una API soportada para G7 Android,
   incluida la región y las versiones compatibles;
2. mecanismo de alta/autorización de Bolus AI Next y términos de uso aplicables;
3. contrato completo y versionado del payload, campos obligatorios/opcionales,
   tipos y comportamiento ante campos desconocidos;
4. unidad del valor y de la tendencia, sin inferencia por región o preferencias;
5. semántica, base temporal, zona/offset y precisión de cada timestamp;
6. identidad estable de la lectura, duplicados, reordenación y backfill;
7. garantías de entrega offline, tras reinicio y con restricciones de segundo
   plano;
8. autenticación del emisor: permiso exigido, UID/package verificable y anclas
   de firma/versionado suministradas por Dexcom;
9. cambios de contrato, deprecación, compatibilidad y canal de incidencias;
10. alcance autorizado para visualización, comparación en sombra y eventual uso
    clínico. La autorización técnica no se interpretará como aprobación clínica.

El canal público indicado por Dexcom es registrarse como developer y usar
[Support Requests](https://developer.dexcom.com/docs/support/requests) o el
proceso de Partnership/Limited Access. Hasta recibir y aprobar esa evidencia,
la decisión reproducible de Next es `policy_not_approved`.

## Puertas para un lote posterior

Un cambio posterior solo podrá proponer recepción real si adjunta:

- referencia Dexcom autorizada y versión exacta del contrato;
- ADR actualizado con threat model del emisor y matriz versión/región;
- contrato Next explícito para unidad, timestamps, identidad y procedencia;
- política clínica de vigencia y anomalías aprobada separadamente;
- persistencia local durable y pruebas de fallo/reinicio/duplicado/orden;
- pruebas instrumentadas en Android para permiso denegado, fuente ausente,
  identidad no disponible/no válida, parseo, reloj y política pendiente;
- prueba real offline con datos minimizados, sin convertirla en golden clínico;
- revisión explícita del propietario. iOS permanece fuera de alcance según ADR
  0004.
