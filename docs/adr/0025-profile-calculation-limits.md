# ADR 0025: límites del cálculo en el perfil versionado

- Estado: aceptado para desarrollo y pruebas, según la autorización del
  propietario de 2026-10-12 (AGENTS.md §3). No aprueba ningún valor ni uso
  terapéutico
- Fecha: 2026-10-12
- Fase y criterio de aceptación: fase 6 (perfil versionado con todos los campos
  requeridos, sin defaults) y fase 7 (cálculo local con entradas trazadas), en
  modo de prueba
- Responsables de decisión: propietario. Las decisiones técnicas reversibles las
  toma el agente (EXECUTION_PLAN §3.3)
- Evidencia clínica requerida: pendiente. Los valores los introduce y confirma
  el usuario. Las reglas que los usan siguen siendo candidatas (ADR 0024, LC-009)

## Contexto

El ADR 0024 dejó el paso de redondeo, el bolo máximo y la corrección máxima como
parámetros que se tecleaban en cada cálculo de prueba, porque el esquema 1 del
perfil no los tenía. Eso obliga a repetirlos, no los confirma y no los versiona.

En Legacy (`main` remoto resuelto de nuevo: `bcac891d85e6fb3e84398c118061e6aeae3f73f1`,
el mismo SHA del ADR 0024) son campos únicos de
`backend/app/models/settings.py::UserSettings` (`round_step_u`, `max_bolus_u`,
`max_correction_u`), no franjas horarias. Legacy les da defaults (`0.5`, `10`,
`5`), los omite de `compute_hash` y, si son inválidos, sustituye todo el perfil
por los defaults (`bolus_calc_service.py`). Next no copia nada de eso
(LC-001, LC-009, LC-012).

## Decisión

### 1. Esquema de contenido 2

`ProfileCatalog.CURRENT_SCHEMA = 2`. El esquema 2 tiene los mismos parámetros
con franjas que el 1 y añade tres límites de perfil (`ProfileLimit`):
`round_step`, `max_bolus` y `max_correction`, siempre en U. No dependen de la
unidad de glucosa y no se comprueba ningún rango clínico. La representación es
la misma `CanonicalDecimal` del resto del perfil, y `0` es distinto de «sin
configurar».

- Un perfil nuevo nace en esquema 2 con los tres límites sin configurar.
- Una versión de esquema 1 no tiene límites. `ProfileContent.limit()` devuelve
  `null`, que significa desconocido. Nunca se rellena ni se migra a esquema 2.
- Al editar desde una versión de esquema 1, el contenido pasa a esquema 2 solo
  cuando el usuario introduce un límite. Los otros dos quedan sin configurar. Si
  los vacía todos, el editor vuelve a esquema 1 para no crear una versión que
  solo cambie de esquema.
- Una edición manual no puede bajar de esquema respecto de la versión de la que
  parte (`profile.edit.schema_downgrade`). Esta regla se comprueba al escribir y
  al leer el historial. Una restauración exacta conserva el esquema de la versión
  restaurada, también el 1, y con él sus límites desconocidos.
- La huella canónica añade una línea `limit=<código>:<valor|~>` por límite, en
  orden de código, solo en esquema 2. El texto y la huella de cualquier versión
  de esquema 1 no cambian.
- Los límites no forman parte de la completitud del ADR 0014. Sin ellos se puede
  confirmar la versión: el motor bloquea solo el paso que los necesita. Al
  confirmar una versión se confirman también sus límites, porque la huella los
  incluye.

### 2. `clinical-profile.db` v3

Nueva tabla `profile_limits(version, limit_code, value)` con clave
`(version, limit_code)`, el mismo `CHECK` de texto decimal que las franjas y tres
triggers: no admite `UPDATE` ni `DELETE`, y solo admite inserciones para la
última versión. Las definiciones v1 y v2 no cambian. Su texto queda congelado en
`ClinicalProfileSchema.V3_ADDITIONS`, con un resumen SHA-256 en
`ClinicalProfileSchemaTextTest`.

La migración v1 → v3 o v2 → v3 se ejecuta en la transacción del helper:

1. verifica las definiciones de la versión de origen, `foreign_key_check` y
   `quick_check`;
2. crea lo que falte (`V2_ADDITIONS` si viene de v1, y `V3_ADDITIONS`);
3. verifica v3 y valida las versiones con el lector v3 antes del commit. Los
   eventos no se tocan: un historial de eventos dañado sigue fallando cada
   lectura con su propio motivo, como antes de migrar.

