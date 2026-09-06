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
- Mientras esa puerta iOS no exista, iOS es una plataforma futura y no soportada,
  no un binding cuya compatibilidad esté garantizada en cada commit. La regla de
  `AGENTS.md` que exige ejecutar contratos en cada plataforma soportada continúa
  aplicándose sin excepción a las plataformas que sí estén soportadas.
- No añadir un workflow macOS manual en GitHub: una ejecución futura que consuma
  esos minutos requiere una nueva autorización explícita del propietario.
- Todo cambio en `commonMain` o en un contrato exportado a Swift debe registrar
  iOS como no verificado; no puede afirmar soporte o compatibilidad iOS actual.
- Antes de promover iOS a plataforma soportada, declarar una versión iOS o
  afirmar compatibilidad actualizada, obtener autorización explícita, ejecutar
  las tareas iOS y el consumidor Swift en un Mac autorizado y registrar commit,
  comandos y resultados.

## Consecuencias

- Los cambios ordinarios dejan de consumir minutos macOS de GitHub.
- La ruta iOS permanece como diseño y toolchain para el futuro, no como
  plataforma soportada ni como garantía verificada en cada commit.
- Una regresión Kotlin/Native u Objective-C/Swift puede detectarse más tarde. Se
  registra este compromiso de consistencia y no se presentará una prueba JVM
  como sustituto de una prueba iOS.
- Android continúa con CI automática completa conforme a la prioridad actual.
- Rehabilitar macOS automático exige una decisión posterior que documente
  presupuesto, frecuencia y puerta requerida.

## Verificación

La revisión de todos los archivos `.yml` y `.yaml` bajo `.github/workflows` debe
demostrar que no contienen runners macOS ni tareas iOS/Swift. `scripts/verify.ps1`
escanea todos esos workflows y bloquea cualquier referencia a `macos` —incluidas
comillas y matrices—, `iosSimulatorArm64Test`, enlace de framework iOS o
`verify-swift.sh`. También exige que `verify.yml` exista, no esté vacío y ejecute
la plantilla canónica exacta aprobada: triggers de PR y push a `main`, permisos
de lectura, job activo en `windows-latest` y paso PowerShell que invoca
`scripts/verify.ps1`. Así, comentarios, condiciones deshabilitadas, otro runner o
cambios de estructura no pueden simular la puerta restante. Los artefactos
KMP/iOS y el script se conservan fuera de los workflows para una verificación
futura autorizada.
