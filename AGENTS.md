# AGENTS.md — Bolus AI Next

Este archivo rige todo el repositorio. Las instrucciones más específicas de un
subdirectorio pueden ampliar estas reglas, pero nunca rebajar sus requisitos de
seguridad clínica, trazabilidad o pruebas.

## 1. Misión y orden de prioridades

Bolus AI Next es una aplicación local-first para apoyar cálculos de bolo. No es
una excusa para cambiar reglas clínicas ni para completar datos ausentes con
suposiciones.

En toda decisión aplica este orden, sin excepciones silenciosas:

1. **SEGURIDAD**
2. **CONSISTENCIA**
3. **DISPONIBILIDAD**
4. **COMODIDAD**

Si dos objetivos entran en conflicto, conserva el de mayor prioridad y documenta
el compromiso. Una respuesta bloqueada y explícita es preferible a una
recomendación basada en datos incompletos, ambiguos, caducados o no validados.

## 2. Límite absoluto con Bolus AI Legacy

- `DanielGTdiabetes/bolus_ai` y sus despliegues son producción durante la
  convivencia.
- El GitHub `https://github.com/DanielGTdiabetes/bolus_ai` es la fuente técnica
  primaria para conocer Legacy. Antes de extraer una conclusión, resolver y
  registrar el SHA exacto de `main`; no basarse solo en una copia local que pueda
  estar desactualizada.
- Este repositorio puede **leer y auditar** Legacy como referencia cuando el
  propietario facilite acceso, pero no debe modificar su código, datos,
  configuración, secretos, infraestructura, despliegues ni comportamiento.
- Consultar primero el mapa reproducible de `docs/LEGACY_SOURCE_MAP.md`. Ampliarlo
  cuando aparezca una fuente relevante, indicando ruta, símbolo/contrato, SHA y
  qué afirmación sustenta.
- Código, tests, commits, issues y documentación de Legacy son **evidencia de lo
  que existe**, no aprobación clínica automática. Si discrepan, conservar la
  discrepancia y resolverla; no elegir silenciosamente la variante más cómoda.
- Legacy contiene implementaciones y fallbacks históricos que pueden divergir.
  No copiar módulos completos ni portar literales clínicos por semejanza. Extraer
  contratos y vectores saneados, y reimplementar solo reglas trazadas y aprobadas
  en el motor único de Next.
- No ejecutar contra Legacy migraciones, escrituras, pruebas destructivas,
  tratamientos simulados ni llamadas con efectos laterales.
- Cualquier modificación de Legacy exige una petición explícita del propietario,
  alcance separado, copia de seguridad y plan de reversión.
- No copiar secretos, tokens, datos personales o historiales clínicos reales a
  este repositorio, fixtures, logs, incidencias o commits.
- Durante el modo sombra, Legacy conserva la autoridad de tratamiento y Next no
  registra, sincroniza ni dispara tratamientos reales.

## 3. Regla clínica fundamental: no inventar

Nunca introducir un valor clínico por defecto, un fallback clínico ni una
suposición implícita. Esto incluye, sin limitarse a:

- ratio de hidratos;
- ISF/CF;
- objetivo o rango objetivo;
- DIA, curva de acción o parámetros de insulina;
- IOB, incluida la suposición `IOB = 0`;
- unidades, zona horaria o interpretación de timestamps;
- vigencia máxima de glucosa o perfil;
- límites, dosis máximas, incrementos o reglas de redondeo;
- reglas de fibra, grasa/proteína, Warsaw u otras reglas nutricionales;
- tratamiento de tendencias, predicciones o glucosa futura.

Cada regla o parámetro clínico debe tener procedencia documentada y estado de
validación. Puede proceder de un perfil introducido y confirmado por el usuario,
de una regla observada y aprobada de Legacy o de una decisión clínica explícita;
nunca de la intuición del agente.

Si falta una definición necesaria:

1. modelar el dato como ausente/desconocido, no como cero;
2. bloquear la recomendación afectada (fail closed);
3. devolver un motivo estable y comprensible;
4. crear una decisión pendiente o pedir confirmación al propietario;
5. añadir una prueba que preserve ese bloqueo.

Las constantes técnicas no clínicas (por ejemplo, tamaño de página de una lista)
deben distinguirse claramente de parámetros clínicos.

## 4. Arquitectura obligatoria

### 4.1 Local-first

- El camino crítico `entradas válidas -> cálculo -> revisión -> confirmación ->
  registro local` debe funcionar sin Internet, NAS, Render, Telegram,
  MyFitnessPal, Nightscout ni otros servicios remotos.
