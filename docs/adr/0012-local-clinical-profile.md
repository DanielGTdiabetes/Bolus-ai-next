# ADR 0012: perfil clínico local versionado (captura, sin uso clínico)

- Estado: aceptado para captura local. No habilita uso clínico.
- Aprobación: el propietario aprobó el ADR y las ocho recomendaciones de la
  sección 12 el 2026-09-27, y añadió la sección 4.4 sobre unidades.
- Fecha: 2026-09-27.
- Fase y criterio de aceptación: primer paso del hito 6 (fase 6, trabajo 1 y 3).
  Guardar, editar, consultar y restaurar versiones inmutables de un perfil
  clínico local, sin que ningún valor llegue al cálculo, a una recomendación ni
  a un tratamiento.
- Responsables de decisión: propietario del proyecto.
- Evidencia clínica requerida: no aplica en esta fase. No se introduce ninguna
  regla, límite ni valor por defecto. Las decisiones clínicas quedan listadas
  como pendientes (sección 12).
- Base Next: `fe466bf` (PR #27 integrada). CI del SHA exacto:
  [Verify 36331996354](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/36331996354),
  conclusión `success`.
- Legacy: no se consulta. Se usa solo la auditoría ya documentada en
  `docs/audit/legacy-clinical.md` (SHA `f5417721d8019a9831126f4d843edfc4de87653d`),
  que clasifica todos los defaults de Legacy como no portables.
- Relacionados: ADR 0008 a 0011 (patrón append-only, conflicto, idempotencia,
  historial contiguo y restauración con procedencia).

## 1. Contexto

La fase 6 necesita un perfil con esquema y versión explícitos, unidades
declaradas, franjas completas sin solapes y cambios como versiones nuevas
(AGENTS.md §5). Legacy mezcla defaults distintos por camino, cae a un slot
«snack» y a literales, migra formatos de forma ambigua y su hash omite campos que
afectan al resultado. Nada de eso se porta.

Este incremento solo crea almacenamiento, edición, histórico y restauración. El
motor (`shared/bolus-engine`) y Bolo no leen el perfil. Cálculo y confirmación
siguen bloqueados.

Riesgos que el diseño debe cerrar:

- confundir «no configurado» con `0` o con un valor heredado;
- sobrescribir en silencio una versión más reciente;
- una versión «restaurada» que en realidad difiere del origen;
- una migración o una fila corrupta que deje la base a medias;
- un perfil incompleto o con franjas ambiguas presentado como válido;
- tener que rehacer la base cuando lleguen franjas horarias o nuevos parámetros.

## 2. Decisión general

1. Nuevo módulo KMP `shared/clinical-profile` (solo `commonMain`, sin Android,
   SQLite, UI, reloj global ni red). Contiene modelo, catálogo de parámetros,
   validación estructural, huella canónica y casos de uso.
2. Adaptador Android `SqliteClinicalProfileRepository` en una **base de datos
   propia**, `clinical-profile.db`, independiente de `meal-drafts.db`.
3. UI bajo Ajustes → Cálculo → «Perfil clínico», como fija la auditoría de UI.
   «Mi perfil» conserva su significado de cuenta/modo enfermedad y no se toca.
4. Todo resultado expone `allowsCalculation = false`, `allowsTreatment = false` y
   el bloqueo estable `profile.not_approved_for_calculation`. Guardar una versión
   no la «confirma» para uso clínico. Qué significa «confirmado» se decidirá en un
   ADR posterior junto con los límites aprobados.
5. Un único flujo de versiones por instalación (un usuario, un perfil activo). No
   hay perfiles alternativos (enfermedad, deporte). Ver sección 13.

### Por qué una base separada

- La migración de comidas (v3) no se toca: cero riesgo sobre datos existentes.
- Cada base tiene su propio número de esquema, migraciones y pruebas.
- Un fallo de perfil no bloquea borradores de comida, y al revés.
- Coste: no hay transacción ni clave foránea entre bases. Hoy no hace falta. Un
  futuro cálculo referenciará el perfil por `version` + `content_sha256`, no por
  clave foránea, y comprobará ambos al leer.

## 3. Campos iniciales del perfil (esquema de contenido 1)

### 3.1 Campos globales de la versión

| Campo | Tipo | Estados | Motivo |
|---|---|---|---|
| `glucose_unit` | `mg/dL` \| `mmol/L` | no configurado \| declarado | unidad de ISF y objetivo. Nunca se convierte |
| `time_zone` | identificador IANA (`Europe/Madrid`) | no configurado \| declarado | las franjas son hora local de esa zona. Sin zona, las franjas son ambiguas |

### 3.2 Parámetros por franja

| Código estable | Significado | Unidad (derivada, no editable) |
|---|---|---|
| `carb_ratio` | gramos de hidratos cubiertos por 1 U | `g/U` |
| `insulin_sensitivity` | descenso de glucosa por 1 U | `<glucose_unit>/U` |
| `glucose_target` | objetivo glucémico puntual | `<glucose_unit>` |

La unidad de cada parámetro sale del catálogo y de `glucose_unit`. No se guarda
por fila para que no puedan divergir.

### 3.3 Excluido a propósito

- DIA, curva, pico, bolo máximo, corrección máxima, IOB máximo, intervalo
  mínimo, paso de redondeo, fibra, Warsaw, ejercicio, autosens, modo enfermedad:
  requieren reglas aprobadas y entran después sin cambiar tablas (sección 5.3).
- Nombre, peso, edad, sexo, correo, fecha de diagnóstico, marca de insulina u
  otros datos personales: no tienen función en Bolus AI hoy (requisito 10).
- Importación desde Legacy o desde Nightscout: no hay prellenado de ningún tipo.

## 4. Modelo de dominio (`shared/clinical-profile`)

```kotlin
sealed interface ProfileValue {            // por franja
    data object NotConfigured : ProfileValue
    data class Entered(val decimal: CanonicalDecimal) : ProfileValue   // "0" es válido
}
sealed interface Declared<out T> {                                   // global
    data object NotConfigured : Declared<Nothing>
    data class Value<T>(val value: T) : Declared<T>
}

enum class GlucoseUnit(val code: String) { MG_DL("mg/dL"), MMOL_L("mmol/L") }
enum class ProfileParameter(val code: String) { CARB_RATIO, INSULIN_SENSITIVITY, GLUCOSE_TARGET }

data class TimeSegment(val startMinute: Int, val endMinute: Int, val value: ProfileValue)
data class ParameterSchedule(val parameter: ProfileParameter, val segments: List<TimeSegment>)

data class ProfileContent(
    val schemaVersion: Int,                 // 1
    val glucoseUnit: Declared<GlucoseUnit>,
    val timeZone: Declared<String>,
    val schedules: List<ParameterSchedule>, // exactamente un schedule por parámetro del catálogo
)

enum class ProfileOrigin(val code: String) {
    MANUAL("manual"), RESTORED("restored"),
    SYSTEM_PROPOSAL_ACCEPTED("system_proposal_accepted"),  // reservado, rechazado en esta fase
}

data class ProfileVersion(
    val version: Long, val content: ProfileContent, val contentSha256: String,
    val origin: ProfileOrigin, val restoredFrom: Long?, val createdAtEpochMs: Long, val writer: String,
)

data class ProfileEditor(val baseVersion: Long, val content: ProfileContent,
                         val origin: ProfileOrigin, val restoredFrom: Long?)
```

Los nombres son orientativos. Lo vinculante son los estados y las invariantes.

### 4.1 Ausencia frente a cero

- `NotConfigured` y `Entered("0")` son estados distintos en dominio, SQLite
  (`NULL` frente a `'0'`), huella y UI («Sin configurar» frente a `0`).
- Un campo de texto vacío es `NotConfigured`. Nunca `0`.
- Un perfil nuevo empieza con todo `NotConfigured`: una franja 00:00–24:00 por
  parámetro con valor no configurado. No hay valores iniciales.
- Nunca se hereda un valor de otra franja, parámetro o versión por ausencia.

### 4.2 Validación estructural y de tipos (sin límites clínicos)

Se valida al crear el editor, al guardar y **en cada lectura**:

- **Decimal canónico**: texto `^(0|[1-9][0-9]{0,5})(\.[0-9]{0,2}[1-9])?$`. No
  negativo, sin signo, sin exponente, sin separador de miles, máximo 6 dígitos
  enteros y 3 decimales, sin ceros finales en la parte decimal. Es un límite de
  **representación** (evita desbordes, `NaN`, notación ambigua y texto libre),
  no un rango clínico. `0` está permitido.
- La UI acepta `,` o `.` como separador decimal y rechaza agrupaciones con
  `profile.edit.ambiguous_decimal`: más de un separador (`1.000,5`), espacios
  internos (`1 000`) y exactamente tres decimales con parte entera distinta de
  cero (`1.000`, `2.125`), que en notación española puede leerse como miles. Con
  parte entera `0` los tres decimales no son ambiguos (`0.125`). Antes de guardar
  muestra el valor canónico que se almacenará. Ceros a la izquierda y ceros
  decimales finales se eliminan al canonizar (`007,50` → `7.5`).
- **Límite de representación**: 6 dígitos enteros y 3 decimales es un límite de
  **almacenamiento y formato**, aprobado por el propietario como revisable. No
  expresa ningún rango clínico admisible ni implica que un valor dentro del
  límite sea clínicamente válido.
- **Franjas**: minutos locales `0..1440`, `start < end`, ordenadas, la primera
  empieza en 0, la última termina en 1440, contiguas (sin huecos ni solapes). Una
  franja no cruza medianoche: 22:00–06:00 se expresa como 22:00–24:00 y
  00:00–06:00. Error: `profile.edit.invalid_segments`.
- Dos franjas adyacentes con el mismo valor se guardan tal cual (no se fusionan
  en silencio). La huella las distingue.
- **Catálogo**: exactamente un schedule por parámetro del esquema de contenido.
  Parámetro desconocido o repetido: `profile.storage.invalid_record` al leer,
  `profile.edit.invalid_parameters` al editar.
- **Zona horaria**: gramática IANA en común y existencia comprobada por el
  adaptador de plataforma (`java.time.ZoneId` en Android) antes de guardar.
  `profile.edit.invalid_time_zone`.
- **Unidad**: ver sección 4.4.
- **Sin cambios**: guardar un contenido cuya huella coincide con la última
  versión se rechaza con `profile.edit.unchanged`. Evita versiones vacías y hace
  inequívoca la idempotencia.

No se valida que `carb_ratio` o `insulin_sensitivity` sean mayores que 0 ni
ningún otro rango. Esas reglas se añadirán como puertas del motor cuando estén
aprobadas. Hasta entonces el perfil no autoriza nada.

### 4.4 Unidad de glucosa: ningún valor se reinterpreta

Decisión del propietario (2026-09-27). Los valores de `insulin_sensitivity` y
`glucose_target` dependen de `glucose_unit` y nunca pueden pasar a leerse con otra
unidad por cambiar la configuración.

- **La unidad viaja con los valores.** Cada versión guarda su propia
  `glucose_unit` junto a sus valores, en la misma fila de versión y bajo la misma
  huella. Una versión representa de forma inequívoca la unidad con que se
  introdujeron. No existe una unidad «global» o «actual» que se aplique a
  versiones antiguas.
- **Sin unidad no hay valores dependientes.** Si `glucose_unit` es «no
  configurada», todas las franjas de ISF y objetivo deben estar sin configurar
  (`profile.edit.unit_required`). Se comprueba en el dominio, al guardar y al
  leer.
- **Cambiar de unidad crea una versión nueva sin valores dependientes.** Al
  cambiar la unidad en el editor se vacían ISF y objetivo y sus campos quedan
  bloqueados hasta guardar. La versión que cambia la unidad no puede contener
  valores dependientes (`profile.edit.unit_change_with_values`). Los valores en
  la unidad nueva se introducen en la versión siguiente, que ya parte de esa
  unidad. Volver a la unidad inicial en el mismo editor no recupera los valores
  borrados.
- **Unidad de partida.** La regla compara con la unidad de la versión de la que
  parte el contenido: la versión restaurada si el editor nació de una
  restauración, o la última versión en otro caso. Solo aplica si esa unidad
  estaba declarada: pasar de «no configurada» a una unidad no reinterpreta nada,
  porque la versión anterior no podía tener valores dependientes.
- **Restaurar conserva la unidad original.** Una restauración exacta copia la
  unidad y los valores juntos. Si difiere de la unidad de la última versión, la
  confirmación lo advierte: los valores se restauran con su unidad original y no
  se convierten.
- **Sin conversión automática.** Nada convierte mg/dL ↔ mmol/L en esta fase.
- **Versiones anteriores intactas.** Cambiar de unidad nunca modifica versiones
  existentes (triggers y huella).
- **Doble barrera.** El editor aplica la regla para guiar la UI. La política de
  escritura compartida la vuelve a aplicar dentro de la transacción SQLite, con
  las versiones leídas de la base, y la lectura del historial la revalida en cada
  versión. Una fila manipulada que la incumpla hace fallar el historial con
  `profile.storage.invalid_record`.

### 4.5 Huella canónica

`content_sha256` = SHA-256 de una serialización canónica UTF-8 de
`ProfileContent` (esquema, unidad, zona, parámetros en orden de código, franjas en
orden, `~` para no configurado y el decimal canónico). No incluye metadatos
(versión, fecha, origen, escritor), de modo que el mismo contenido da la misma
huella.

- Se calcula en común con una implementación SHA-256 pura en Kotlin, probada con
  los vectores NIST (FIPS 180-4). No añade dependencias.
- Se guarda al escribir y se recalcula y compara en cada lectura. Discrepancia:
  `profile.storage.invalid_record`.
- Sirve para detectar corrupción, verificar restauraciones exactas y, en el
  futuro, identificar el perfil usado por un cálculo (AGENTS.md §5).

## 5. Esquema SQLite (`clinical-profile.db`, versión de base 1)

```sql
CREATE TABLE profile_versions (
    version          INTEGER PRIMARY KEY CHECK(version > 0),
    schema_version   INTEGER NOT NULL CHECK(schema_version > 0),
    glucose_unit     TEXT CHECK(glucose_unit IS NULL OR length(glucose_unit) > 0),
    time_zone        TEXT CHECK(time_zone IS NULL OR length(time_zone) BETWEEN 1 AND 64),
    content_sha256   TEXT NOT NULL CHECK(length(content_sha256) = 64),
    origin           TEXT NOT NULL CHECK(length(origin) > 0),
    restored_from    INTEGER REFERENCES profile_versions(version)
                     CHECK(restored_from IS NULL OR (restored_from > 0 AND restored_from < version - 1)),
    created_at_ms    INTEGER NOT NULL,
    writer           TEXT NOT NULL CHECK(length(writer) BETWEEN 1 AND 128),
    CHECK(origin <> 'restored' OR restored_from IS NOT NULL)
);

CREATE TABLE profile_segments (
    version       INTEGER NOT NULL REFERENCES profile_versions(version),
    parameter     TEXT NOT NULL CHECK(length(parameter) > 0),
    start_minute  INTEGER NOT NULL CHECK(start_minute BETWEEN 0 AND 1439),
    end_minute    INTEGER NOT NULL CHECK(end_minute BETWEEN 1 AND 1440 AND end_minute > start_minute),
    value         TEXT CHECK(value IS NULL OR (length(value) BETWEEN 1 AND 10
                                               AND value NOT GLOB '*[^0-9.]*')),
    PRIMARY KEY(version, parameter, start_minute)
);

CREATE TRIGGER profile_versions_immutable_update BEFORE UPDATE ON profile_versions
    BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END;
CREATE TRIGGER profile_versions_immutable_delete BEFORE DELETE ON profile_versions
    BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END;
CREATE TRIGGER profile_segments_immutable_update BEFORE UPDATE ON profile_segments
    BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END;
CREATE TRIGGER profile_segments_immutable_delete BEFORE DELETE ON profile_segments
    BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END;
CREATE TRIGGER profile_segments_latest_only BEFORE INSERT ON profile_segments
    WHEN NEW.version <> (SELECT MAX(version) FROM profile_versions)
    BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END;
```

El último trigger impide añadir franjas a una versión que ya no es la última. La
huella detecta cualquier otra alteración de contenido.

### 5.1 Reparto de responsabilidades

- **SQLite** impone lo estructural estable: claves, `NOT NULL`, rangos de
  minutos, forma básica del decimal, `restored_from` anterior y existente,
  inmutabilidad por triggers.
- **Dominio compartido** impone lo que evoluciona con el catálogo: códigos de
  parámetro, origen y unidad, cobertura completa sin solapes, gramática exacta
  del decimal, huella y reglas de origen. Se comprueba al escribir y al leer.
- Se evita a propósito un `CHECK(parameter IN (...))` o `CHECK(origin IN (...))`:
  SQLite no permite modificar un `CHECK` sin reconstruir la tabla, y eso es
  justo lo que el requisito 9 pide evitar.

### 5.2 Franjas horarias

Cada fila de `profile_segments` es una franja `[start_minute, end_minute)` en
hora local de `time_zone`. Un valor para todo el día es una sola fila 0–1440.
Varias franjas son varias filas. El modelo, la validación, la huella y la
persistencia soportan franjas desde el primer día y se prueban con datos
sintéticos de varias franjas.

Resolver un instante a una franja (cambio de hora, horas repetidas o
inexistentes) no se hace aquí. Es responsabilidad del motor y requiere decisión
aprobada (sección 12, P4).

### 5.3 Evolución sin rehacer la base

- **Nuevo parámetro** (DIA, límites, etc.): nuevo código en el catálogo y
  `schema_version` de contenido 2. Las filas antiguas siguen siendo esquema 1 y
  se leen con el catálogo 1. No hay `ALTER TABLE`.
- **Nuevo campo global**: `ALTER TABLE ... ADD COLUMN` anulable, con `NULL`
  = «no existía en esa versión», distinto de «no configurado» porque lo decide
  `schema_version`.
- **Nueva unidad** (por ejemplo raciones): nuevo código de catálogo y esquema de
  contenido, sin tocar tablas.
- **Propuestas del sistema**: tabla nueva `profile_proposals` y columna anulable
  `proposal_id`. Una propuesta nunca es una versión. Solo una aceptación
  explícita del usuario crearía una versión con origen
  `system_proposal_accepted`.

## 6. Procedencia y auditoría

Cada versión conserva:

| Dato | Contenido |
|---|---|
| `version` | número consecutivo desde 1, orden autoritativo |
| `created_at_ms` | instante UTC del guardado según el reloj inyectado. Informativo: si el reloj del dispositivo está mal, la fecha lo refleja, pero el orden lo da `version` |
| `origin` | `manual` \| `restored` \| `system_proposal_accepted` (reservado) |
| `restored_from` | versión de origen cuando el contenido parte de una restauración |
| `writer` | cliente y build que escribió, por ejemplo `android/0.1.0-dev(1)`. Sin identificadores de dispositivo ni datos personales |
| `content_sha256` | huella del contenido |

Reglas de origen:

- `manual`: introducido o editado por el usuario. `restored_from` es `NULL`, o
  indica la versión de partida si el usuario restauró y **después modificó**
  algún campo.
- `restored`: copia exacta de una versión anterior. Exige `restored_from` y que
  `content_sha256` sea igual al de esa versión. El repositorio lo comprueba en la
  transacción y la lectura lo revalida. Si no coincide:
  `profile.storage.invalid_record`.
- `system_proposal_accepted`: existe en el enum para no cambiar el formato, pero
  el caso de uso y el repositorio lo rechazan con
  `profile.origin.not_enabled`. No hay aprendizaje ni modificación automática.

## 7. Versionado, guardado e idempotencia

- Append-only: una versión y sus franjas se insertan una sola vez en una
  transacción. Triggers impiden `UPDATE` y `DELETE`.
- `save(editor, createdAtEpochMs, writer)` en una transacción exclusiva:
  1. relee y valida la cadena completa de versiones (contigüidad, huellas,
     procedencia y transiciones de unidad). Si no se puede demostrar, falla con
     `profile.storage.invalid_record` y no añade nada. Valida el contenido y el
     origen (fuera y dentro de la transacción);
  2. si existe la versión `baseVersion + 1` con la misma huella, origen y
     `restored_from`, devuelve `Saved` con esa versión (**reintento idéntico**,
     aunque después se hayan creado otras). No inserta nada;
  3. si la última versión no es `baseVersion`: `profile.storage.revision_conflict`;
  4. si la huella coincide con la última: `profile.edit.unchanged`;
  5. inserta versión y franjas y devuelve `Saved` solo tras el commit.
- Cualquier excepción revierte todo. La UI muestra «guardado» solo con `Saved`.
- Fecha y escritor no participan en la comparación de reintento.

## 8. Restauración

Mismo principio que el ADR 0011:

- `restore(history, k)` es puro. Exige historial cargado y `k < latest`. Crea un
  editor con `baseVersion = latest`, contenido exacto de `k` (incluidos
  `NotConfigured` y `0`), `origin = restored`, `restoredFrom = k`. No lee ni
  escribe almacenamiento.
- Restaurar la última versión, una inexistente o una cuya huella ya es la de la
  última: rechazado (`profile.storage.invalid_record` o `profile.edit.unchanged`).
- Si el usuario modifica algo antes de guardar, el origen pasa a `manual` y
  `restored_from` se conserva como punto de partida. Si deshace el cambio y el
  contenido vuelve a ser el de `k`, recupera `restored`.
- Nunca se modifica la versión `k` ni ninguna otra.
- El origen no lo elige quien llama: el editor lo deriva comparando el contenido
  con el de partida. La política de escritura compara huellas con la versión `k`
  leída de la base: `restored` exige huella igual y `manual` con
  `restored_from` exige huella distinta.
- La UI pide confirmación explícita en pantalla con: versión que se carga, número
  que tendrá al guardar, que nada guardado cambia, y aviso de descarte si hay un
  editor con cambios pendientes. Confirmación y editor sobreviven a rotación y
  recreación.

## 9. Concurrencia

- El editor guarda `baseVersion` al abrirse (0 si no hay perfil). Una
  restauración toma como base la última versión en el momento de prepararla, no
  la restaurada.
- Si otra ventana, proceso o conexión creó una versión posterior, guardar falla
  con `profile.storage.revision_conflict`. El editor conserva los campos, la UI
  lo explica y ofrece ver la versión nueva. No hay fusión automática ni «última
  escritura gana».
- La comprobación de base y la inserción ocurren en la misma transacción
  exclusiva de SQLite, así que dos escritores no pueden crear la misma versión.
- Una lectura en vuelo sustituida por otra no puede pintar su resultado tardío
  (mismo patrón que el ADR 0010).

## 10. Migraciones y datos existentes

- `clinical-profile.db` nace en la versión de base 1. `onCreate` crea tablas y
  triggers en la transacción del helper. Si encuentra tablas ajenas en un
  fichero con `user_version = 0`, falla con `profile.storage.unsupported_schema`
  sin tocarlas.
- `onUpgrade` y `onDowngrade` fallan con `profile.storage.unsupported_schema`: no
  hay versiones anteriores. Toda versión futura añadirá su `onUpgrade` con
  validación completa de filas (decodificar, cobertura y huella) dentro de la
  transacción de actualización, igual que `meal-drafts.db` v1/v2 → v3.
- `onConfigure`: `PRAGMA synchronous=FULL` y claves foráneas activas, como
  comidas. Manejador de corrupción que falla cerrado con `profile.storage.corrupt`.
- `meal-drafts.db` no cambia (sigue en v3). Instalar esta versión sobre una app
  con comidas guardadas no migra ni lee esa base desde el código de perfil.
- No hay importación ni prellenado. Tras actualizar, el perfil aparece como «Sin
  perfil guardado».
- Un fallo de creación o de migración revierte la transacción: la base queda
  como estaba (vacía o en su versión anterior) y la pantalla muestra el motivo.
  Nunca se presenta como «sin perfil».

## 11. Backup y restauración de copias

Estado actual, que se mantiene: `android:allowBackup="false"`,
`fullBackupContent="false"` y `data_extraction_rules.xml` excluye todos los
dominios en copia en la nube y transferencia entre dispositivos. El perfil es
dato de salud y queda igual de excluido sin cambiar reglas. `verify.ps1` pasará a
comprobar que esas exclusiones siguen presentes en el manifiesto combinado.

En esta fase no existe exportación/importación de producto. La integración con
backup/restore se limita a garantías de fichero, verificadas con pruebas:

- una copia del fichero tomada con la base cerrada se reabre y valida todas las
  versiones (cobertura, catálogo, huella, continuidad 1..N);
- una copia manipulada (fila alterada, huella distinta, hueco en versiones,
  `schema_version` desconocido) falla cerrado con motivo estable, sin reparar ni
  ocultar versiones;
- una base de una versión futura (`user_version` mayor) falla con
  `profile.storage.unsupported_schema`;
- `clinical-profile.db` y `meal-drafts.db` se restauran de forma independiente:
  no hay referencias cruzadas;
- toda futura migración de `clinical-profile.db` añadirá la prueba «copia previa
  a la migración reabre con la versión anterior», como en comidas.

Exportación cifrada, restauración desde la app y política de retención quedan
pendientes (P6).

## 12. Decisiones pendientes del propietario

Las marco con mi recomendación. Ninguna implica valor clínico.

| Id | Pregunta | Recomendación en este ADR |
|---|---|---|
| P1 | ¿Objetivo puntual o rango (bajo/alto)? | puntual (`glucose_target`). El rango se añade después como dos parámetros sin tocar tablas |
| P2 | ¿Ratio en g/U o también U por ración? | solo `g/U`, igual que los borradores de comida. Raciones como unidad futura |
| P3 | ¿Admitir `mg/dL` y `mmol/L`? | ambas, declaradas y sin conversión |
| P4 | ¿Cómo resolver franjas en cambios de hora? | fuera de esta fase. Lo decidirá el ADR del motor |
| P5 | ¿Editor de franjas en la UI ya? | no. UI de esta fase edita una franja de día completo por parámetro. Las versiones con varias franjas se muestran completas en solo lectura y su edición queda bloqueada con `profile.edit.segments_ui_unavailable` hasta el siguiente incremento. Modelo, SQLite y pruebas ya las soportan |
| P6 | ¿Exportación/importación de copias? | fuera de esta fase. Auto Backup sigue desactivado |
| P7 | ¿Rechazar negativos como tipo? | sí: las tres magnitudes son no negativas por definición. `0` permitido |
| P8 | Límite de representación (6 enteros, 3 decimales) | aceptar como límite técnico revisable, no clínico |

## 13. Alternativas consideradas

- **Tabla ancha con una columna por parámetro**: sencilla, pero cada parámetro
  nuevo es `ALTER TABLE` y las franjas obligan a rehacerla. Rechazada.
- **JSON completo en una columna**: flexible, pero SQLite no valida estructura y
  la corrupción solo se detecta al parsear. Rechazada frente a filas tipadas con
  huella.
- **Misma base que comidas (v4)**: acopla migraciones y arriesga datos existentes
  sin beneficio actual. Rechazada.
- **Slots de comida (desayuno/comida/cena/snack) como Legacy**: dependen de
  clasificar la comida y Legacy tiene fallback a snack. Rechazado. Las franjas
  por hora local son explícitas y completas.
- **`restored_from` sin distinguir origen**, como comidas: el perfil exige saber
  si el contenido restaurado se guardó intacto. Por eso se separa `restored` de
  `manual` y se verifica con la huella.
- **Clave de idempotencia UUID por operación**: más general, pero el rechazo de
  contenido sin cambios más la comprobación en `baseVersion + 1` hace el
  reintento inequívoco sin generador adicional.
- **Varios perfiles con identidad propia**: sin necesidad hoy. Añadir un
  `profile_id` exigiría migración. Condición de revisión explícita.

## 14. Consecuencias

- Local, sin red, sin permisos nuevos, sin tocar el motor ni Bolo.
- Una base nueva con versión 1. Rollback: revertir el commit. La base
  `clinical-profile.db` queda huérfana y sin efectos. `meal-drafts.db` intacta.
- iOS implementará el mismo puerto con SQLite nativo, mismas tablas, triggers,
  catálogo y huella del módulo común. Sigue sin verificación de plataforma.
- Coste: SHA-256 propio en común (con vectores NIST) y validación completa en
  cada lectura. El perfil es pequeño, el coste es despreciable.

## 15. Pruebas previstas

### Comunes (`shared/clinical-profile`, KMP/JVM)

- ausencia frente a `0` en cada parámetro, unidad y zona, incluida la huella;
- perfil nuevo: todo no configurado, una franja 0–1440 por parámetro;
- decimal: canónico, `,` y `.`, rechazo de agrupaciones, negativos, exponentes,
  espacios, `NaN`, texto, más de 6 enteros o 3 decimales, ceros finales;
- franjas: completas; hueco, solape, inicio distinto de 0, fin distinto de 1440,
  vacías, desordenadas, cruce de medianoche rechazado; adyacentes iguales se
  conservan;
- catálogo: falta un parámetro, duplicado, desconocido;
- unidad (sección 4.4): cambio de unidad vacía y bloquea ISF y objetivo sin
  convertir; volver a la unidad inicial no recupera valores; versión que cambia
  de unidad con valores dependientes rechazada (también si se construye a mano,
  sin pasar por el editor); valores dependientes sin unidad rechazados; valores
  en la unidad nueva aceptados en la versión siguiente; restauración de una
  versión en otra unidad conserva su unidad; restaurar y cambiar de unidad con
  valores rechazado; historial con una transición manipulada rechazado;
- huella: vectores NIST de SHA-256, estabilidad, independencia de metadatos,
  sensibilidad a cada campo;
- restauración: contenido exacto, origen `restored`, paso a `manual` al editar,
  vuelta a `restored` al deshacer, rechazo de última/inexistente/idéntica;
- guardado: sin cambios, conflicto, reintento idéntico (incluido tras una
  versión posterior), origen reservado rechazado;
- historial: orden, continuidad 1..N, hueco o duplicado rechazados, fallo nunca
  equivale a ausencia;
- todos los estados: `allowsCalculation = false`, `allowsTreatment = false`.

### SQLite en dispositivo (`SqliteClinicalProfileRepositoryDeviceTest`)

- crear, guardar, reiniciar el repositorio y releer idéntico;
- reintento idéntico sin fila nueva; conflicto sin fila nueva;
- triggers: `UPDATE`/`DELETE` abortan y no cambian nada;
- `restored` con huella distinta rechazado; `restored_from` inexistente o no
  anterior rechazado por `CHECK`/FK;
- fallo de escritura a mitad (franja inválida inyectada) revierte versión y
  franjas, y un reintento correcto funciona;
- lectura de base manipulada: huella, hueco, parámetro desconocido,
  `schema_version` desconocido, decimal no canónico → fallo cerrado;
- esquema desconocido, `user_version` futura y degradación → `unsupported_schema`;
- fichero con tablas ajenas no se modifica;
- copia cerrada del fichero reabre y valida; `meal-drafts.db` intacta y abre en
  v3 con sus revisiones y selección tras crear el perfil;
- varias franjas sintéticas persisten y releen exactas;
- cambio de unidad: la versión anterior conserva unidad, valores y huella
  byte a byte; escritura con valores dependientes tras cambiar de unidad
  rechazada sin filas nuevas; filas manipuladas (unidad cambiada con valores, o
  valores sin unidad) con huella recalculada fallan al leer.

### UI en dispositivo (`ClinicalProfileDeviceTest`)

- sin perfil: «Sin perfil guardado» y todo «Sin configurar»;
- escribir `0` frente a dejar vacío, se ve distinto antes y después de guardar;
- valor canónico mostrado antes de guardar; `1.000` rechazado;
- cambio de unidad con aviso, campos dependientes vaciados y bloqueados, guardado
  como versión nueva y valores en la unidad nueva en la versión siguiente. La
  versión anterior se sigue mostrando con su unidad original;
- guardar, recrear actividad, relanzar: versión persistida;
- conflicto con versión creada por otra conexión: error visible, campos
  conservados;
- historial en solo lectura con fecha, origen, procedencia y huella corta;
- restaurar: confirmación, cancelar, confirmar, recrear, guardar con procedencia,
  aviso de descarte con editor pendiente;
- versión con varias franjas: se muestra completa y no se puede editar;
- Bolo sigue bloqueado y no muestra ni lee el perfil;
- layouts 840×900, 900×840 y 411×914 dp con texto 1,0× y 1,8×, tema claro y
  oscuro.

### Arquitectura y verificación

- `shared/bolus-engine` no depende de `shared/clinical-profile` (comprobación en
  `verify.ps1`);
- manifiesto combinado conserva `allowBackup=false` y exclusiones de base de
  datos;
- nuevas clases añadidas a la lista `-e class` de `scripts/verify.ps1`;
- `scripts/verify.ps1 -DeviceTests` en el Pixel con un único dispositivo
  autorizado, repositorios sintéticos aislados y `KEYCODE_WAKEUP` cada 10 s;
- evidencia en `docs/validation/local-clinical-profile-<fecha>.md`.

## 16. Notas de implementación

Precisiones de la entrega, sin cambiar decisiones:

- Código adicional `profile.edit.value_not_representable` para valores fuera del
  límite de representación, distinto de `invalid_value` y `ambiguous_decimal`.
- La política de escritura (`ProfileWritePolicy`) y las reglas de transición
  (`ProfileRules`) viven en `shared/clinical-profile`. El adaptador SQLite solo
  lee las versiones implicadas en su transacción, aplica la decisión y relee lo
  insertado antes del commit. iOS reutilizará la misma política.
- El adaptador también valida la cadena completa al leer, no solo el caso de uso,
  y la vuelve a validar dentro de la transacción de guardado antes de añadir
  (revisión de la PR #28). El editor solo permite guardar con un historial
  cargado o ausente demostrado, nunca con una lectura pendiente o fallida.
- El estado del editor se guarda en `onSaveInstanceState` con la codificación
  canónica y se vuelve a validar al recrear.
- Evidencia: [validación 2026-09-27](../validation/local-clinical-profile-2026-09-27.md).

## 17. Condiciones para revisar este ADR

Aprobación de límites o del significado de «confirmado», uso del perfil por el
motor o por IOB, perfiles alternativos, edición de franjas en la UI, propuestas o
aprendizaje del sistema, exportación/importación de copias, sincronización, o un
segundo esquema de contenido.
