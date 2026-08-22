# Plan ejecutable de Bolus AI Next

## 1. Propósito

Este plan convierte la arquitectura y el roadmap en una secuencia de entregas
verificables para Codex. Su objetivo es construir Bolus AI Next sin interrumpir ni
modificar Bolus AI Legacy y sin convertir incertidumbres clínicas en valores por
defecto.

Orden de decisión obligatorio:

> **SEGURIDAD > CONSISTENCIA > DISPONIBILIDAD > COMODIDAD**

El plan no autoriza tratamientos ni sustituye validación clínica. Cualquier
parámetro, límite, fórmula o umbral no demostrado queda marcado como decisión
pendiente y bloquea la recomendación que lo necesite.

## 2. Resultado objetivo

Al final de la migración:

- Android puede recibir y persistir glucosa local de Dexcom G7, presentar su
  procedencia/antigüedad y realizar el flujo clínico aprobado sin Internet;
- existe un solo motor clínico determinista y reutilizable por Android e iOS;
- IOB, glucosa y perfil se validan estrictamente y los estados desconocidos
  bloquean de forma explícita;
- recomendaciones y confirmaciones se guardan localmente antes de sincronizar;
- la sincronización es opcional, durable e idempotente;
- Legacy y Next conviven primero en modo sombra con una sola autoridad de
  escritura;
- la promoción de Next y el retiro gradual de servicios auxiliares se basan en
  evidencia y tienen rollback;
- cada entrega está probada y solo se considera terminada al estar integrada en
  `origin/main`.

## 3. Reglas de ejecución transversales

### 3.1 Estados explícitos

Modelar las entradas clínicas con estados distinguibles, por ejemplo:

```text
No disponible | Inválida | Caducada | Válida(procedencia, instante, versión)
```

El nombre exacto se decidirá al diseñar el dominio. No usar `0`, `null`, valor
vacío o el último dato conocido como sustitución silenciosa. La UI debe explicar
qué falta y el motor debe devolver un bloqueo estable y comprobable.

### 3.2 Evidencia antes que paridad aparente

- La auditoría de Legacy es de solo lectura.
- Toda regla migrada enlaza a evidencia: ubicación/versión de Legacy, vector
  aprobado, decisión del propietario o ADR.
- Una diferencia no se oculta ajustando un esperado; se clasifica y resuelve.
- Los datos reales se minimizan/anonimizan antes de crear fixtures.

### 3.3 Entrega incremental

Cada fase produce un cambio pequeño en una rama corta, revisión, verificación y
merge. No se empieza lógica clínica de una fase si su puerta de entrada no está
cumplida. Sí puede adelantarse infraestructura segura que no dependa de una
decisión clínica pendiente.

### 3.4 Artefactos estándar

Crear cuando la fase correspondiente los necesite:

```text
docs/
  adr/                         decisiones arquitectónicas
  audit/legacy-clinical.md     inventario clínico de solo lectura
  contracts/                   esquemas y contratos versionados
  safety/                      peligros, mitigaciones y gates
  validation/                  informes de paridad/sombra/beta
shared/bolus-engine/           única implementación clínica
android/                       cliente Android y adaptadores
ios/                           futuro cliente/binding iOS, sin duplicar fórmulas
tests/vectors/                 vectores aprobados y saneados
scripts/verify.ps1             verificación local única
```

No crear carpetas vacías por estética. Cada una nace con un artefacto usado y
probado.

## 4. Puertas globales de seguridad

Estas puertas se aplican además de los criterios de cada fase:

| Puerta | Debe demostrar | Si falla |
|---|---|---|
| G0 — Alcance | Solo se modifica Bolus AI Next | detener y revertir el cambio fuera de alcance |
| G1 — Procedencia | Toda regla/parámetro clínico tiene fuente y versión | bloquear implementación/recomendación |
| G2 — Entradas | Glucosa, IOB y perfil requeridos son válidos y vigentes | fail closed con motivo explícito |
| G3 — Determinismo | Mismas entradas/versiones producen mismo desglose | no integrar el motor |
| G4 — Durabilidad | Confirmación local atómica anterior a sync | no habilitar autoridad Next |
| G5 — Idempotencia | Reintentos/duplicados no duplican efectos | no conectar escritura remota |
| G6 — Autoridad | Existe exactamente un escritor de tratamiento real | detener rollout |
| G7 — Evidencia | Pruebas, revisión y trazabilidad completas | no integrar en `main` |
| G8 — Publicación | Commit verificado contenido en `origin/main` | entrega incompleta |