- Una operación confirmada se persiste durablemente en local antes de intentar
  sincronizarla.
- Una caída de red puede reducir capacidades externas, pero no debe corromper el
  estado local ni hacer pasar datos antiguos por actuales.
- Toda degradación debe ser visible y específica. No ocultar errores de origen,
  permisos, autenticación, reloj, parseo o persistencia bajo “sin conexión”.
- La base local es la fuente de verdad operativa del dispositivo. La
  sincronización replica hechos; no reinterpreta decisiones ya confirmadas.

### 4.2 Un único motor clínico reutilizable

- Debe existir una sola implementación canónica del cálculo en
  `shared/bolus-engine` (o en la ubicación aprobada por un ADR equivalente).
- Android, el futuro cliente iOS, UI, importadores y servicios auxiliares llaman
  al mismo motor; no duplican fórmulas clínicas.
- El motor es determinista, puro en lo posible, independiente de UI, red, reloj
  global, base de datos y SDKs de plataforma.
- Todas las entradas relevantes son explícitas: valores, unidades, instantes,
  zona/offset cuando corresponda, procedencia, versión del perfil y versión de
  reglas.
- La salida incluye componentes del cálculo, reglas aplicadas, avisos/bloqueos,
  versión del motor y huella de las entradas. No solo devuelve una cifra.
- El motor no administra sincronización ni ejecuta tratamientos.
- Toda elección de tecnología del núcleo compartido debe demostrar cómo se
  reutilizará en Android e iOS. Si esto no está resuelto, registrar un ADR antes
  de crear lógica clínica.

### 4.3 Capas y dependencias

Dirección permitida:

```text
UI / adaptadores de plataforma
             |
       casos de uso
        /         \
motor clínico   puertos de persistencia/integración
                     |
         Room/SQLite, Dexcom, MFP, sync, etc.
```

- Las capas internas no importan frameworks de UI o clientes de red.
- Los datos externos se validan y normalizan en el límite; el motor nunca recibe
  DTOs crudos de proveedores.
- Acceso a reloj, identificadores y red mediante dependencias inyectables para
  permitir pruebas deterministas.
- Cambios estructurales relevantes requieren un ADR breve en `docs/adr/`.

## 5. Validación estricta de entradas

No se puede solicitar una recomendación hasta validar de forma explícita:

### Glucosa

- valor y unidad reconocida sin conversión ambigua;
- timestamp del dato y timestamp de recepción;
- procedencia e identificador cuando exista;
- antigüedad calculada con un reloj inyectado;
- vigencia conforme a una política **aprobada**, nunca inventada;
- duplicados, orden temporal y timestamps futuros/anómalos;
- estado inequívoco: válida, ausente, caducada, inválida o fuente no disponible.

Una lectura caducada o inválida puede mostrarse con su estado, pero no tratarse
como glucosa actual para calcular.

### IOB

- nunca transformar desconocido, error de cálculo o historial incompleto en
  `0 U`;
- verificar integridad y ventana del historial necesario;
- usar la DIA/curva y la versión de perfil validadas para el instante calculado;
- conservar procedencia, instante de cálculo y versión del algoritmo;
- detectar eventos duplicados y conflictos de identidad;
- bloquear si no se puede demostrar que el historial requerido es suficiente.

### Perfil clínico

- esquema y versión explícitos;
- unidades explícitas y coherentes;
- intervalos temporales completos, no solapados y sin ambigüedad de zona horaria;
- todos los campos requeridos presentes y dentro de restricciones **aprobadas**;
- vigencia temporal y huella inmutable de la versión usada;
- cambios de perfil como nuevas versiones, sin reescribir el perfil usado por un
  cálculo histórico.

No inventar rangos “razonables” para validar. Si los límites aún no han sido
aprobados, validar estructura/coherencia, marcar la decisión pendiente y bloquear
la funcionalidad que dependa de ellos.

## 6. Persistencia, eventos y sincronización

- Persistir entradas normalizadas, recomendación/desglose, confirmación del
  usuario y versión/hash de perfil y motor suficientes para auditoría.
- Diferenciar recomendación, confirmación, registro y sincronización; no son el
  mismo evento.
- Las migraciones de base de datos son versionadas, probadas hacia delante y,
  cuando sea viable, con prueba de recuperación mediante copia de seguridad.
