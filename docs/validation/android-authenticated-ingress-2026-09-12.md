# Acceso al contenido tras autenticar — 2026-09-12

## Alcance y resultado

- Base Next: `9478236` de `origin/main`, PR #18 integrada.
- Incremento técnico de Fase 5: [ADR 0006](../adr/0006-authenticate-before-payload.md).
- Prueba en el teléfono USB autorizado, Android API 37.
- Solo Next y las APK sintéticas del arnés; sin consultas ni escrituras a Legacy
  o Dexcom, sin datos clínicos ni cambios de conectividad del teléfono.

`scripts/verify.ps1 -DeviceTests` terminó con código 0. Compiló KMP/JVM, APK
debug, instrumentación y fixture; lint y comprobaciones de aislamiento de
manifests debug/release terminaron correctamente.

| Suite | Casos | Fallos / errores / omitidos |
|---|---:|---:|
| Contratos compartidos JVM | 11 | 0 / 0 / 0 |
| Android JVM | 30 | 0 / 0 / 0 |
| Instrumentación Android API 37 | 8 | 0 / 0 / 0 |

Los cinco nuevos casos JVM cubren todas las causas de rechazo sin ejecutar la
función posterior, orden de evidencia y acceso, ejecución única, conservación
de un bloqueo clínico posterior, reautenticación tras un mensaje permitido y
propagación de defectos de evidencia o del consumidor sin reintentos.

Se reforzó el intercambio de las pruebas instrumentadas existentes para usar
la nueva frontera. Un emisor de UID distinto con identidad compartida y anclas
sintéticas coincidentes permite leer exactamente una vez un marcador sin datos
clínicos. Identidad ausente, paquete distinto, firma/versión no aprobadas o
política pendiente producen cero accesos al marcador. Las pruebas restantes
conservan resolución explícita de paquete ausente y composición bloqueada.

El arnés actualizó Next y eliminó las APK de instrumentación y emisor sintético
al terminar. `adb shell am start -W -n org.bolusai.next/.MainActivity` confirmó
`Status: ok` y `LaunchState: COLD`. `adb shell getprop ro.build.version.sdk`
confirmó API 37. Los conteos JVM se obtuvieron de los XML de resultados Gradle;
no se recopilaron logcat, pantalla, identificadores del teléfono ni glucosa.

## Límites y siguiente paso

No existe parser ni persistencia; estos tests prueban que el rechazo no ejecuta
el consumidor, no transacciones futuras. La función debe contener todos los
accesos al payload: la API no evita lecturas hechas por código externo antes de
invocarla. La autenticación no valida glucosa ni habilita cálculo o tratamiento.

El siguiente paso para recepción real exige fijar una release reproducible del
productor, sus anclas aprobadas y demostrar su identidad compartida. Después
deben resolverse contrato de contenido, políticas clínicas y persistencia antes
de probar Dexcom en modo avión. Estas puertas siguen abiertas; este incremento
no modifica el productor. iOS no se ejecutó y sigue sin declararse soportado.

La PR de este incremento enlaza la ejecución de CI y el commit integrado. La
verificación local por sí sola no acredita integración ni CI del SHA final.
