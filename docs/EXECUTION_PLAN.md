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

**Alcance Dexcom confirmado por el propietario — 2026-10-11:** partir de la
app Dexcom ya modificada, instalada y utilizada por `bolus_ai`. Next es su
sustituto y debe aprovechar el transporte existente. Adaptar Next; no modificar,
recompilar, reinstalar ni sustituir Dexcom para satisfacer un requisito nuevo
del consumidor. Archivar e identificar la APK instalada mediante solo lectura
no exige reconstruirla. La política estricta de identidad implementada en Next
requiere revisión para ser compatible con ese transporte, sin presentar como
autenticación por mensaje una comprobación de la APK instalada.

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

### 3.3 Entregas funcionales y ejecución proporcionada — 2026-10-11

Por petición del propietario, avanzar por capacidades completas y revisables.
Una entrega puede reunir dominio, persistencia, adaptador, UI y pruebas de un
mismo objetivo. Las fases organizan requisitos; no obligan a una PR por capa,
estado, método o decisión técnica. Separar trabajo sin relación y cambios
clínicos de refactors, dependencias o formato.

- Elegir un resultado observable y criterios de aceptación antes de programar.
  Resolver autónomamente decisiones técnicas reversibles dentro del alcance
  autorizado y de los contratos vigentes.
- Crear un ADR breve cuando cambie la arquitectura, un contrato relevante o una
  decisión de seguridad. Ampliar el ADR existente cuando corresponda; no exigir
  otro ADR ni una ronda de aprobación por cada paso técnico rutinario.
- Agrupar las preguntas clínicas o de alcance que sí necesiten al propietario,
  con opciones concretas. Reutilizar aprobaciones registradas; una petición de
  simplificar el proceso no aprueba reglas clínicas ni amplía autoridad.
- Mientras falte una decisión, bloquear solo la capacidad que dependa de ella y
  seguir con trabajo seguro del objetivo. No sustituir el objetivo por nuevas
  mejoras de diagnóstico o endurecimiento sin un defecto concreto, un riesgo
  identificado o un criterio de aceptación que las justifique.
- Ejecutar pruebas específicas durante el desarrollo y `scripts/verify.ps1`
  sobre la versión final antes de publicar. Repetir lo afectado si hay cambios,
  fallos o nueva evidencia; no repetir auditorías o suites sobre el mismo estado
  sin motivo. Las pruebas de dispositivo se aplican a cambios de Android que
  las requieran, no automáticamente a documentación.
- Mantener una sola evidencia por entrega: alcance, decisiones, comandos y
  resultados, riesgos y pendientes en el informe o PR correspondiente. El
  archivo de continuación enlaza esa evidencia y resume el siguiente objetivo;
  no necesita duplicar el informe en cada documento.
- Conservar revisión de PR, corrección de hallazgos válidos y CI aplicable antes
  de integrar. Una revisión adicional debe responder a un cambio, hallazgo o
  riesgo pendiente, sin abrir ciclos indefinidos por preferencias de estilo.

Siguen siendo obligatorios procedencia y aprobación de reglas clínicas,
fail-closed, pruebas de cálculo/persistencia/sincronización, aislamiento de
Legacy y autoridad única de tratamiento. No se empieza lógica clínica si su
puerta de entrada no está cumplida.

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
   Android, reutilizando el transporte de la Dexcom instalada. La compatibilidad
   debe resolverse en Next sin intervenir en el productor ni en Legacy.
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

### Entrega de interfaz adelantada — 2026-09-12

Por petición del propietario tras PR #19 se adelanta la estructura visual de los
flujos de fases 6–8 y herramientas auxiliares, manteniendo intactas sus puertas
clínicas. [Auditoría](audit/legacy-ui-2026-09-12.md),
[ADR 0007](adr/0007-legacy-android-navigation.md) y
[verificación USB](validation/android-navigation-2026-09-12.md).

Criterios de este incremento: Inicio y navegación equivalente al frontend real,
contraste explícito con Companion, pantallas locales para los accesos observados,
estados ausentes/pending visibles, cálculo y tratamiento bloqueados, restauración
y Atrás, verificación local/USB/CI, integración y árbol limpio. No completa
recepción Dexcom, persistencia, perfil, IOB, motor, importación ni paridad clínica.

