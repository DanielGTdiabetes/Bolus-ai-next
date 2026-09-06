# ADR 0002: base instalable del cliente Android

- Estado: aceptado técnicamente para el esqueleto; integración Dexcom y reglas clínicas no autorizadas
- Fecha: 2026-09-06
- Fase: infraestructura preparatoria para las fases 2 y 5
- Prioridad de producto: Android primero; iOS conserva únicamente la compatibilidad del núcleo compartido

## Contexto

El núcleo KMP ya publica causas explícitas de entradas no disponibles, pero no
existe un cliente Android instalable. El siguiente incremento debe acercar el
primer hito visible sin fingir una lectura Dexcom, una validación de vigencia o
una capacidad de cálculo que todavía no existen.

Las versiones mínimas definitivas y el contrato autorizado de recepción local
Dexcom siguen pendientes. Elegirlos requiere conocer el dispositivo objetivo y
terminar la auditoría técnica de la integración; no pertenecen a este esqueleto.

## Decisión

Se crea `android/app` como aplicación Android nativa:

- usa Android Gradle Plugin 9.4.0 y el Kotlin integrado de AGP;
- compila y apunta a API 36 estable, con `minSdk 26` provisional para este artefacto de
  desarrollo;
- usa Views/XML del SDK, sin Compose ni librerías de red;
- depende de `shared/bolus-engine` para identificar la causa de bloqueo;
- arranca con `input.glucose.missing`, porque aún no existe un productor Dexcom;
- muestra de forma persistente que la compilación no tiene autoridad de
  tratamiento y que el cálculo está bloqueado;
- no solicita permisos ni declara `INTERNET`, no persiste datos y no registra
  tratamientos.

`applicationId = org.bolusai.next`, `minSdk 26` y la versión de desarrollo no
constituyen una política de distribución definitiva. Deben revisarse antes de
la prueba en el dispositivo objetivo. Cambiar la compatibilidad mínima exige
actualizar este ADR y aportar evidencia del dispositivo/Dexcom autorizado.

## Consecuencias

- Se puede generar un APK de depuración instalable sin introducir lógica clínica.
- Los estados de ausencia, permiso denegado y fuente no disponible tienen
  presentaciones distintas, aunque este lote solo produce ausencia.
- Un adaptador Dexcom futuro deberá determinar la causa real en el límite; la UI
  no la infiere.
- El primer hito offline todavía no está cumplido: falta recibir, validar,
  persistir y mostrar una lectura local real en modo avión.
- iOS no recibe UI ni funcionalidad nueva; conserva las pruebas del núcleo KMP.

## Verificación

`scripts/verify.ps1` compila el APK, ejecuta lint y las pruebas unitarias Android,
además de las comprobaciones existentes del núcleo. Las pruebas fijan que toda
causa no disponible bloquea el cálculo y que permiso/fuente no se colapsan en un
mensaje genérico idéntico.

La configuración sigue la recomendación de Kotlin integrado introducida en
[Android Gradle Plugin 9.0](https://developer.android.com/build/migrate-to-built-in-kotlin)
y fija la versión del plugin para evitar actualizaciones dinámicas. Los avisos
de lint sobre API 37 continúan ejecutándose como informativos porque ese SDK aún
está en preview; todos los demás avisos de lint fallan la verificación. La
compatibilidad del dispositivo Dexcom continúa siendo una decisión separada.