- No borrar ni sobrescribir silenciosamente historial clínico.
- Toda operación sincronizable lleva un identificador estable/idempotency key.
- Reintentos con la misma clave producen el mismo efecto lógico; no duplican
  bolos, comidas ni eventos.
- Resolver conflictos de forma explícita y auditable. Nunca usar “última escritura
  gana” para datos clínicos sin una decisión documentada y pruebas.
- La sincronización no recalcula ni modifica una recomendación o confirmación
  histórica. Si hace falta corregir, crear un nuevo evento enlazado.
- La cola local sobrevive reinicios, backoff y pérdida de red, y distingue fallos
  reintentables de fallos que requieren intervención.

## 7. Modo sombra y autoridad de escritura

- Por defecto, Next empieza sin autoridad para tratamiento real.
- En modo sombra usa entradas equivalentes y compara, como mínimo: identidad y
  versión de inputs, perfil, IOB, componentes, redondeo, bloqueos y resultado.
- Las comparaciones no pueden alterar la decisión de Legacy ni crear efectos
  laterales clínicos.
- La UI y los datos deben marcar inequívocamente los resultados de sombra como no
  accionables/no autoritativos.
- Registrar discrepancias con datos minimizados o seudonimizados y clasificación
  de causa; no registrar secretos ni datos personales innecesarios.
- Una discrepancia no se “arregla” copiando el resultado de Legacy: se explica,
  reproduce y cubre con una prueba.
- El cambio de autoridad exige criterios aprobados, mecanismo de exclusión mutua,
  despliegue gradual, observabilidad y rollback ensayado. Nunca pueden coexistir
  dos escritores de tratamiento real para el mismo flujo.

## 8. Estrategia Android e iOS

- Android es el primer cliente y debe validar recepción local de Dexcom y uso
  offline antes del cálculo completo.
- iOS es un consumidor futuro del mismo modelo de dominio, contratos, fixtures y
  motor clínico; no una reimplementación de fórmulas.
- Aislar integraciones específicas de plataforma detrás de interfaces/puertos.
- No bloquear el diseño del dominio con APIs exclusivas de Android.
- Cada capacidad dependiente del sistema operativo debe declarar su alternativa
  iOS, limitación conocida o decisión pendiente en un ADR.
- Golden vectors y contratos serializados son multiplataforma y se ejecutan para
  cada binding/plataforma soportada.

## 9. Pruebas obligatorias

Todo cambio de comportamiento incluye pruebas proporcionales al riesgo. Para
lógica clínica, persistencia o sincronización, las pruebas son condición de
entrega, no trabajo posterior.

Cobertura mínima por tipo de cambio:

- motor: unitarias deterministas, golden vectors de Legacy aprobados, límites,
  unidades, redondeo y estados fail-closed;
- validación: ausente, inválido, caducado, futuro, duplicado, unidad desconocida,
  perfil incompleto y IOB desconocido/incompleto;
- persistencia: CRUD relevante, reinicio, transacciones y migraciones desde cada
  versión soportada;
- sincronización: reintento, duplicado, reordenación, respuesta perdida,
  conflicto, reinicio y ejecución offline;
- Dexcom/integraciones: contratos con fixtures saneados, permisos/errores y prueba
  en modo avión; ninguna prueba automatizada escribe en producción;
- sombra: paridad por componente, clasificación de discrepancias y ausencia de
  efectos laterales;
- UI crítica: estado de antigüedad/origen, bloqueos visibles y confirmación
  explícita;
- regresión: cada defecto clínico corregido añade un caso que fallaba antes.

Reglas de fixtures:

- usar datos sintéticos o anonimizados aprobados;
- declarar unidades y timestamps completos;
- no fabricar golden vectors “esperados” a mano si deben representar Legacy:
  capturarlos mediante el proceso de auditoría, revisarlos y registrar su origen;
- no actualizar snapshots/golden files solo para hacer pasar una prueba sin
  explicar y aprobar el cambio clínico.

Cuando exista el arnés del proyecto, `scripts/verify.ps1` será la entrada única de
verificación local en Windows y ejecutará formato/lint, compilación, pruebas y
comprobaciones de arquitectura pertinentes. Hasta crearlo, cada PR debe enumerar
los comandos reales ejecutados; no escribir “tests pasan” sin evidencia.

## 10. Flujo de trabajo para agentes

Antes de editar:

1. leer este archivo, `README.md`, `docs/ARCHITECTURE.md` y
   `docs/EXECUTION_PLAN.md`; si el cambio usa Legacy, leer también
   `docs/LEGACY_SOURCE_MAP.md`;