### Entrega de borradores de comida — 2026-09-13

Mis platos y la entrada manual desde Bolo disponen de creación/lectura y
corrección por revisiones locales (sin borrado). `shared/meal-drafts` separa plato,
borrador, recomendación y hecho clínico; SQLite Android persiste revisiones con IDs
estables y fallos explícitos. Ausencia no equivale a cero. No incorpora catálogo,
validación nutricional, cálculo, timestamps de ingesta, sync ni tratamiento. Véanse
[ADR 0008](adr/0008-local-meal-drafts.md) y la
[validación del incremento](validation/local-meal-drafts-2026-09-13.md).

### Entrega de selección para revisión en Bolo — 2026-09-26

Incremento preparatorio de fases 2, 4 y 7: selección durable por ID/revisión desde
Mis platos y Comidas, presentación literal de campos/origen/ausencias y detección
de revisiones posteriores sin actualización silenciosa. SQLite v2 añade el slot
de revisión sin reescribir comidas. Guardar una corrección exige reselección
explícita. Cálculo y tratamiento permanecen bloqueados; no completa validación
nutricional ni flujo clínico. Véanse [ADR 0009](adr/0009-local-meal-selection.md)
y [validación](validation/local-meal-selection-2026-09-26.md).

### Entrega de retirada de selección — 2026-09-26

Continuación del ciclo de revisión local de fases 2, 4 y 7: Bolo permite retirar
explícitamente la selección coincidente u obsoleta. El puerto compartido y SQLite
confirman la ausencia solo tras persistir; reintentos son idempotentes y las
revisiones de comida se conservan. Pruebas cubren ausencia, rollback, reinicio,
reselección y lecturas en vuelo. Mantiene cálculo y tratamiento bloqueados, sin
reglas nutricionales nuevas. Véanse la ampliación del [ADR 0009](adr/0009-local-meal-selection.md)
y la [validación](validation/local-meal-selection-removal-2026-09-26.md).

### Entrega de restauración de revisiones — 2026-09-27

Continuación de fases 2, 4 y 7: una revisión anterior se restaura tras
confirmación explícita como contenido de partida del editor. Guardar crea la
revisión siguiente con procedencia `restored_from`, mismo control de conflicto e
idempotencia. SQLite v3 migra desde v1 y v2 sin reescribir filas. La selección de
Bolo no cambia. Cálculo y tratamiento siguen bloqueados. Véanse el
[ADR 0011](adr/0011-restore-meal-revision.md) y la
[validación](validation/local-meal-revision-restore-2026-09-27.md).

### Entrega de perfil clínico local — 2026-09-27

Primer paso de la fase 6 (trabajo 1 y 3): captura local del perfil como
versiones inmutables con unidad y zona declaradas, franjas completas en el
modelo, ausencia distinta de cero, huella canónica, origen auditado, restauración
como versión nueva y conflicto explícito. Un cambio de unidad nunca reinterpreta
valores. Base propia `clinical-profile.db` v1, comidas intactas. Sin límites,
DIA, conversión, propuestas, exportación ni uso clínico: cálculo y confirmación
siguen bloqueados. Véanse el [ADR 0012](adr/0012-local-clinical-profile.md) y la
[validación](validation/local-clinical-profile-2026-09-27.md).

### Entrega del editor de franjas del perfil — 2026-09-28

Continuación de la fase 6: el editor del perfil clínico permite dividir, unir
(solo valores exactamente iguales), mover límites compartidos y editar el valor
de cada franja, con cobertura 00:00–24:00 garantizada por construcción, 24 h y
resolución de 1 minuto. Franjas adyacentes iguales se conservan. Cambiar de
unidad conserva las fronteras de ISF y objetivo y pasa sus valores a «Sin
configurar». Máximo de 48 franjas solo para dividir. Indicador «Modificada» solo
de editor. Sin esquema nuevo, sin límites clínicos ni uso por el motor. Véanse el
[ADR 0013](adr/0013-clinical-profile-segment-editor.md) y la
[validación](validation/profile-segment-editor-2026-09-28.md).