## 5. Fases y criterios de aceptación

### Fase 0 — Baseline, gobierno y aislamiento de Legacy

**Objetivo:** establecer una base reproducible antes de crear código de producto.

**Trabajo:**

1. Confirmar que `DanielGTdiabetes/bolus_ai` sigue siendo producción y que Next
   no tiene credenciales de escritura hacia ella.
2. Consultar `DanielGTdiabetes/bolus_ai` en GitHub como fuente técnica primaria,
   registrar el SHA de `main` y mantener una copia de auditoría separada con push
   deshabilitado, sin modificarlo ni ejecutar efectos externos.
3. Crear `AGENTS.md`, este plan, plantilla de ADR, registro inicial de riesgos y
   mapa de responsables/aprobaciones.
4. Elegir mediante ADR la tecnología del núcleo compartido, build y soporte
   Android/iOS. La decisión debe probar reutilización real, no prometer una
   reimplementación futura.
5. Crear CI mínimo y `scripts/verify.ps1` al introducir el primer toolchain.
6. Definir clasificación de datos, política de fixtures/logs y gestión de
   secretos.

**Criterios de aceptación:**

- alcance y prohibición de modificar Legacy constan en la raíz;
- incertidumbres clínicas están registradas, no resueltas con defaults;
- el ADR del stack demuestra ruta Android/iOS y motor único;
- verificación local y CI ejecutan el mismo conjunto esencial de checks;
- el primer commit de gobierno está integrado en `origin/main`.
- `docs/LEGACY_SOURCE_MAP.md` identifica las fuentes reales del SHA auditado y el
  procedimiento para refrescarlas de forma reproducible.

**No habilita:** cálculo, registro de tratamiento ni escrituras a Legacy.

### Fase 1 — Auditoría clínica y técnica de Legacy (solo lectura)

**Objetivo:** explicar exactamente el comportamiento de producción que se pretende
comparar, incluidos errores y ambigüedades; no asumir que todo comportamiento es
clínicamente correcto.

**Trabajo:**

1. Inventariar entradas, salidas y fuentes: glucosa, perfil, insulina/IOB,
   alimentos, hora/zona y confirmaciones.
2. Seguir el inventario de `docs/LEGACY_SOURCE_MAP.md` desde callers/API/UI hasta
   las funciones ejecutadas; contrastar código, tests, commits y documentación
   del GitHub en el mismo SHA.
3. Trazar el cálculo real: CR, ISF/CF, objetivos, DIA/curva, IOB, correcciones,
   fibra/grasa/proteína/Warsaw si existen, límites y redondeo.
4. Identificar parámetros por horario, unidades, precedencias, cachés, fallbacks y
   manejo de errores.
5. Separar cuatro categorías: comportamiento observado, documentación histórica,
   regla clínicamente aprobada y defecto/decisión pendiente.
6. Comparar explícitamente el backend Python con `android-companion` y cualquier
   transformación del frontend; no declarar canónica una implementación solo por
   su ubicación.
7. Crear vectores de referencia saneados con input, output/desglose, versión de
   Legacy, procedencia y aprobación.
8. Catalogar fallos posibles de Dexcom, MyFitnessPal, reloj, red, base de datos y
   sincronización.

**Criterios de aceptación:**

- `docs/audit/legacy-clinical.md` permite reproducir cada componente observado;
- el informe registra el SHA GitHub y rutas/símbolos por cada afirmación;
- las divergencias backend/Android/frontend y los fallbacks existentes están
  catalogados y no se han importado como defaults de Next;
- ninguna incógnita está rellenada con un valor supuesto;
- fixtures no contienen secretos ni datos identificables;
- los vectores tienen procedencia/versiones y revisión;
- existe una matriz requisito -> evidencia -> vector -> estado de aprobación;
- se sabe qué comportamientos deben igualarse, corregirse deliberadamente o
  quedar bloqueados.

