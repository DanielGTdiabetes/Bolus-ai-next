# Prueba de instalación Android del 2026-09-06

## Alcance y privacidad

Prueba técnica del APK no autoritativo en el dispositivo Android conectado. Se
registra el modelo y la versión del sistema, pero no el número de serie del
dispositivo. No se leyó información de Dexcom ni ningún dato clínico.

## Baseline probado antes de editar

- Commit: `364afefed6e9870a30ae791d89e099882a95ae6b` (`origin/main`, fusión de PR #9).
- Dispositivo: Pixel 10 Pro Fold, Android 17, API 37.
- Paquete local observado: `com.dexcom.g7`, versión `1.6.1.4537`,
  `versionCode 4537`, `minSdk 29` y `targetSdk 31`.
- Metadato de permisos observado: el paquete Dexcom define
  `com.dexcom.cgm.EXTERNAL_PERMISSION` con protección `dangerous`. Esta
  inspección de paquete no demuestra el contrato del payload, la autenticidad
  efectiva del emisor ni que Next deba solicitar el permiso.
- `scripts/verify.ps1`: `BUILD SUCCESSFUL`; generó el APK de depuración.
- `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`: `Success`.
- Apertura: `am start -W -n org.bolusai.next/.MainActivity` devolvió `Status: ok`,
  `LaunchState: COLD` y `TotalTime: 316` ms.
- Estado posterior: proceso de `org.bolusai.next` activo y
  `org.bolusai.next/.MainActivity` como actividad reanudada.
- Revisión visual posterior, confirmada con el dispositivo abierto: la app es
  una única pantalla y muestra `DESARROLLO · NO AUTORITATIVO`,
  `Sin lectura de glucosa`, `input.glucose.missing` y `Cálculo bloqueado`.

El dispositivo estaba bloqueado durante el primer intento automatizado de
inspección; después el propietario abrió la app y se capturó la jerarquía visible
sin datos clínicos. Esta evidencia no demuestra recepción Dexcom, funcionamiento
en modo avión, compatibilidad autorizada con Dexcom ni aptitud clínica.

## APK candidato del límite local

Tras añadir el puerto fail-closed se repitieron compilación, instalación y
arranque en el mismo dispositivo:

- instalación con `adb install -r`: `Success`;
- arranque en frío de `org.bolusai.next/.MainActivity`: `Status: ok`, 164 ms;
- actividad reanudada y visible;
- pantalla: `Política de validación de glucosa pendiente`,
  `input.glucose.policy_not_approved` y `Cálculo bloqueado`.

El APK candidato tampoco registró un receiver ni intentó leer Dexcom. Por tanto,
esta repetición valida la composición y presentación del bloqueo, no el primer
hito funcional offline.

## Verificación local del candidato

- La primera invocación directa de Gradle falló antes de ejecutar tareas porque
  `ANDROID_HOME` no estaba definido en esa consola; no fue un fallo de código.
- Repetida con la misma detección de SDK que el arnés:
  `:android:app:testDebugUnitTest`, `:android:app:lintDebug` y
  `:android:app:assembleDebug` finalizaron con `BUILD SUCCESSFUL`.
- Resultado Android: 8 pruebas, 0 fallos; lint sin incidencias bloqueantes.
- `scripts/verify.ps1`: `BUILD SUCCESSFUL`; 11 pruebas comunes JVM y 8 Android,
  0 fallos. Las puertas iOS se ejecutan únicamente en CI macOS.
- `git diff --check`: sin errores.

## Resultado de seguridad

El APK probado no declara `INTERNET`, no registra un receiver Dexcom, no solicita
permisos de glucosa y no calcula ni registra tratamientos. La prueba no ejecutó
escrituras ni llamadas contra Legacy.

R-012 queda mitigado para esta pantalla mediante compilación, instalación,
arranque y revisión visual del bloqueo. R-013 demuestra que el artefacto
provisional se instala en este dispositivo API 37, pero la compatibilidad Dexcom
y la versión mínima definitiva siguen pendientes.