No reescribe ninguna fila, no crea confirmaciones ni filas de límites. Cualquier
fallo deshace todo y el archivo se queda en su versión.

Al leer, una versión de esquema 1 con filas de límites o una de esquema 2 a la
que le falte alguna son corrupción (`profile.storage.invalid_record`). Un valor
alterado no coincide con la huella y falla igual.

### 3. Motor y cálculo de prueba

`ProfileSnapshot` lleva `roundStep`, `maxBolus` y `maxCorrection`.
`CandidateRequest` ya no tiene esos campos. El motor (`0.3.0-candidate`, codec
`candidate-request/v2`) toma los límites solo de la instantánea del perfil:

- perfil no disponible: su causa;
- perfil sin confirmar: `profile.unconfirmed`;
- límite ausente: la causa del productor, sin cambios.

Android traduce `null` a `profile.<código>.not_in_schema` y «sin configurar» a
`profile.<código>.not_configured`. La huella de entradas incluye cada límite como
`profile.<código>=…`. Los dos casos bloquean solo su paso: B2 sin corrección
máxima, D sin paso y E sin bolo máximo. Lo que depende de esos pasos queda
bloqueado. Desaparece el aviso `limits.entered_for_test_not_in_profile`.

En Bolo los límites ya no se teclean. Se muestran en solo lectura con la versión
del perfil, su confirmación y la causa de cada ausencia. Las dependencias del
resultado incluyen la instantánea completa del perfil, así que una versión nueva,
una revocación o un límite distinto descartan el resultado anterior.

## Alternativas consideradas

- **Límites como franjas horarias.** Descartada: Legacy los define únicos y el
  editor de franjas permitiría dividirlos sin evidencia.
- **Rellenar las versiones antiguas o migrarlas a esquema 2.** Descartada: sería
  inventar un dato y cambiaría huellas ya confirmadas.
- **Exigir los límites para la completitud.** Descartada: bloquearía la
  confirmación y, con ella, comida y corrección. AGENTS.md §3 pide bloquear solo
  el componente que dependa del dato ausente.
- **Mantener campos manuales por cálculo como alternativa.** Descartada: habría
  dos orígenes para el mismo dato y se podría usar un límite sin versionar ni
  confirmar.

## Consecuencias

- El usuario introduce los límites una vez, en Perfil. Cada versión posterior
  los conserva hasta que los cambie.
- Una app anterior no abre un archivo v3 (`onDowngrade` falla cerrado). Rollback:
  restaurar la copia previa del archivo v2 o v1. Las pruebas del ADR 0014 ya
  cubren que esa copia sigue siendo legible.
- iOS usará el mismo `ProfileContent`, el mismo codec y el mismo motor KMP.

## Seguridad y pruebas

- `ProfileLimitsTest` (11): esquema 1 intacto, texto canónico estricto del
  esquema 2, huella por límite y ausencia frente a cero, paso a esquema 2 solo al
  introducir un valor, vuelta del editor, conservación en ediciones posteriores,
  bajada de esquema rechazada al escribir y al leer, restauración exacta y
  confirmación sin límites.
- `CandidateEngineTest`: límites solo desde el perfil, causa del productor y
  líneas de la huella. Los 12 vectores de Legacy siguen dando paridad exacta.
- `TestCalculationTest`: límites desde la versión sin teclearlos, esquema 1
  desconocido con bloqueo parcial, límite sin configurar, texto de solo lectura e
  invalidación y huella distintas al cambiar cada límite o el esquema.
- `ClinicalProfileSchemaTextTest`: texto v3 congelado sin tocar v1 ni v2.
- Pruebas instrumentadas, pendientes de ejecutar en el Pixel:
  `ClinicalProfileLimitsDeviceTest` (migraciones v1 y v2 → v3 con confirmación,
  persistencia y reinicio, triggers, filas manipuladas) y
  `ClinicalProfileDeviceTest.limitsAreTypedOnceSavedInANewVersionAndOlderVersionsStayUnknown`.
  Además se actualizaron las pruebas existentes de migración y definiciones a v3.

## Condiciones para revisar este ADR

- El propietario aprueba rangos o valores para los límites, o una regla de
  redondeo distinta (LC-009).
- Aparece en Legacy otro uso de estos campos que no esté trazado aquí.
- Hace falta un límite más, como el techo de IOB (`max_iob_u`), que sería el
  esquema 3.