### Entrega de confirmación de datos del perfil, parte 1 — 2026-10-03

Continuación de la fase 6 (trabajo 1 y 2): completitud estructural del perfil
sin límites clínicos, eventos append-only de confirmación y revocación con
identidad de operación, orden por `seq` y estado observado, validación conjunta
del historial, política común idempotente y `clinical-profile.db` v2 con
migración desde v1 verificada por definición y prueba de copia previa. Sin UI
nueva: la revisión, confirmación y revocación en pantalla y el texto de Bolo son
la parte 2. Cálculo y tratamiento siguen bloqueados en todos los estados. Véanse
el [ADR 0014](adr/0014-clinical-profile-confirmation-eligibility.md) y la
[validación](validation/profile-confirmation-domain-2026-10-03.md).

### Entrega de confirmación de datos del perfil, parte 2 — 2026-10-03

Pantalla de la parte 2 del ADR 0014 sobre el dominio de la parte 1, sin cambios
de esquema ni de reglas: estados E1 a E11 con sus textos, revisión de solo
lectura desde una lectura nueva con todas las franjas y los `0` destacados,
confirmar y retirar como acciones separadas con `operation_id` conservado en
reintentos y recreación, resolución de operaciones pendientes por identidad,
historial de confirmaciones, retiradas y superadas, y Bolo con «Perfil · no se
usa para calcular» sin leer el perfil. Cálculo y tratamiento siguen bloqueados.
Integrada mediante la PR #33 (merge commit `8940498`, Verify 37131464837 en
verde). Véase la [validación](validation/profile-confirmation-ui-2026-10-03.md).

### Entrega del contrato de indisponibilidad v2 — 2026-10-03

Implementación del [ADR 0015](adr/0015-profile-unavailability-contract.md)
(fases 2 y 6), autorizada por el propietario con D1 a D10: contrato v2 aditivo
en el núcleo compartido (53 combinaciones admitidas, elevación total de las 52
de v1, gramática y límites del detalle, rechazos con identificador estable,
copias defensivas y unión de detalles sin recorte), módulo común
`shared/profile-unavailability` que traduce `ProfileGateState` según las
secciones 6.1 y 6.2, y `ReadOverview` con `input.profile.policy_not_approved`
estático (D7). v1, SQLite, huellas, serialización y confirmaciones sin cambios.
Ningún consumidor usa el traductor. iOS no verificado. Aprobada por el
propietario con la decisión D11 (rechazo de fallos ajenos a la lectura) e
integrada mediante la PR #36 (merge commit `a57d2e5`, Verify 37142410544 en
verde). Véanse el [contrato](contracts/unavailable-input-v2.md) y la
[validación](validation/profile-unavailability-v2-2026-10-03.md).

### Entrega del primer consumidor del informe del perfil — 2026-10-04

Implementación de la opción A del
[ADR 0016](adr/0016-profile-unavailability-consumer.md) (fases 2 y 6), con A1 a
A6 y A4 precisada: Ajustes → Cálculo muestra, en un bloque técnico plegado, el
informe v2 del estado que ya presenta, derivado en cada render del modelo de la
pantalla. Solo se captura el rechazo conocido del traductor. Sin lecturas
nuevas ni textos nuevos. Bolo, `ReadOverview`, el motor, SQLite y el contrato
no cambian. Cálculo y tratamiento siguen bloqueados. Integrada mediante la PR
#39 (merge commit `c583e6a`, Verify 37177820636 en verde). Véase la
[validación](validation/profile-unavailability-screen-2026-10-04.md).

### Entrega del informe del perfil en Bolo y Diagnóstico — 2026-10-04

Implementación del [ADR 0017](adr/0017-bolo-profile-unavailability-details.md)
(fases 2 y 6), aceptado con B2 a B10 y la ampliación a Diagnóstico:
«Detalles del bloqueo» de Bolo y Diagnóstico muestra un único informe v2 con
glucosa, IOB y comida elevadas y las causas reales del perfil. E1 antes de la
primera lectura, sin reintentos, sin modificar versiones, franjas ni eventos
(la apertura de SQLite puede crear o migrar el esquema) y sin textos nuevos. Solo esas
pantallas y Ajustes → Cálculo inician lecturas del perfil. `ReadOverview`, el
motor, SQLite y el contrato no cambian. Cálculo y tratamiento siguen
bloqueados. `verify.ps1` comprueba el manifiesto empaquetado de los APK de
instrumentación. Aprobada por el propietario e integrada mediante la PR #42
(merge commit `929d5c1`, Verify 37901021591 en verde). Véase la
[validación](validation/bolo-profile-details-2026-10-04.md).

