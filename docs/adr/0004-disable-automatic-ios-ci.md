# ADR 0004: desactivar la verificación iOS automática de GitHub

- Estado: aceptado por el propietario
- Fecha: 2026-09-06
- Alcance: coste de CI y prioridad Android
- Sustituye: únicamente la ejecución macOS automática del ADR 0001

## Contexto

El workflow `Verify` ejecutaba en cada PR y cada push a `main` un runner
`macos-latest` para probar el simulador iOS, enlazar el framework KMP y ejecutar
el consumidor Swift. Los minutos macOS disponibles son limitados y pueden tener
coste. El propietario ha indicado detener ese consumo automático y mantener iOS
solo como compatibilidad del núcleo mientras Android es la prioridad.

El último commit del límite Android verificado por ese job antes de retirarlo fue
`ba115213abbcc7d4f5f62182381cc2a63e3dc4e9` en la PR #10. El run posterior del
merge `f7884d3599705f7fb175396024b835fce02bcfff` fue cancelado al recibir la
instrucción; no debe contarse como verificación verde de `main`.

## Decisión

- Eliminar el job `iOS framework compilation` del workflow automático.
- Mantener los targets Kotlin/Native y `scripts/verify-swift.sh` en el
  repositorio; no se elimina la ruta técnica compartida.
- Mantener `scripts/verify.ps1` y las pruebas JVM/Android como la puerta
  automática en Windows para PR y `main`.
- No añadir un workflow macOS manual en GitHub: una ejecución futura que consuma
  esos minutos requiere una nueva autorización explícita del propietario.
- Antes de declarar una versión iOS, cambiar un contrato exportado a Swift o
  afirmar compatibilidad iOS actualizada, ejecutar de forma explícita las tareas
  iOS y el consumidor Swift en un Mac autorizado y registrar commit, comandos y
  resultados.

## Consecuencias

- Los cambios ordinarios dejan de consumir minutos macOS de GitHub.
- La compatibilidad iOS permanece como diseño y toolchain, no como garantía
  verificada en cada commit.
- Una regresión Kotlin/Native u Objective-C/Swift puede detectarse más tarde. Se
  registra este compromiso de consistencia y no se presentará una prueba JVM
  como sustituto de una prueba iOS.
- Android continúa con CI automática completa conforme a la prioridad actual.
- Rehabilitar macOS automático exige una decisión posterior que documente
  presupuesto, frecuencia y puerta requerida.

## Verificación

La revisión del workflow debe demostrar que no contiene `runs-on: macos-*`,
`iosSimulatorArm64Test`, enlace de framework iOS ni ejecución de
`verify-swift.sh`. `scripts/verify.ps1` bloquea esos tokens en el workflow para
evitar una reactivación accidental. Los artefactos KMP/iOS y el script se
conservan para una verificación futura autorizada.
