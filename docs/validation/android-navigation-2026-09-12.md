# Navegación Android — lote 2026-09-12, cierre 2026-09-13

Base Next: `1036ecc9a636ef3cb11ed9ca5d64582c5219896e` (PR #19).
Legacy remoto consultado, sin modificaciones:
`f5417721d8019a9831126f4d843edfc4de87653d`.
Alcance: [ADR 0007](../adr/0007-legacy-android-navigation.md) y
[matriz de pantallas/fuentes/pendientes](../audit/legacy-ui-2026-09-12.md).

## Comandos y cobertura

Entrada de verificación: `scripts/verify.ps1 -DeviceTests`, que incluye el mismo
build/lint/JVM/manifests de CI y ambas suites USB. Resultado final registrado en
la PR del cambio; los informes generados permanecen fuera de Git.

La ejecución final del 2026-09-13 terminó con código 0: compilación, lint,
manifests y las tres suites siguientes sin fallos. La instrumentación devolvió
`OK (14 tests)`. `git diff --cached --check` también quedó limpio. Tras instalar
el APK final se repitió el arranque: `Status: ok`, `LaunchState: COLD`.

| Suite | Casos | Cobertura |
|---|---:|---|
| KMP/JVM | 11 | Contratos comunes e identidad del motor, sin cambios clínicos. |
| Android/JVM | 40 | 30 regresiones existentes + 7 casos de navegación + 3 de resumen fail-closed. |
| Instrumentación USB, API 37 | 14 | 8 regresiones de autenticación sintética + 6 de interfaz. |

Los casos de interfaz recorren por controles visibles los 25 destinos y diez
secciones de Ajustes; comprueban pestaña activa, aviso persistente, ausencia de
campos de captura personal y operaciones deshabilitadas sin listeners. Las tres
entradas de cálculo (Bolo, Emergencia, Sin conexión) conservan códigos explícitos
de glucosa no aprobada, perfil ausente e IOB desconocido y no calculan/registran.

Se recrea la actividad dentro de Ajustes → Cálculo y se comprueban sección
seleccionada, pila y Atrás al origen, además de vuelta a Inicio. Se comprueba que
la app no tiene permiso INTERNET. El APK debug y el manifest release siguen sin
receivers productivos ni permisos Dexcom; la dependencia de UI no amplía esas
capacidades.

Se revisaron visualmente las imágenes de Inicio, Bolo y Más dibujadas desde el
árbol de Views de Next. También se verifica el contenido a 320 dp con texto 1,8×,
y el shell completo a 320×640 dp con texto ampliado y 640×360 dp horizontal. No se
cambia la fuente ni orientación global del teléfono. Estos tamaños son escenarios
técnicos de prueba, no parámetros clínicos. No se afirma haber validado TalkBack
o todas las versiones/dispositivos Android.

Con texto muy ampliado la barra inferior permite desplazamiento horizontal para
mantener los nombres completos, en lugar de partir palabras. Su destino activo
permanece a la vista. Se conserva la distribución de cinco botones al tamaño normal.

## Dispositivo y aislamiento

Se instaló `android/app/build/outputs/apk/debug/app-debug.apk` en el único teléfono
USB autorizado. `adb shell getprop ro.build.version.sdk` devuelve 37.
Las APK de test y emisor sintético se eliminan al finalizar; Next permanece
instalada. Se comprueba arranque con:

```powershell
adb shell am force-stop org.bolusai.next
adb shell am start -W -n org.bolusai.next/.MainActivity
```

Las capturas se escriben en `files/ui-review` de Next y se extraen usando
`adb exec-out run-as org.bolusai.next cat files/ui-review/<nombre>.png` hacia
`build/`. No incluyen barras de estado, otras apps ni datos clínicos; no se
recopila logcat ni se leen bases personales.

Durante la primera ejecución conjunta el teléfono entró en reposo y los
controles aún no dibujados hicieron fallar las comprobaciones de visibilidad.
El arnés espera al dibujo y usa `KEEP_SCREEN_ON`, `setShowWhenLocked` y
`setTurnScreenOn` únicamente en la ventana de la actividad instrumentada. No
desbloquea el keyguard ni cambia ajustes permanentes. La aplicación normal no
incorpora este comportamiento. Las comprobaciones no se omiten ni se relajan.

## Límites

No se modificaron Legacy, Dexcom ni servicios; no se recogieron datos clínicos.
No se cambió conectividad ni se activó modo avión. El aislamiento offline se
verifica por ausencia de INTERNET/servicios remotos y recorrido local. Recibir
una lectura Dexcom real en modo avión sigue siendo una validación pendiente y
no se declara cubierta por esta UI.

No hay cálculo, historial clínico durable, perfil confirmado, IOB local, comidas
reales ni autoridad de tratamientos. Las pantallas secundarias son estructura
navegable con estados específicos pendientes. iOS no se ejecutó y no se promueve
a plataforma soportada.

La PR enlaza la ejecución de CI y el SHA integrado. Después del merge se exige
CI del SHA exacto de `origin/main`, prueba de ancestralidad y árbol limpio; una
verificación de otro SHA no sustituye esa comprobación.
