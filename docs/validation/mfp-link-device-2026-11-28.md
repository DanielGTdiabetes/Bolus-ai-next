# Validación en dispositivo del enlace MyFitnessPal — 2026-11-28

ADR 0032, segunda entrega. Fase 8.

## Qué se ejecutó

`.\scripts\verify.ps1 -DeviceTests` sobre el árbol de la rama
`claude/mfp-android` (base `88032e8`), en el Pixel 10 Pro Fold (`rango`,
Android 17, API 37, build `CP3A.261005.005`), único dispositivo en
`adb devices`, con el propietario presente, la pantalla encendida y el
teléfono desbloqueado.

Resultado: Gradle `BUILD SUCCESSFUL`, vectores de Legacy reproducidos en
`bcac891`, instrumentación **`OK (198 tests)`**. Las APK de prueba se retiraron
al terminar; Next quedó instalada con la versión de esta rama.

Clases nuevas: `MfpWebSessionDeviceTest` (7) y `MfpLinkDeviceTest` (2).

## Qué demuestra

- El `WebView` real ejecuta el guion de extracción sobre una página sintética
  con la estructura del ADR 0032 y el módulo común la interpreta: identidad,
  alimentos, unidad `ración`, totales exactos, `1,234` como separador de miles
  y fibra desconocida cuando un alimento trae `n/a`.
- Una petición a un dominio ajeno no sale: recibe la respuesta local vacía, no
  llega a la prueba que hace de red y su host se lista.
- Una dirección fuera de MyFitnessPal no se abre.
- Una página que no es el diario da `mfp.page.unexpected`.
- El `WebView` informa el estado HTTP de una página rechazada: un 403 da
  `mfp.page.session_rejected`, no un fallo de transporte.
- Dos lecturas son dos cargas.
- En instrumentación no se puede crear una sesión que llegue a la red ni
  cerrar la sesión real.
- Pantalla: enlace apagado por defecto, sin `WebView` ni botones de lectura y
  sin ninguna petición; encenderlo no conecta; un día mal escrito no lee; la
  lectura hace dos cargas y no guarda nada; importar crea el borrador
  `mfp:2026-01-15:breakfast` revisión 1 con fibra sin dato; reimportar no
  escribe; apagar retira la sesión y la lectura.

## Qué no demuestra

Nada de esto llegó a MyFitnessPal. No hay evidencia todavía de que el inicio de
sesión funcione dentro del `WebView`, de qué dominios necesita la página real,
de que la tabla real tenga la forma descrita, ni de que una segunda sesión no
afecte a la de Hermes. Son los puntos 2 a 4 de «Pendiente con el Pixel y el
propietario» del ADR 0032.

## Incidencias de la ejecución

1. Tres pruebas existentes afirmaban que la app no pide `INTERNET`
   (`NavigationDeviceTest`, `AndroidDexcomSenderEvidenceTest`,
   `DexcomG7ReceiverDeviceTest`). Ese cambio es el objeto de la entrega: ahora
   comprueban que los permisos de Android declarados son solo `INTERNET` y
   `QUERY_ALL_PACKAGES` y que el enlace está apagado.
2. Android 17 añade por su cuenta `android.permission.ACCESS_LOCAL_NETWORK` a
   una app con `INTERNET` que apunta a una versión anterior. Next no lo declara
   (`verify.ps1` comprueba el manifiesto); las dos pruebas lo admiten solo a
   él. El filtro de `MfpHosts` bloquea cualquier dirección que no sea de
   MyFitnessPal, también las locales.

## Estado del teléfono

Antes de las pruebas, el receptor Dexcom de Next se puso en su estado por
defecto, porque varias pruebas lo esperan así:

```text
adb shell run-as org.bolusai.next pm default-state --user 0 org.bolusai.next/.glucose.dexcom.DexcomG7Receiver
```

Al terminar se volvió a habilitar y `dumpsys package org.bolusai.next` lo
muestra en `enabledComponents`:

```text
adb shell run-as org.bolusai.next pm enable --user 0 org.bolusai.next/.glucose.dexcom.DexcomG7Receiver
```

Entre ambos momentos Next no recibió lecturas reales. Dexcom, Legacy, la app
del reloj y la red del teléfono no se tocaron. El interruptor guardado del
enlace MyFitnessPal no se leyó ni se cambió: sigue apagado.
