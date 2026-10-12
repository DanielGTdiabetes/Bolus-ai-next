# ADR 0027: acción de la insulina en el perfil versionado

- Estado: aceptado para desarrollo y pruebas, según la autorización del
  propietario de 2026-10-12 (AGENTS.md §3). No aprueba ningún valor ni uso
  terapéutico
- Fecha: 2026-10-12
- Fase y criterio de aceptación: fase 6, puntos 1 a 4 (perfil versionado con DIA
  y curva explícitos y sin defaults, para el IOB del motor común), en modo de
  prueba
- Responsables de decisión: propietario. Las decisiones técnicas reversibles las
  toma el agente (EXECUTION_PLAN §3.3)
- Evidencia clínica requerida: pendiente. Los valores los introduce y confirma
  el usuario. Las curvas que los usan son candidatas (ADR 0026, LC-005)

## Contexto

El IOB candidato del [ADR 0026](0026-candidate-iob-from-dose-history.md) necesita
DIA, curva y pico de una versión confirmada del perfil, y el perfil no los
tenía. El propietario pidió el 2026-10-12 añadirlos al perfil versionado sin
defaults, dejando para otra entrega la decisión sobre el historial de dosis.

En Legacy (`main` remoto: `bcac891d85e6fb3e84398c118061e6aeae3f73f1`, el mismo
SHA del ADR 0026) son campos únicos de `backend/app/models/settings.py::IOBConfig`:
`dia_hours: float` (defecto 4, `> 0`), `curve` con los valores `walsh`, `bilinear`,
`fiasp`, `novorapid` y `linear` (defecto `walsh`) y `peak_minutes: int` (defecto 75,
entre 10 y 300). No son franjas horarias. Next no copia ningún defecto ni rango.

## Decisión

### 1. Esquema de contenido 3

`ProfileCatalog.CURRENT_SCHEMA = 3`. El esquema 3 conserva los parámetros con
franjas y los tres límites del esquema 2 y añade:

- dos campos únicos numéricos en el mismo mecanismo que los límites
  (`ProfileLimit`, ahora agrupados por `ProfileFieldGroup`): `insulin_dia` en h y
  `insulin_peak` en min. El pico admite solo minutos enteros, como el `int` de
  Legacy. Es una regla de representación, no un rango clínico: una fracción se
  rechaza con `profile.edit.not_whole_number` y nunca se redondea;
- la curva como declaración (`ProfileContent.insulinCurve`,
  `Setting<InsulinCurveCode>`) con los cinco nombres de Legacy. Sin configurar es
  explícito.

Reglas de esquema, las mismas del ADR 0025 generalizadas:

- Un perfil nuevo nace en esquema 3 con todo sin configurar.
- Una versión de esquema 1 o 2 no tiene acción de la insulina: `insulinCurve` es
  `null` y `limit(INSULIN_DIA)`/`limit(INSULIN_PEAK)` devuelven `null`, que
  significa desconocido. Nunca se rellena ni se migra.
- Introducir un valor pasa el contenido al esquema más pequeño que lo tiene,
  conservando sus valores: un límite sobre esquema 1 sigue llevando a esquema 2,
  y DIA, pico o curva llevan a esquema 3.
- El editor mantiene el esquema más pequeño, nunca menor que el de partida, que
  conserva todos los valores configurados. Si el usuario borra lo que exigía el
  esquema 3, vuelve al de partida y no se crea una versión que solo cambie de
  esquema.
- Una edición manual sigue sin poder bajar de esquema
  (`profile.edit.schema_downgrade`). Una restauración exacta conserva el esquema
  de la versión restaurada.
- La huella canónica añade en esquema 3 dos líneas `limit=insulin_…` y una línea
  `insulin_curve=<código|~>` tras los límites. El texto y la huella de las
  versiones de esquema 1 y 2 no cambian.
- La acción de la insulina no forma parte de la completitud del ADR 0014: sin
  ella se puede confirmar la versión. Al confirmar se confirma también, porque la
  huella la incluye.

El perfil no depende del motor. `InsulinCurveCode` repite los códigos y el uso
del pico de `org.bolusai.engine.iob.InsulinCurve`, y `verify.ps1` exige que
ambas listas coincidan en orden, código y uso del pico.

### 2. `clinical-profile.db` v4