**No habilita:** copiar ciegamente bugs o declarar una regla clínicamente válida
solo porque está en producción.

### Fase 2 — Contratos de dominio y modelo de seguridad

**Objetivo:** fijar tipos, estados y fronteras antes de implementar fórmulas.

**Trabajo:**

1. Diseñar contratos versionados para glucosa, perfil, evento de insulina,
   comida/revisión, solicitud y resultado de cálculo.
2. Hacer explícitos unidades, timestamps, zona/offset, procedencia, versiones y
   calidad/estado.
3. Definir códigos estables de bloqueo y advertencia.
4. Elaborar análisis de peligros: dato ausente/antiguo, reloj incorrecto,
   duplicados, historial IOB incompleto, perfil ambiguo, fallo de persistencia,
   doble escritor y replay de sincronización.
5. Definir la huella canónica de inputs/perfil/motor sin incluir secretos.

**Criterios de aceptación:**

- no hay sentinelas clínicos ambiguos;
- contratos inválidos fallan antes de entrar al motor;
- cada peligro de severidad relevante tiene mitigación y prueba prevista;
- serialización es determinista y compatible con fixtures multiplataforma;
- los contratos no dependen de Android, UI, red o base de datos.

### Fase 3 — Núcleo clínico compartido

**Objetivo:** implementar la única versión canónica de reglas aprobadas.

**Trabajo:**

1. Crear `shared/bolus-engine` conforme al ADR.
2. Implementar validación y cálculo como funciones deterministas con dependencias
   explícitas.
3. Migrar una regla cada vez, vinculada a evidencia y vectores.
4. Devolver desglose, bloqueos, versión y huella; nunca solo una dosis.
5. Añadir unit tests, golden tests y tests de propiedades/invariantes que no
   inventen límites clínicos.
6. Publicar bindings/contrato demostrable para Android y una prueba mínima de
   consumo futuro desde iOS según el ADR.

**Criterios de aceptación:**

- no existe otra implementación de fórmulas en Android, iOS o backend;
- vectores aprobados coinciden exactamente o según el redondeo documentado;
- cualquier diferencia aprobada está en ADR/informe y tiene tests;
- inputs ausentes, inválidos o no aprobados producen bloqueo explícito;
- motor ejecuta sin red, base de datos, reloj global ni UI;
- suite pasa en todas las plataformas/bindings declarados.

**Bloqueo obligatorio:** una regla no documentada queda sin implementar o devuelve
“regla no disponible”; no se aproxima.

### Fase 4 — Persistencia local y máquina de estados

**Objetivo:** hacer durable y auditable el flujo local.

**Trabajo:**

1. Implementar base local Android (Room/SQLite si el ADR lo confirma).
2. Modelar glucosa, perfil versionado, insulina, comidas/revisiones,
   recomendaciones, confirmaciones y outbox de sincronización.
3. Aplicar transacciones: confirmación + evento local + item de outbox de forma
   atómica.
4. Definir identidades estables, deduplicación, retención, exportación/backup y
   recuperación.
5. Crear migraciones versionadas y tests de cada ruta soportada.

**Criterios de aceptación:**

- reiniciar la app no pierde una confirmación persistida;
- un fallo entre confirmación y red no pierde ni duplica el evento;
- historial conserva hashes/versiones originales;
- duplicados y conflictos no se resuelven silenciosamente;
- migraciones se prueban con bases fixture sin datos reales;
- funcionamiento local no requiere cuenta ni servicio remoto.

### Fase 5 — Dexcom G7 local y primer hito Android offline

**Objetivo:** abrir Android y mostrar correctamente una lectura local de Dexcom G7
sin Internet.

**Trabajo:**

1. Auditar de solo lectura la integración Legacy y las opciones autorizadas de
   Android.
2. Implementar un adaptador de plataforma separado del dominio.
3. Normalizar y persistir valor, unidad, timestamp del dato/recepción, tendencia,
   origen e identidad.
