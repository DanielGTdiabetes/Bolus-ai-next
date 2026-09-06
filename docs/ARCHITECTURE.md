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

## Convivencia con Legacy

`bolus_ai` seguirá siendo producción mientras Bolus AI Next se valida.

Fases previstas:

1. desarrollo aislado;
2. modo sombra/comparación sin registrar tratamiento real desde Next;
3. beta controlada;
4. migración gradual;
5. congelación posterior de NAS/Render cuando Next haya demostrado estabilidad.

Durante la convivencia solo un sistema tendrá autoridad para registrar un tratamiento real en cada fase para evitar duplicados.
