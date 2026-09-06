# ADR 0001: Kotlin Multiplatform para el núcleo compartido

- Estado: aceptado técnicamente para el esqueleto; verificación macOS automática sustituida por ADR 0004; reglas clínicas no autorizadas
- Fecha: 2026-08-30
- Fase: 0 — baseline, gobierno y aislamiento de Legacy
- Responsables de aprobación clínica: propietario del producto y revisión clínica

## Contexto

Bolus AI Next necesita una sola implementación determinista del motor que pueda
consumirse desde Android y desde un futuro cliente iOS. El núcleo no puede
depender de UI, red, reloj global, base de datos ni SDKs de plataforma. Tampoco
puede incorporar fórmulas o parámetros antes de completar su trazabilidad y
aprobación.

El primer cliente será Android y el equipo trabaja inicialmente en Windows. La
compilación y prueba de artefactos iOS requiere un host macOS, por lo que esa
capacidad no puede verificarse en el host local Windows. El ADR 0004 documenta
por qué ya no se ejecuta automáticamente en GitHub.

## Decisión

Se adopta Kotlin Multiplatform para `shared/bolus-engine`:

- `commonMain` contendrá contratos y reglas clínicas puras cuando sean aprobadas;
- el target JVM producirá bytecode consumible por Android;
- los targets Kotlin/Native producirán un framework estático `BolusEngine` para
  iOS x64, arm64 y simulador arm64;
- `commonTest` será la fuente canónica de pruebas multiplataforma;
- Gradle Wrapper fijará la versión de build y `scripts/verify.ps1` será la entrada
  única de verificación local en Windows;
- La decisión original configuró CI macOS para iOS; el ADR 0004 retiró su
  ejecución automática por coste y exige autorización explícita para repetirla.

El esqueleto solo expone identidad técnica y declara explícitamente que no
implementa reglas clínicas. Ninguna dosis, parámetro, unidad, vigencia o fallback
se introduce mediante este ADR.

## Alternativas consideradas

### Dos motores nativos (Kotlin y Swift)

Rechazada porque duplica fórmulas y permite divergencias clínicas entre Android e
iOS, en conflicto con la arquitectura obligatoria.

### Rust con bindings JNI y Swift

Es viable para un núcleo puro, pero añade FFI, gestión de memoria, toolchains y
empaquetado antes de obtener valor en Android. Se reconsideraría solo mediante un
nuevo ADR con evidencia de una necesidad que Kotlin Multiplatform no cubra.

### TypeScript/JavaScript embebido

Rechazada para el camino clínico local porque exigiría runtimes o adaptadores
adicionales en ambos móviles y dificultaría una integración nativa y tipada.

## Consecuencias

- Android puede consumir el artefacto JVM sin reimplementar el dominio.
- Un futuro cliente iOS podrá consumir el framework generado desde las mismas
  fuentes comunes; el ADR 0004 lo clasifica actualmente como no soportado.
- La verificación iOS no puede completarse en el host Windows. Desde el ADR 0004
  no se ejecuta automáticamente y debe registrarse por separado antes de afirmar
  compatibilidad iOS actualizada.
- El código específico de plataforma queda fuera de `commonMain`.
- Serialización, huellas canónicas y librerías adicionales requerirán decisiones
  explícitas antes de incorporarse.

## Evidencia de la decisión

- Kotlin Multiplatform Gradle plugin `2.4.10`.
- Gradle Wrapper `9.7.1`, compatible con el JDK 21 disponible.
- Test común ejecutado en JVM y ruta de enlace del framework iOS demostrada en
  macOS hasta la PR #10; el job automático quedó desactivado por el ADR 0004.

## Condiciones para revisar este ADR

Reabrir la decisión si una prueba mínima no puede consumir el mismo contrato
serializado desde Android e iOS, si el framework obliga a duplicar lógica o si el
toolchain deja de soportar las plataformas mínimas que se aprueben.
