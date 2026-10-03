# Arquitectura de Bolus AI Next

## Decisión principal

Bolus AI Next adopta una arquitectura **local-first**. Es el sustituto previsto
de Bolus AI Legacy y debe alcanzar paridad funcional aprobada ejecutando en el
dispositivo móvil las funciones clínicas críticas y conservando el estado
operativo necesario para seguir funcionando sin NAS, Render o Telegram.

## Principios

1. Seguridad clínica por encima de disponibilidad o comodidad.
2. No asumir valores clínicos por defecto cuando falten datos.
3. IOB desconocido nunca equivale automáticamente a 0 U.
4. No usar glucosa antigua como si fuera actual.
5. No reutilizar silenciosamente datos nutricionales antiguos.
6. Un evento confirmado se registra localmente antes de cualquier sincronización.
7. La sincronización debe ser idempotente y no recalcular decisiones ya confirmadas.
8. El estado local es la fuente de verdad durante el uso diario.
9. Las integraciones de red añaden capacidades, pero no deben formar parte del camino crítico del cálculo.

## Componentes previstos

```text
Bolus AI Next
├── shared/
│   └── bolus-engine/        # Motor clínico común y determinista
├── android/                 # Aplicación Android
├── ios/                     # Futuro cliente iOS
├── docs/                    # Arquitectura, migración y reglas
└── tests/                   # Vectores y pruebas de regresión
```

## Motor clínico

Debe aislarse del UI y de la red. Como mínimo deberá cubrir:

- ratios de hidratos;
- ISF/CF;
- objetivos;
- IOB;
- DIA/curva de acción;
- correcciones;
- fibra y reglas nutricionales que se validen;
- límites máximos;
- redondeos;
- condiciones de bloqueo de seguridad.

Antes de implementarlo se auditará el motor actual de `bolus_ai` y se crearán vectores de referencia para demostrar paridad.

## Persistencia local

La aplicación Android deberá utilizar una base de datos durable, previsiblemente Room/SQLite, para almacenar al menos:

- glucosa y metadatos de origen;
- comidas y revisiones;
- alimentos normalizados;
- cálculos/recomendaciones;
- bolos confirmados;
- perfil clínico y versión/config hash;
- cola de sincronización e idempotency keys.

### Primer adaptador: borradores de comida

`shared/meal-drafts` define el modelo KMP y el puerto de persistencia para
borradores y platos guardados. Android implementa el puerto con SQLite y conserva
revisiones append-only. Los campos nutricionales son ausencia o texto introducido;
el módulo no interpreta números ni aplica reglas. Guardar no crea recomendación,
ingesta confirmada, tratamiento ni item de sincronización. Véase el
[ADR 0008](adr/0008-local-meal-drafts.md).

La revisión de entrada de Bolo consume `ReviewMealSelection` del mismo módulo:
SQLite (v2 en adelante) guarda una referencia a una revisión inmutable y la lee junto a la
última revisión. La UI distingue selección ausente, fallo y obsolescencia sin
reemplazar el snapshot. La selección sobrevive al relanzamiento y no constituye
ingesta, cálculo ni confirmación. Véase el
[ADR 0009](adr/0009-local-meal-selection.md).
La retirada explícita usa el mismo puerto compartido: elimina solo el slot dentro
de una transacción y devuelve ausencia tras confirmar el commit. No modifica
revisiones ni esquema; fallos e intentos en curso mantienen el bloqueo.
`ReadMealHistory` consulta todas las revisiones de un registro sin escribir
([ADR 0010](adr/0010-local-meal-revision-history.md)). `MealDrafts.restore` carga
de forma pura una revisión anterior como editor; guardar usa el mismo puerto y
SQLite v3 registra `restored_from` sin reescribir revisiones
([ADR 0011](adr/0011-restore-meal-revision.md)).

### Perfil clínico local

`shared/clinical-profile` define contenido, catálogo, decimal canónico, huella
SHA-256, reglas de transición, política de escritura y casos de uso. Android lo
persiste en `clinical-profile.db`, independiente de `meal-drafts.db`, con
versiones append-only protegidas por triggers. Cada versión lleva su unidad de
glucosa junto a sus valores. Ni el motor ni `ReadOverview` dependen del módulo, y
`verify.ps1` lo comprueba. Véase el [ADR 0012](adr/0012-local-clinical-profile.md).