### Entrega del parser estructural Dexcom G7 — 2026-10-11

Preparación de la fase 5 (trabajo 3, parte estructural) según el
[ADR 0018](adr/0018-dexcom-g7-payload-parser.md), aceptado con D1 a D4:
`shared/dexcom-g7-payload` lee una copia tipada del broadcast observado y el
paquete autenticado, rechaza el mensaje completo ante cualquier defecto, lista
campos desconocidos por nombre, colapsa duplicados idénticos y rechaza los
conflictivos. El valor no tiene rango y toda lectura queda
`NOT_APPROVED_FOR_USE`. Sin consumidor, receiver, permisos ni persistencia:
`PendingDexcomSource` y la APK no cambian. Véase la
[validación](validation/dexcom-g7-payload-parser-2026-10-11.md).

### Entrega de la composición de recepción Dexcom G7 — 2026-10-11

Continuación de la fase 5 (trabajo 2 y estados del trabajo 4) según el
[ADR 0019](adr/0019-dexcom-g7-reception-composition.md): Android autentica al
emisor, solo después copia el `Bundle` sin convertir tipos, llama al parser
con el paquete autenticado y traduce el resultado a `authentication_failed`,
`parse_failed` o `policy_not_approved`. Sin receiver, permisos, persistencia
ni UI. Véase la
[validación](validation/dexcom-g7-reception-composition-2026-10-11.md).

### Entrega del registro local de observaciones Dexcom G7 — 2026-10-11

Continuación de la fase 5 (persistencia del trabajo 3, duplicados del trabajo
4) según el [ADR 0020](adr/0020-dexcom-g7-observation-log.md), con E1 a E4 del
propietario: base propia `dexcom-observations.db` append-only con mensajes,
rechazos sin contenido y lecturas con huella SHA-256 del transmisor,
duplicados contados, conflictos enlazados a la primera observación y fallo de
escritura como `persistence_failed`. Sin receiver ni consumidor. Véase la
[validación](validation/dexcom-g7-observation-log-2026-10-11.md).

### Entrega de la vista del registro Dexcom en Diagnóstico — 2026-10-11

Fase 5 (estado del trabajo 5) según el
[ADR 0021](adr/0021-dexcom-log-diagnostics-view.md), con F1 y F2 del
propietario: Diagnóstico lee el registro del ADR 0020 en cada entrada y
muestra, plegados, solo recuentos, rechazos por código, última recepción y
emisor. Nunca valores de glucosa. Leer no crea el fichero. Véase la
[validación](validation/dexcom-log-diagnostics-2026-10-11.md).

### Entrega del receiver Dexcom G7 desactivado — 2026-10-11

Fase 5 (trabajo 2), incremento preparatorio según el
[ADR 0022](adr/0022-dexcom-g7-receiver-disabled.md), con R1 a R3 del
propietario: `DexcomG7Receiver` declarado con `android:enabled="false"`,
activable solo por `adb`, sin el permiso del productor. Comprueba la acción,
autentica antes de leer y solo guarda lo autenticado. Con la política `Pending`
rechaza todo emisor sin leer ni abrir el registro. Diagnóstico muestra el
estado del componente y de la política. No valida la recepción con Dexcom real:
en ese incremento se dejaron pendientes release reproducible, opt-in de
identidad, anclas y permiso. La rectificación de alcance Dexcom de este plan
sustituye la exigencia de cambiar el productor por adaptar Next al envío actual.
Véase la [validación](validation/dexcom-g7-receiver-disabled-2026-10-11.md).

### Fallos de almacenamiento del receiver Dexcom G7 — 2026-10-11