4. Detectar permiso ausente, fuente no disponible, duplicado, desorden, timestamp
   futuro e invalidez/caducidad según política aprobada.
5. Mostrar valor, unidad, origen, hora, antigüedad y estado; no insinuar vigencia
   cuando no pueda determinarse.
6. Ejecutar pruebas en modo avión y tras reinicio.

**Criterios de aceptación:**

- una prueba en dispositivo abre la app sin Internet y muestra una lectura local
  válida con metadatos correctos;
- lectura caducada/invalidada se distingue y no alimenta cálculo;
- pérdida de permisos/fuente produce estado explícito, no un valor almacenado
  presentado como nuevo;
- duplicados y orden temporal tienen tests;
- no existe dependencia de NAS, Render o Telegram en el camino de lectura.

**Este es el primer hito funcional.** Aún no habilita cálculo de bolo.

### Fase 6 — Perfil versionado e IOB local

**Objetivo:** disponer de los otros inputs críticos con integridad demostrable.

**Trabajo:**

1. Crear importación/edición/confirmación de perfil con unidades y periodos
   explícitos.
2. Validar completitud, solapamientos, huecos, zona horaria, vigencia y límites
   aprobados.
3. Persistir nuevas versiones sin mutar las históricas.
4. Implementar IOB únicamente con DIA/curva/eventos aprobados y el motor común.
5. Detectar historial insuficiente, duplicado o conflictivo y propagar
   `desconocido/bloqueado`.
6. Probar cambios horarios, cambios de perfil y fronteras temporales observadas en
   los vectores aprobados.

**Criterios de aceptación:**

- ningún cálculo usa perfil incompleto o ambiguo;
- cada resultado IOB enlaza eventos, perfil, algoritmo e instante;
- `IOB desconocido` jamás se transforma en `0 U`;
- paridad por componente con los vectores Legacy aprobados;
- reinicio y modo avión conservan perfil/IOB local verificable.

### Fase 7 — Cálculo local completo y revisión humana

**Objetivo:** completar el flujo local sin conceder todavía autoridad real a Next.

**Trabajo:**

1. Entrada manual de comida con unidades explícitas y validación.
2. Caso de uso que reúne snapshots válidos de glucosa, IOB, perfil y comida.
3. Mostrar desglose y procedencia; bloquear si cualquier requisito falla.
4. Separar claramente calcular, revisar, confirmar y registrar.
5. Persistir confirmación local atómica antes de ponerla en outbox.
6. Probar totalmente offline, rotación/reinicio y fallos de almacenamiento.

**Criterios de aceptación:**

- con el mismo snapshot/versiones se reproduce el mismo resultado;
- la UI no permite confirmar un resultado bloqueado o desactualizado;
- si cambian inputs después de calcular, se invalida o recalcula mediante acción
  explícita, nunca en silencio;
- un fallo de persistencia impide presentar el registro como completado;
- el flujo completo pasa en modo avión con fixtures aprobados;
- la compilación sigue marcada como no autoritativa para tratamiento real.

### Fase 8 — MyFitnessPal como importación opcional

**Objetivo:** importar nutrición sin convertir el servicio en dependencia clínica.

**Trabajo:**

1. Auditar método autorizado, contratos, autenticación, límites y disponibilidad.
2. Importar a un borrador local normalizado con origen, identidad, timestamp y
   huella/versiones.
3. Presentar revisión/edición/descarte antes de incluirlo en un cálculo.
4. Diferenciar comida nueva, revisión y reimportación; deduplicar de forma
   explícita.
5. Ofrecer entrada manual cuando el servicio no esté disponible.

**Criterios de aceptación:**

- caída o ausencia de cuenta no impide el flujo manual/local;
- no se reutiliza una comida/revisión antigua como si fuera actual;
- una reimportación idéntica no duplica el hecho local;
- diferencias del proveedor son visibles antes de confirmar;
- tokens no aparecen en logs, fixtures o repositorio.

### Fase 9 — Modo sombra y paridad operacional

**Objetivo:** comparar Next con producción sin efectos laterales.

**Trabajo:**