La confirmación de datos (ADR 0014) vive en el mismo módulo: completitud
estructural, eventos `confirm`/`revoke` append-only fuera de la huella,
validación conjunta de versiones y eventos (`ProfileRecord`), política común y
estado en cuatro dimensiones con elegibilidad clínica siempre bloqueada. El
adaptador Android usa `clinical-profile.db` v2, verifica el esquema por
definición al abrir y al migrar desde v1, y evalúa la política en una
transacción exclusiva. La pantalla de Ajustes → Cálculo presenta esos estados y
llama a los casos de uso (`readState`, `confirmRequest`, `revokeRequest`,
`record`, `resolvePending`); no decide reglas ni abre SQLite. Bolo muestra un
texto fijo y no lee el perfil. Véase el
[ADR 0014](adr/0014-clinical-profile-confirmation-eligibility.md).

### Contrato de indisponibilidad v2 y traductor del perfil

`shared/bolus-engine` contiene, junto al contrato v1 congelado, el contrato v2
(`InputUnavailability`, `InputUnavailabilityReport`): los 13 motivos v1 más
`unconfirmed`, admitido solo para `profile`, con detalle de dominio en códigos
de texto validados, copias defensivas y `@Throws` hacia Swift. El módulo común
`shared/profile-unavailability` traduce un `ProfileGateState` recibido a un
informe v2. Depende del perfil y del contrato. El motor y el perfil no dependen
de él y ningún consumidor lo usa todavía: conectarlo exige su propio ADR.
`ReadOverview` sigue sin leer el perfil y emite `input.profile.policy_not_approved`
estático (D7). Véanse el [ADR 0015](adr/0015-profile-unavailability-contract.md)
y el [contrato v2](contracts/unavailable-input-v2.md).

```text
shared/clinical-profile ──┐
                          ├──> shared/profile-unavailability   (sin consumidores)
shared/bolus-engine ──────┘
     ^ contrato v1 y v2, sin dependencia del perfil
```

## Conectividad

Sin Internet deben seguir disponibles las funciones que solo requieren datos locales válidos.

MyFitnessPal, Nightscout, backup, panel web, Telegram u otras integraciones se consideran servicios externos. Si alguno falla, la aplicación debe degradarse de forma explícita y segura, nunca sustituir datos faltantes por datos antiguos sin indicarlo.

## Límite Android de glucosa local

La recepción local de glucosa entra por un puerto Android separado de la UI. El
caso de uso traduce causas ya determinadas al contrato compartido de entradas no
disponibles; no interpreta payloads ni decide vigencia. La aplicación Dexcom G7
modificada y controlada por el propietario es el productor local primario; la
Web API es solo contingencia online. Mientras el contrato versionado del
productor, la autenticidad del emisor, las unidades, timestamps, identidad y
políticas no estén aprobados, el puerto no tiene una variante de lectura válida
y devuelve `policy_not_approved`. Véanse el [ADR 0003](adr/0003-android-local-glucose-boundary.md)
y el [contrato v1](contracts/android-local-glucose-boundary-v1.md).

## Presentación Android local

La estructura de UI usa Views/XML y AndroidX Activity para el ciclo de vida y
Atrás. `AppNavigation` mantiene destinos/pila sin Android ni lógica clínica;
`ScreenRenderer` presenta estados y enlaces internos. `MainActivity` compone
`ReadOverview`, que consume el caso de uso de glucosa y los contratos compartidos
de entradas no disponibles, `MealDraftModel`, que llama al módulo compartido y
al adaptador SQLite, y `ClinicalProfileModel`, que hace lo mismo para el perfil. No hay WebView ni acceso remoto. La pila, el scroll, el editor
de borrador y la sección de Ajustes se restauran; las revisiones confirmadas viven
en SQLite.
La implementación y los límites están en el [ADR 0007](adr/0007-legacy-android-navigation.md).

## Convivencia con Legacy

`bolus_ai` seguirá siendo producción mientras Bolus AI Next se valida.

Fases previstas:

1. desarrollo aislado;
2. modo sombra/comparación sin registrar tratamiento real desde Next;
3. beta controlada;
4. migración gradual;
5. congelación posterior de NAS/Render cuando Next haya demostrado estabilidad.

Durante la convivencia solo un sistema tendrá autoridad para registrar un tratamiento real en cada fase para evitar duplicados.