Corrección tras revisar `main` en `ccfb753` (R10 del
[ADR 0022](adr/0022-dexcom-g7-receiver-disabled.md)): una excepción al abrir,
escribir o cerrar el registro en el hilo de escritura ya no puede cerrar el
proceso. Se entrega `DexcomReceipt.StorageFailed` con un código estable por
etapa y la glucosa sigue bloqueada como `persistence_failed`. Sin cambios en la
política (`Pending`) ni en la lógica clínica. Véase la
[validación](validation/dexcom-g7-receiver-storage-failure-2026-10-11.md).

### Fallos de la fase síncrona del receiver Dexcom G7 — 2026-10-11

Continuación de R10 (R11 del
[ADR 0022](adr/0022-dexcom-g7-receiver-disabled.md), decisiones 1a a 5a del
propietario con los ajustes de alcance y etapas): una excepción en
`DexcomG7Intake.accept` (autenticación, lectura de extras, parser o reloj,
cada una en su propio límite) ya no puede cerrar el proceso desde el hilo principal. Se
entrega `DexcomReceipt.IntakeFailed` con un código estable por etapa, sin
guardar nada. Un fallo de autenticación no lee extras, no abre el registro ni
lee el reloj. La glucosa sigue bloqueada (`authentication_failed`,
`parse_failed` o `clock_anomaly`). Los `Error` de la JVM no se capturan. Política `Pending` y
lógica clínica sin cambios. Véase la
[validación](validation/dexcom-g7-receiver-intake-failure-2026-10-11.md).

### Secuencia de capacidades

La secuencia inicial siguiente queda como referencia de dependencias, no como
una lista que deba reiniciarse ni una obligación de dividir cada capacidad:

1. Gobierno: `AGENTS.md`, plan, ADR/risk templates.
2. Toolchain: ADR del motor compartido y esqueleto mínimo compilable.
3. Verificación: `scripts/verify.ps1` y CI.
4. Auditoría: inventario Legacy sin efectos laterales.
5. Contratos: estados de glucosa/perfil/IOB y códigos de bloqueo.
6. Vectores: formato, procedencia y primer conjunto aprobado.
7. Motor: primera regla aprobada con paridad y fail-closed.
8. Android: persistencia mínima; después adaptador Dexcom local.

Cada cambio debe indicar fase, criterio cubierto, pruebas, riesgos y pendientes.

### Prioridad de continuación — 2026-10-11

La [PR #53](https://github.com/DanielGTdiabetes/Bolus-ai-next/pull/53)
(ADR 0022 R11) quedó integrada en `origin/main` mediante `740af25` durante
este ajuste. No queda pendiente de fusión; comprobar el CI del SHA integrado.

1. Concentrar la siguiente entrega funcional en el primer hito Android: recepción
   local Dexcom, persistencia y presentación con origen, hora y estado, sin
   habilitar cálculo. Reunir en una sola propuesta las decisiones pendientes de
   identificación de la APK instalada, política compatible con su envío actual,
   permiso y presentación; no exigir `setShareIdentityEnabled(true)` al productor;
   implementar después el recorrido completo dentro del alcance aprobado.
   La combinación de permiso, conjunto de titulares y anclas se evalúa en el
   [ADR 0023](adr/0023-dexcom-existing-transport-trust.md), todavía propuesto y
   pendiente de aceptación de sus garantías condicionadas y riesgos residuales.
2. Si faltan esas decisiones o artefactos, dejar explícito qué bloquean y avanzar
   solo con preparativos independientes del mismo hito. Retención, contadores,
   exportación y refinamientos visuales quedan fuera de la ruta inmediata salvo
   que sean necesarios para un riesgo concreto o los priorice el propietario.

La recepción y presentación con Dexcom real conservan las puertas de los ADR
vigentes que no contradigan el alcance Dexcom confirmado arriba. Revisar la
política de Next en el ADR existente; esta decisión no activa el receiver ni
aprueba una política alternativa de confianza. No aprobar por inferencia unidad,
timestamps, vigencia ni uso clínico.
La validación offline debe preservar la recepción del sensor: según la decisión
registrada en el ADR 0022, no activar el modo avión que corta su Bluetooth;
documentar y probar la ausencia de dependencia de Internet sin interrumpirlo.

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
git switch -c codex/<capacidad>-<descripcion-corta>
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
