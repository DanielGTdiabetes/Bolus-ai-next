# ADR 0005: evidencia Android del emisor y prueba entre aplicaciones

- Estado: implementación técnica preparatoria; no aprueba recepción clínica
- Fecha: 2026-09-12
- Fase: preparación del primer hito Android de Fase 5

## Decisión

La fuente prevista sigue siendo la aplicación Dexcom modificada del propietario,
por broadcast local. Este incremento implementa la obtención de metadatos que
necesita el verificador existente. No utiliza servidores Dexcom.

`DexcomSenderEvidenceAuthentication` consulta un puerto de evidencia sin acceso
a `Intent`, extras, almacenamiento o red. Comprueba primero la política y la
identidad atribuida; solo resuelve un paquete cuando corresponde al emisor
esperado. La política pendiente no consulta Android. Una identidad ausente o de
otro paquete tampoco provoca búsquedas de paquetes instalados.

`AndroidDexcomSenderEvidenceSource` lee `sentFromPackage`/`sentFromUid` durante
`onReceive` y resuelve UID, versión exacta y firmantes mediante `PackageManager`.
En API inferior a 34 no obtiene identidad; la autenticación queda bloqueada.
Se distinguen paquete no resuelto, acceso denegado y firma no disponible. Los
defectos inesperados se propagan sin convertirse en falta de datos o de red.

Se comparan los certificados que firman **actualmente** el APK, usando
`apkContentsSigners`. La recomendación general Android de consultar el historial
de rotación no se aplica a esta política de release exacta: aceptar un firmante
histórico ampliaría el ancla aprobada. Cambios de firma requieren otra ancla
explícita. Se conserva el conjunto completo de firmantes.

La composición de la app permanece `PendingDexcomSource`. No se registra un
receiver productivo, no se pide permiso Dexcom y no existe una lectura disponible.
Autenticar al emisor no aprueba el contenido del mensaje.

## Pruebas y aislamiento

Un módulo `android/sender-fixture` genera una APK sintética sin red ni
almacenamiento. Protege su receiver con un permiso de firma propio, solicitado
solo por el manifest debug de Next. Las APKs de test comparten firma de
depuración, pero no UID. Envía exclusivamente una acción de prueba a `org.bolusai.next`.
Su receiver recibe un comando dirigido por componente y decide si comparte su
identidad. No declara ni emite acciones Dexcom/Legacy ni contiene datos clínicos.
Es una aplicación de distinto UID; las pruebas verifican esa diferencia.

El receiver consumidor se registra solo durante cada prueba instrumentada y se
retira en `finally`. Sus extras nunca entran en la frontera. Las anclas de test
corresponden a la APK sintética instalada, no a Dexcom, y no se guardan como
política productiva. La visibilidad del paquete sintético se declara solo en el
manifest debug. La APK fixture no es dependencia de la APK Next.

`scripts/verify.ps1` compila también instrumentación y fixture y ejecuta lint.
`-DeviceTests` añade ejecución explícita en un único dispositivo autorizado API
34+, conserva Next instalada y elimina únicamente las APKs desechables que haya
instalado. Rechaza sobrescribir fixtures preexistentes. Usa `am instrument` sin
recoger logcat, datos de otras aplicaciones ni identificadores del dispositivo.
No cambia conectividad global ni el modo de funcionamiento de la app Dexcom.

## Límites y alternativa iOS

La evidencia con el emisor sintético valida las APIs Android y los rechazos;
no certifica una release real de Dexcom. Continúan pendientes release
reproducible, anclas aprobadas, opt-in real de identidad, permisos, contrato de
lectura, vigencia, persistencia y prueba Dexcom en modo avión. Este incremento
no abre las puertas R-010/R-014 ni completa el primer hito funcional.

iOS no tiene esta API de broadcasts Android. Su integración queda pendiente de
un puerto específico; consume los mismos estados compartidos y no copia el
adaptador Android ni introduce otro motor. Se mantiene ADR 0004.

## Fuentes

Documentación oficial consultada el 2026-09-12:

- [BroadcastReceiver: identidad del emisor](https://developer.android.com/reference/android/content/BroadcastReceiver#getSentFromUid()).
- [BroadcastOptions: compartir identidad](https://developer.android.com/reference/android/app/BroadcastOptions#setShareIdentityEnabled(boolean)).
- [SigningInfo: certificados actuales e historial](https://developer.android.com/reference/android/content/pm/SigningInfo#getApkContentsSigners()).
- [AndroidX Test: runner 1.7.0 y JUnit 1.3.0](https://developer.android.com/jetpack/androidx/releases/test).

No se ha consultado ni ejecutado Legacy para este incremento; las referencias
históricas de otros documentos conservan sus SHA originales.