1. Definir emparejamiento de ejecuciones equivalentes y minimizar datos.
2. Ejecutar Next con los mismos inputs/versiones verificables que Legacy.
3. Comparar input por input, IOB, cada componente, bloqueos, redondeo y total.
4. Clasificar discrepancias: input distinto, versión/perfil, timing, regla, error
   Legacy, error Next o indeterminada.
5. Crear panel/informe de paridad y proceso de triage.
6. Demostrar con tests/controles que Next no escribe tratamientos reales.

**Criterios de aceptación:**

- Legacy es el único escritor y los resultados Next se marcan no accionables;
- toda comparación demuestra equivalencia de entradas o se clasifica como no
  comparable;
- ninguna discrepancia indeterminada se cuenta como coincidencia;
- defectos reproducibles añaden tests;
- métricas, duración mínima y umbrales de promoción son acordados por el
  propietario antes de evaluar la salida; no se inventan en este plan;
- informe de salida registra volumen, cobertura, discrepancias y resolución.

### Fase 10 — Sincronización idempotente y servicios auxiliares

**Objetivo:** replicar el estado local de forma segura sin situar la red en el
camino crítico.

**Trabajo:**

1. Definir contratos de eventos y claves idempotentes por destino.
2. Implementar outbox durable, reintentos, backoff, estados terminales y acción
   manual.
3. Tratar respuesta perdida, replay, duplicado, desorden y conflicto.
4. Conectar uno a uno los destinos aprobados (backup, Nightscout, panel,
   Telegram, NAS/Render), inicialmente sin autoridad clínica.
5. Asegurar que sync no recalcula ni reescribe decisiones confirmadas.

**Criterios de aceptación:**

- repetir N veces un evento con la misma clave produce un único efecto lógico;
- reinicio/pérdida de red no pierde la cola;
- un destino caído no bloquea cálculo/revisión/registro local;
- conflicto queda visible y auditable, sin “última escritura gana” implícita;
- pruebas de caos cubren respuesta perdida y reordenación;
- cada integración puede deshabilitarse sin romper el flujo local.

### Fase 11 — Beta controlada y cambio gradual de autoridad

**Objetivo:** permitir uso real de Next solo bajo controles explícitos y
reversibles.

**Precondiciones:** fases anteriores aceptadas; informe de sombra aprobado;
criterios de promoción, responsables y rollback escritos; ninguna incógnita
clínica crítica abierta.

**Trabajo:**

1. Implementar feature gate y exclusión mutua de autoridad por usuario/flujo.
2. Empezar con cohorte definida por el propietario, no por el agente.
3. Confirmación humana explícita, observabilidad mínima segura y soporte.
4. Ensayar rollback sin perder hechos locales ni crear doble escritura.
5. Revisar cada ampliación con evidencia acumulada.

**Criterios de aceptación:**

- solo uno de Legacy/Next puede registrar tratamiento real en cada momento;
- estado de autoridad es visible y auditable;
- rollback probado restaura el flujo autorizado sin duplicar eventos;
- métricas/periodo/criterios definidos por el propietario se cumplen;
- incidentes y discrepancias están resueltos o aceptados explícitamente;
- cada ampliación de cohorte tiene aprobación registrada.

### Fase 12 — Promoción, estrategia iOS y retirada reversible

**Objetivo:** convertir Next en principal y reducir infraestructura auxiliar sin
perder reversibilidad ni portabilidad.

**Trabajo:**

1. Aprobar formalmente el cambio de autoridad y ejecutar rollout gradual.
2. Mantener Legacy como rollback durante el periodo decidido por el propietario.
3. Congelar, no borrar, código/configuración/histórico de NAS/Render cuando se
   apruebe; conservar exportaciones y procedimiento de restauración.
4. Validar que el motor/contratos/golden vectors se consumen desde el prototipo o
   cliente iOS según el ADR, sin duplicar lógica clínica.
5. Documentar operación offline, backup/restore, incidentes y recuperación.

**Criterios de aceptación:**

- Next funciona como autoridad única bajo la configuración aprobada;
- dependencias externas pueden caer sin impedir el camino crítico local;
- backup, restore y rollback se han ensayado;
- Legacy y servicios retirados permanecen recuperables durante la retención
  aprobada;
