# ADR 0003: límite Android fail-closed para glucosa local

- Estado: aceptado técnicamente para resultados no disponibles; productor local
  autorizado, lectura clínica aún no aprobada
- Fecha: 2026-09-06
- Fase: infraestructura preparatoria para Fase 5
- Prioridad: Android; sin nueva implementación iOS

## Contexto

La aplicación Android de la PR #9 muestra un bloqueo, pero construye la causa
directamente en la actividad. El siguiente incremento necesita una frontera
entre UI, caso de uso y futura integración local sin inventar el contrato
Dexcom, aceptar extras como prueba del emisor o crear un estado «válido» sin
valor, unidad, timestamps, identidad y políticas aprobadas.

La auditoría de solo lectura permanece fijada en
`DanielGTdiabetes/bolus_ai@f5417721d8019a9831126f4d843edfc4de87653d`.
Demuestra que existe una integración histórica, pero no autoriza sus literales,
validaciones ni modelo de permisos para Next. Las decisiones LC-002 y LC-003 y
el riesgo R-010 continúan abiertos.

La [investigación oficial, del productor y del dispositivo del 2026-09-06](../validation/dexcom-g7-android-local-reception-2026-09-06.md)
confirma que la aplicación Dexcom G7 modificada y controlada por el propietario
es la fuente primaria prevista. Su
[transporte observado](../contracts/modified-dexcom-g7-broadcast-observed-v1.md)
demuestra la construcción y distribución del broadcast, pero la compilación
instalada todavía no está archivada como release reproducible y el permiso es
`dangerous`. La Web API REST/OAuth queda solo como contingencia online con
retraso, no como parte del camino crítico.

## Decisión

Se añade en el módulo Android:

```text
MainActivity
    -> ReadLocalGlucoseStatus
        -> LocalGlucoseSourcePort
            -> PendingDexcomSource (actual)
            -> adaptador Dexcom autenticado (futuro)
```

- `LocalGlucoseSourceResult` es sellado y solo admite
  `Unavailable(UnavailabilityReason)`.
- No existe rama de lectura disponible en esta versión.
- El caso de uso convierte la causa en el contrato KMP `UnavailableInput` y no
  decide precedencias, vigencia ni validez.
- `PendingDexcomSource` devuelve `policy_not_approved`; no inspecciona el
  dispositivo ni solicita permisos.
- La UI continúa usando el presentador exhaustivo existente y mantiene el
  cálculo bloqueado.
- Excepciones técnicas inesperadas no se convierten silenciosamente en una
  causa clínica o de conectividad.

## Consecuencias

- El límite puede probarse sin SDK, payload ni dato Dexcom simulado.
- Cada fallo conocido debe llegar con un motivo específico y se conserva sin
  colapsarlo.
- Añadir una rama disponible rompe de forma deliberada el `when` exhaustivo y
  exige revisar el consumidor en el mismo cambio.
- La recepción real, permisos, manifest, persistencia, modo avión y validación
  de lectura siguen pendientes; el primer hito offline no está cumplido.
- El puerto es específico del adaptador Android. Los estados no disponibles
  continúan en el núcleo KMP compartido, por lo que iOS no duplica lógica.

## Alternativas descartadas

### Copiar el receiver de Legacy

Rechazado: su existencia es evidencia histórica, no aprobación del contrato ni
de la autenticidad del emisor. También arrastraría validaciones y literales no
aprobados.

### Añadir una lectura sintética o un valor opcional

Rechazado: un ejemplo ejecutable podría confundirse con una lectura real y un
valor opcional permitiría estados ambiguos. Las pruebas solo usan causas
técnicas sin valores clínicos.

### Mantener la causa construida en la actividad

Rechazado: mezclaría composición y UI e impediría sustituir el productor detrás
de un puerto probado cuando el contrato se apruebe.

## Condiciones para ampliar este ADR

Una variante de lectura disponible requiere contrato versionado, trazabilidad y
aprobación de cada decisión pendiente enumerada en
`android-local-glucose-boundary-v1.md`, pruebas del adaptador y persistencia, y
evidencia en dispositivo real sin Internet. También requiere una garantía de
emisor aprobada por el propietario y fijada a una release reproducible: un extra
`packageName`, una acción de intent, el nombre del paquete instalado o la
posesión de un permiso `dangerous` no bastan.
No es válido fijar como ancla de confianza una firma extraída de una compilación
no archivada. No basta con observar un payload o replicar el comportamiento
Legacy.
