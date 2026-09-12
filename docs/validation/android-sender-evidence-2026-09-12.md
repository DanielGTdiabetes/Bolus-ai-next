# Evidencia de identidad Android — 2026-09-12

## Alcance

- Base Next: `f807a5a5f39826bf00d69a3b767488f2513f02f2` de `origin/main`.
- Incremento: [ADR 0005](../adr/0005-android-sender-evidence-adapter.md), preparación
  técnica de Fase 5; sin reglas clínicas ni recepción Dexcom real.
- Dispositivo autorizado: Pixel 10 Pro Fold, Android API 37, conectado por USB.
- Fuente de glucosa prevista: app Dexcom modificada del propietario, vía local.
- Fuente de esta prueba: APK sintética `org.bolusai.next.senderfixture`.
- No se auditó ni modificó Legacy; no se accedió a datos ni servicios Dexcom.

## Resultados locales

`scripts/verify.ps1 -DeviceTests` completa compilación KMP/JVM y Android,
lint (incluyendo fuentes de test), compilación de instrumentación/fixture,
comprobaciones de manifests y ejecución en dispositivo.

| Suite | Casos | Fallos / omitidos |
|---|---:|---:|
| Contratos compartidos JVM | 11 | 0 / 0 |
| Android JVM: puerto, presentador, autenticador y coordinación | 25 | 0 / 0 |
| Instrumentación en Pixel API 37 | 8 | 0 / 0 |

Los ocho casos instrumentados comprueban:

1. Identidad compartida entre UID distintos; paquete, UID, versión y firmantes
   actuales coinciden con los metadatos de la APK sintética.
2. Sin identity sharing, rechazo `IDENTITY_NOT_SHARED` aunque el emisor esté instalado.
3. Un extra de paquete falsificado no reemplaza la identidad atribuida por Android.
4. Ancla de certificado incorrecta produce `CERTIFICATE_MISMATCH`.
5. Versión no aprobada produce `VERSION_NOT_APPROVED`.
6. Política pendiente bloquea incluso un broadcast real del emisor sintético.
7. Resolver un paquete inexistente da un resultado explícito de no encontrado.
8. La composición sigue `policy_not_approved`, sin receiver productivo, permiso
   Dexcom ni permiso de Internet.

Las pruebas unitarias adicionales demuestran que no se consulta evidencia con
política pendiente, ni paquetes con identidad ausente/inválida/inesperada.
Conservan las causas de acceso denegado y firma ausente, comprueban UID, versión
y conjunto completo de certificados y propagan defectos inesperados.

Se instaló/actualizó Next con `adb install -r`. El arnés eliminó sus dos APKs
temporales al finalizar. `adb shell am start -W -n org.bolusai.next/.MainActivity`
confirmó arranque en frío con `Status: ok`. No se capturaron pantalla, logcat,
identificador del teléfono, glucosa, transmisor ni otras aplicaciones.

Durante la implementación se resolvieron un intento de Gradle directo sin
`ANDROID_HOME` (el arnés ya detecta el SDK) y errores de lint del fixture
(permiso de firma del receiver, configuración de backup e icono) y del nivel
API de instrumentación. Se usó `SdkSuppress(minSdkVersion = 34)` para declarar
el requisito de la suite; el arnés rechaza dispositivos inferiores antes de
instalar y falla ante pruebas omitidas. No se desactivaron checks para integrar.

## Límites y siguiente puerta

La prueba sintética demuestra el comportamiento Android de la frontera, no la
compatibilidad del productor Dexcom instalado. Falta fijar su release
reproducible, aprobar anclas y demostrar su identidad compartida, después
resolver contrato clínico, persistencia y recepción real en modo avión.

No se cambió el modo avión, Bluetooth ni la conectividad del teléfono para
preservar la operación del productor en uso. La ausencia de permiso de Internet
y de APIs de red en esta frontera demuestra su independencia de servidores;
no sustituye la futura prueba Dexcom offline. API inferiores a 34 e iOS no
fueron ejecutados en este lote. El cálculo y registro de tratamientos permanecen
bloqueados.

La evidencia de CI y el SHA final integrado deben consultarse en la PR que
incluye este informe; una ejecución local no acredita por sí sola G8.