DIA y pico se guardan en `profile_limits` (v3), cuyo `CHECK` decimal ya sirve.
La curva no es decimal y va en una tabla nueva
`profile_insulin_curve(version INTEGER PRIMARY KEY, curve TEXT)` con `CHECK` de
letras minúsculas y tres triggers: no admite `UPDATE` ni `DELETE`, y solo admite
inserciones para la última versión. Las definiciones v1 a v3 no cambian. El texto
queda congelado en `ClinicalProfileSchema.V4_ADDITIONS`, con su resumen SHA-256
en `ClinicalProfileSchemaTextTest`.

La migración v1, v2 o v3 → v4 sigue el procedimiento del ADR 0025: verifica las
definiciones de origen, `foreign_key_check` y `quick_check`, crea lo que falte,
verifica v4 y valida las versiones con el lector v4 antes del commit. No
reescribe ninguna fila ni crea confirmaciones, curvas o valores.

Al leer, una versión de esquema 1 o 2 con fila de curva, una de esquema 3 sin
ella o sin alguno de sus campos, un código de curva desconocido o un pico
fraccionario son corrupción (`profile.storage.invalid_record`). Un valor alterado
no coincide con la huella y falla igual.

### 3. Interfaz

Ajustes → Cálculo → editar muestra la sección «Acción de la insulina» tras los
límites, con el aviso permanente de que sirve para el IOB calculado de prueba,
que no tiene valores por defecto, que lo vacío bloquea el IOB calculado y que la
validación está pendiente y no se usa para tratamiento. La curva se elige entre
«Sin configurar» y las cinco curvas. Si la versión de partida es anterior, se
avisa de que su acción es desconocida. El perfil actual, el historial y la
revisión muestran la curva, la DIA y el pico, «Sin configurar» o «desconocido
(versión sin acción de la insulina)».

### 4. Fuera de esta entrega

- **El IOB calculado en Bolo.** Sigue sin productor de historial (ADR 0026, §5)
  y `verify.ps1` sigue impidiendo que Android use `org.bolusai.engine.iob`. La
  traducción de la versión del perfil a `InsulinAction` va con la entrega del
  historial.
- **El rango clínico del pico y de la DIA.** Pendiente de aprobación.

## Alternativas consideradas

- **Guardar la curva como un código numérico en `profile_limits`.** Descartada:
  convierte una elección en un número sin significado y debilita el `CHECK`.
- **Un enum separado para DIA y pico con su propia tabla y codec.** Descartada:
  duplicaría almacenamiento, codec, editor y pruebas que ya cubren los campos
  únicos del ADR 0025. El grupo `INSULIN_ACTION` los separa en la interfaz.
- **Exigir la acción para la completitud.** Descartada por AGENTS.md §3: bloquea
  solo el IOB calculado, que aún no se usa.
- **Aceptar los defaults o el rango 10-300 de Legacy.** Descartada: valores y
  rangos clínicos sin aprobar.

## Consecuencias

- El usuario declara la acción una vez y cada versión posterior la conserva.
- Una app anterior no abre un archivo v4 (`onDowngrade` falla cerrado). Rollback:
  restaurar la copia previa del archivo v3, v2 o v1.
- iOS usará el mismo `ProfileContent`, el mismo codec y el mismo motor KMP.

## Seguridad y pruebas

- `ProfileInsulinActionTest` (10): esquemas 1 y 2 intactos, perfil nuevo sin
  configurar, texto canónico estricto del esquema 3, huella por campo y ausencia
  frente a cero, pico entero, subida al esquema mínimo, vuelta del editor,
  conservación y restauración, confirmación sin acción y construcción inválida.
- `ProfileLimitsTest` y `ProfileContentTest`: solo cambian las expectativas que
  dependen del esquema actual (perfil nuevo en esquema 3 y primer esquema
  desconocido 4). El resto de reglas del ADR 0025 sigue igual.
- `ClinicalProfileSchemaTextTest`: texto v4 congelado sin tocar v1 a v3.
- Pruebas instrumentadas, pendientes de ejecutar en el Pixel:
  `ClinicalProfileInsulinActionDeviceTest` (migraciones v1, v2 y v3 → v4 con
  confirmación, persistencia y reinicio, triggers, filas manipuladas) y
  `ClinicalProfileDeviceTest.insulinActionIsDeclaredOnceWithoutDefaultsAndOlderVersionsStayUnknown`.
  Se actualizaron las existentes que esperaban v3.

## Condiciones para revisar este ADR

- El propietario aprueba rangos o valores para DIA o pico, o descarta alguna
  curva.
- La entrega del historial de dosis necesita otro dato de la acción.
- Cambios en `IOBConfig` o `curves.py` en un SHA posterior de Legacy.