- iOS consume el mismo motor o binding aprobado y los mismos golden vectors;
- informe final y decisión de cierre están integrados en `origin/main`.

## 6. Orden recomendado de los primeros cambios

No intentar construir toda una fase en un solo cambio. Secuencia inicial:

1. Gobierno: `AGENTS.md`, plan, ADR/risk templates.
2. Toolchain: ADR del motor compartido y esqueleto mínimo compilable.
3. Verificación: `scripts/verify.ps1` y CI.
4. Auditoría: inventario Legacy sin efectos laterales.
5. Contratos: estados de glucosa/perfil/IOB y códigos de bloqueo.
6. Vectores: formato, procedencia y primer conjunto aprobado.
7. Motor: primera regla aprobada con paridad y fail-closed.
8. Android: persistencia mínima; después adaptador Dexcom local.

Cada cambio debe indicar fase, criterio cubierto, pruebas, riesgos y pendientes.

## 7. Comandos iniciales para Codex

Ejecutar en PowerShell antes de cada encargo:

```powershell
Set-Location E:\projects\Bolus-ai-next
git fetch --prune origin
git status --short --branch
git log -5 --oneline --decorate
Get-Content -Raw .\AGENTS.md
Get-Content -Raw .\docs\ARCHITECTURE.md
Get-Content -Raw .\docs\EXECUTION_PLAN.md
Get-Content -Raw .\docs\LEGACY_SOURCE_MAP.md
```

Si la tarea necesita información de Legacy, resolver también el SHA remoto con
los comandos de `docs/LEGACY_SOURCE_MAP.md` y anotarlo en el informe/commit.

Si el árbol no está limpio, Codex debe identificar y preservar los cambios
existentes. Si `HEAD` no parte de `origin/main`, debe aclarar la rama/alcance antes
de mezclar trabajo.

Para iniciar una rama cuando proceda:

```powershell
git switch main
git pull --ff-only origin main
git switch -c feature/<fase>-<descripcion-corta>
```

No ejecutar `pull`, cambio de rama o rebase sobre cambios locales no entendidos.

## 8. Comandos de verificación y entrega

Antes del commit:

```powershell
Set-Location E:\projects\Bolus-ai-next
if (Test-Path .\scripts\verify.ps1) { & .\scripts\verify.ps1 }
if ($LASTEXITCODE -ne 0) { throw "Verificación fallida" }
git diff --check
git status --short
git diff
```

Después de revisión y aprobación aplicables:

```powershell
git add -- <archivos-del-cambio>
git diff --cached --check
git diff --cached
git commit -m "docs: add executable safety-first implementation plan"
git fetch origin
```

Integrar mediante la protección/revisión configurada para el repositorio. No usar
`push --force` ni saltarse checks. Una vez que el cambio deba estar en `main`,
comprobar el estado publicado:

```powershell
Set-Location E:\projects\Bolus-ai-next
git fetch origin
$delivered = git rev-parse HEAD
git merge-base --is-ancestor $delivered origin/main
if ($LASTEXITCODE -ne 0) { throw "Entrega incompleta: HEAD no está en origin/main" }
$remote = git rev-parse origin/main
Write-Output "Entregado: $delivered"
Write-Output "origin/main: $remote"
git status --short --branch
```

Si CI existe, verificar además que el commit exacto de `origin/main` está verde.
Una ejecución anterior o de otro SHA no sirve como evidencia.

## 9. Definición de terminado

Una fase o cambio no termina por existir localmente. Termina cuando:

- satisface todos sus criterios aplicables y puertas globales;
- no introduce defaults/suposiciones clínicas ni debilita fail-closed;
- Legacy no fue modificado ni recibió escrituras;
- tests y verificaciones aplicables pasan con evidencia del SHA entregado;
- decisiones, riesgos y discrepancias quedan documentados;
- revisión clínica/propietario se obtuvo cuando corresponde;
- el commit está integrado en `origin/main` y la copia local queda limpia.

Si cualquiera de estas condiciones falta, informar “pendiente” o “bloqueado” con
la causa exacta; nunca declarar finalización parcial como completa.
