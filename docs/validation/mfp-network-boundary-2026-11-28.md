# Límite de red del enlace MyFitnessPal impuesto por el sistema — 2026-11-28

ADR 0032, «Límite de red impuesto por el sistema». Fase 8. Pixel 10 Pro Fold
(`rango`, Android 17, API 37, build `CP3A.261005.005`), WebView
`com.google.android.webview` 158.0.8127.46, versión de depuración de la rama
`claude/mfp-network-security-config`. El propietario conectó el teléfono para
estas pruebas.

Legacy, Hermes y su configuración no se leyeron ni se tocaron. Ninguna prueba
contactó con MyFitnessPal ni usó la sesión del propietario. No hay datos
personales en esta nota.

## Pregunta

¿Ve el filtro de `MfpHosts` (`shouldInterceptRequest`) el destino cuando un
recurso permitido responde con una redirección HTTP a otro dominio?

## Respuesta: no

| Qué | Cómo | Resultado |
|---|---|---|
| Redirección de un recurso | Servidor HTTPS en la interfaz local del teléfono; una página carga una hoja de estilo que responde 302 hacia otro nombre de host del mismo servidor | El servidor recibe la petición del destino. `shouldInterceptRequest` recibió la página y el recurso, **no el destino** |
| Redirección de la página mostrada | La página pedida responde 302 | `shouldOverrideUrlLoading` recibe el destino antes de pedirlo; al rechazarlo, el servidor no recibe esa petición |
| Código de Chromium, etiqueta `158.0.8127.46` | `android_webview/browser/network_service/aw_proxying_url_loader_factory.cc` | Tras una redirección, `InterceptedRequest::ShouldNotInterceptRequest` devuelve `true`: la petición sigue sin consultar a la app |

Una primera lectura del código de la rama principal (`FollowRedirect` acaba en
`Restart`) llevó a suponer lo contrario. La prueba en el teléfono lo
desmintió y la relectura encontró la condición que lo explica. Se deja escrito
porque es el motivo de comprobar en dispositivo y no solo leer.

La documentación de Android no describe este comportamiento. Un
`WebResourceResponse` no admite estados 3xx, por eso la página sintética de
`MfpWebSessionDeviceTest` no podía simularlo y hizo falta un servidor real en
el propio teléfono (`LoopbackTlsServer`, certificado autofirmado generado en
cada prueba en el almacén de claves de Android y borrado al terminar; ninguna
clave en el repositorio).

## Medida

`res/xml/network_security_config.xml`, declarada en el manifiesto: por defecto
no se confía en ninguna autoridad de certificación; solo `myfitnesspal.com`
con sus subdominios y `challenges.cloudflare.com` confían en las del sistema;
sin tráfico en claro.

| Qué | Cómo | Resultado |
|---|---|---|
| La plataforma lee la configuración como se pretende | Sin red: gestor de confianza por defecto de la app y `NetworkSecurityPolicy` | Cero autoridades aceptadas por defecto; tráfico en claro no permitido; confianza distinta solo para los hosts del enlace (`isSameTrustConfiguration`) |
| El `WebView` la aplica a un servidor real | Con red, `WebView` sin ningún filtro, `https://example.org/` | Rechazado: `SSL_UNTRUSTED` |
| El caso del riesgo | Con red: recurso del servidor local que redirige a `https://example.org/` | El filtro no ve el destino y el sistema lo rechaza: `SSL_UNTRUSTED`. No hay petición HTTP |
| Los hosts del enlace siguen funcionando | Con red: `https://challenges.cloudflare.com/turnstile/v0/api.js` | Carga sin error de certificado |
| El recurso coincide con `MfpHosts` | JVM, `MfpNetworkSecurityConfigTest` | 4 pruebas |

## Comandos y resultados

```powershell
.\gradlew.bat --no-daemon :android:app:testDebugUnitTest --tests "*Mfp*" :android:app:lintDebug :android:app:assembleDebug :android:app:assembleDebugAndroidTest
# BUILD SUCCESSFUL

adb shell am instrument -w -r -e mfpNetworkProbe true -e class org.bolusai.next.mfp.MfpNetworkBoundaryProbe org.bolusai.next.test/org.bolusai.next.ProfileIsolationTestRunner
# OK (3 tests)

.\scripts\verify.ps1 -DeviceTests
# salida 0; BUILD SUCCESSFUL; vectores de Legacy reproducidos; OK (202 tests),
# 198 anteriores y las 4 de MfpNetworkBoundaryDeviceTest
```

Antes de cada ejecución en el teléfono, `pm default-state` del receptor Dexcom
de Next con `run-as org.bolusai.next`; después, `pm enable`, comprobado en
`enabledComponents`. Entre ambos Next no recibió lecturas.

La sonda con red (`MfpNetworkBoundaryProbe`) no forma parte de
`verify.ps1 -DeviceTests`: sin `-e mfpNetworkProbe true` sus pruebas se
omiten. Hace como mucho una conexión anónima por prueba a `example.org` o a
`challenges.cloudflare.com`.

## Límites de esta evidencia

- Hacia el destino ajeno de una redirección siguen saliendo la consulta DNS,
  la conexión TCP y el saludo TLS con el nombre del host. No sale ninguna
  petición HTTP ni dato de la sesión.
- `localhost` y las direcciones de bucle local quedan fuera: Android les da
  una configuración propia que permite tráfico en claro (observado:
  `isCleartextTrafficPermitted("localhost")` es verdadero). Esas conexiones no
  salen del teléfono.
- No se observó ninguna redirección real de MyFitnessPal ni de Cloudflare a
  otro dominio; se probó el mecanismo, no su página.
- No se probaron web sockets ni service workers. La medida no depende de por
  dónde llegue la conexión, pero no hay prueba específica de esas dos vías.
- El comportamiento de las redirecciones es el de esta versión del `WebView`.
  Si una actualización lo cambia, `MfpNetworkBoundaryDeviceTest` falla y hay
  que actualizar el ADR; la medida no se debilita.
- La comprobación con la sesión real la hizo el propietario, no una prueba
  automática; su resultado está al final.

## Comprobación del propietario con la sesión real

La versión con la configuración nueva quedó instalada en el Pixel
conservando los datos de la app.

1. Menú → Ajustes del móvil → MyFitnessPal → «Abrir MyFitnessPal»: se ve el
   diario con la sesión que ya estaba iniciada, sin pedir de nuevo usuario y
   contraseña.
2. «Leer el diario» del día de hoy: las comidas y los totales coinciden con
   los de MyFitnessPal, como antes.
3. La lista de hosts bloqueados es parecida a la de antes (publicidad,
   analítica, consentimiento) y no aparece ningún error de certificado ni una
   página en blanco.
4. Si es posible, «Cerrar la sesión» y volver a iniciarla: la comprobación de
   Cloudflare aparece y el inicio de sesión termina bien. Es el paso que más
   depende de la configuración nueva. Tras hacerlo conviene comprobar, como la
   primera vez, que Hermes sigue leyendo al cerrar la app de MyFitnessPal.

**Resultado.** El propietario confirmó el 2026-11-28 que el inicio de sesión
y la lectura del diario funcionan con la versión nueva y autorizó integrar la
PR. No consta qué pasos de la lista siguió ni si repitió el cierre e inicio de
sesión del paso 4.

Si más adelante algún paso falla, anotar el texto y el código que muestre Next y los hosts
bloqueados: un host que MyFitnessPal necesite y no esté en la lista se añade
en `MfpHosts.kt` y en la configuración a la vez, a propósito.