2. comprobar `git status --short --branch` y preservar cambios ajenos;
3. actualizar referencias con `git fetch --prune origin`;
4. identificar la fase, criterios de aceptación y riesgos clínicos;
5. buscar ADRs y contratos relacionados;
6. declarar las incógnitas que impidan trabajar sin suposiciones.

Antes de usar información de Legacy, comprobar además el GitHub remoto:

```powershell
$legacyUrl = "https://github.com/DanielGTdiabetes/bolus_ai.git"
$legacyMain = (git ls-remote $legacyUrl refs/heads/main).Split()[0]
if (-not $legacyMain) { throw "No se pudo resolver bolus_ai/main" }
Write-Output "Legacy audit SHA: $legacyMain"
```

Toda nota, vector o informe derivado debe incluir ese SHA. Si cambia durante una
auditoría, terminar el lote con el SHA fijado y abrir una comparación separada;
no mezclar resultados de versiones distintas.

Durante el trabajo:

- mantener el cambio pequeño, revisable y vinculado a una fase/criterio;
- preferir tipos/estados explícitos a sentinelas como `0`, cadena vacía o `null`
  ambiguo;
- separar cambios clínicos de refactors, dependencias y formato;
- no añadir telemetría con información clínica identificable;
- no incluir secretos ni endpoints privados;
- documentar decisiones y actualizar pruebas en el mismo cambio;
- detener la implementación clínica cuando falte una regla aprobada, pero seguir
  con trabajo seguro no dependiente (tipos, interfaces, pruebas de bloqueo,
  documentación).

Antes de entregar:

1. revisar el diff completo y los archivos sin seguimiento;
2. ejecutar `scripts/verify.ps1` cuando exista y las pruebas específicas del
   cambio;
3. comprobar que el modo offline y fail-closed no se han debilitado;
4. comprobar que Legacy no fue modificado ni recibió escrituras;
5. dejar trazabilidad de comandos, resultados, riesgos y decisiones pendientes;
6. verificar que el commit publicado está contenido en `origin/main`.

## 11. Git, revisiones y publicación

- `main` representa el estado estable e integrado; no dejar trabajo final solo en
  el directorio local o en una rama sin publicar.
- Empezar desde `origin/main` actualizado y usar una rama corta por cambio salvo
  que el propietario indique trabajar directamente en `main`.
- No usar `push --force`, reescribir historia compartida, borrar ramas remotas o
  saltarse protecciones.
- Commits pequeños y descriptivos; no mezclar cambios no relacionados.
- No confirmar artefactos generados, secretos ni datos clínicos reales.
- Los cambios clínicos exigen revisión explícita del propietario y evidencia de
  paridad/seguridad. Que el código compile no constituye aprobación clínica.
- Resolver fallos; no omitir pruebas, desactivar checks ni rebajar validaciones
  para integrar.
- Antes de publicar, actualizarse respecto de `origin/main` y revalidar.
- Tras integrar, ejecutar `git fetch origin` y demostrar que el commit entregado
  es ancestro de `origin/main`.

Un cambio está **terminado** únicamente cuando:

1. cumple sus criterios de aceptación;
2. documentación y pruebas obligatorias están incluidas;
3. la verificación local y CI aplicable están en verde;
4. no quedan riesgos clínicos ocultos ni supuestos presentados como hechos;
5. el commit revisado está publicado e integrado en `origin/main`;
6. `git status --short` está limpio en la copia de trabajo de entrega.

## 12. Comandos base (PowerShell)

Inicio de una tarea:

```powershell
Set-Location E:\projects\Bolus-ai-next
git fetch --prune origin
git status --short --branch
git log -5 --oneline --decorate
```

Verificación previa a entrega (añadir las pruebas específicas):

```powershell
Set-Location E:\projects\Bolus-ai-next
if (Test-Path .\scripts\verify.ps1) { & .\scripts\verify.ps1 }
git diff --check
git status --short
git diff --stat origin/main...HEAD
```

Comprobación final tras integración:

```powershell
Set-Location E:\projects\Bolus-ai-next
git fetch origin
$commit = git rev-parse HEAD
git merge-base --is-ancestor $commit origin/main
if ($LASTEXITCODE -ne 0) { throw "El commit entregado no está en origin/main" }
git status --short
```

No ejecutar comandos de publicación si las pruebas fallan, el árbol contiene
cambios no entendidos o falta una aprobación clínica necesaria.
