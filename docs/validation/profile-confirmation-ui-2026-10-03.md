# Confirmación de datos del perfil: revisión, confirmación y retirada en pantalla — 2026-10-03

Base Next: `7b2b1d298aa439ee6a65c9ded1ff74b4afbaeed9` (PR #32 integrada, parte 1
del ADR 0014). CI del SHA exacto comprobado antes de editar: Verify en verde
([37122843929](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37122843929),
evento `push`). Implementa la **segunda parte** del
[ADR 0014](../adr/0014-clinical-profile-confirmation-eligibility.md): estados,
revisión de solo lectura, confirmación, retirada, historial de confirmaciones,
recreación con operación pendiente y el texto de Bolo. Rama
`claude/profile-confirmation-ui`. Legacy no se consulta ni se modifica.

## Qué existe tras esta entrega

- `ClinicalProfileModel` lee el estado con `ClinicalProfiles.readState()` (una
  sola lectura de versiones y eventos) y deriva de él el historial del editor.
  No duplica reglas: completitud, `canConfirm`, `canRevoke`, peticiones,
  política y resolución de pendientes son las del dominio compartido
  (`ProfileGateState`, `ConfirmationOutcome`, `PendingConfirmations` vía
  `resolvePending`). La UI no abre SQLite.
- Estados de la sección 9.2 en la vista de la última versión: E5 a E11, E8 con
  una o varias confirmaciones superadas, lectura pendiente (E1) y fallida (E2 a
  E4) con su código. Las faltas se listan todas. «Datos confirmados» va siempre
  en el mismo texto que «Cálculo todavía bloqueado».
- «Revisar y confirmar los datos de la versión N…» abre una revisión de solo
  lectura construida desde una lectura nueva de la base: versión, fecha, huella
  corta, unidad, zona y todas las franjas de cada parámetro. Un `0` explícito se
  muestra como «0 · introducido explícitamente», resaltado. La revisión queda
  ligada a versión, huella y último evento de esa lectura. No hay campos, guardar
  ni «Guardar y confirmar».
- La acción no existe dentro del editor. En el historial (accesible desde el
  editor) aparece deshabilitada, con el texto de la sección 9, mientras el
  editor tenga cambios, textos no válidos pendientes, un panel de franjas
  abierto o un cambio de unidad pendiente. Un editor sin cambios se cierra al
  abrir la revisión.
- Confirmar y retirar son acciones separadas. Retirar pide confirmación en línea
  con el texto del ADR. Cada intención genera su `operation_id` una sola vez con
  el puerto `OperationIds` (`AndroidOperationIds` en producción). La petición
  completa se conserva en el modelo y en el estado guardado, y se reutiliza en
  cada reintento. Mientras una operación está en vuelo se bloquean guardar,
  editar, restaurar, abrir el historial y la otra operación, y se muestra el
  estado anterior con «Registrando confirmación…» o «Registrando retirada…».
- Resultados: éxito solo tras el commit. Un `replayed` nunca se presenta como
  reconfirmación: «La confirmación de la versión N ya se había registrado.» o,
  si después se retiró, «Esta confirmación ya se había registrado y después se
  retiró. No se ha vuelto a confirmar.». Rechazos de política (`stale_version`,
  `state_changed`, `already_active`, `incomplete`, `not_active`,
  `operation_mismatch`) cierran la decisión y releen la base. Un fallo de
  almacenamiento conserva la decisión y la misma operación. Todo error muestra
  su código.
- Recreación (sección 9.3) con `resolvePending`: lectura no demostrada conserva
  la operación sin afirmar éxito ni fracaso y ofrece reintentar la lectura;
  operación encontrada se muestra registrada con el estado vigente; solo una
  operación no encontrada se cierra por `stale_version` o `state_changed`; si
  nada cambió, la revisión sigue abierta con la misma identidad. Una revisión o
  pregunta sin operación pendiente se compara igualmente con la lectura nueva.
- E11 ofrece «Retirar la confirmación…» y, tras retirarla, no ofrece confirmar
  hasta que la zona se reconozca. Reconfirmar exige revisión y operación nuevas.
- Historial: por versión, «Confirmada el…», «Confirmación retirada el…» y
  «Superada por la versión N», en orden de `seq`. Restaurar una versión con
  confirmación avisa de que la versión nueva no la hereda. Guardar muestra
  «Guardado · versión N · datos sin confirmar.».
- Bolo: `profile_absent` pasa a «Perfil · no se usa para calcular». Es el único
  texto que cambia en Bolo. El mismo recurso se usa en Menú → Perfil, donde el
  texto nuevo también es exacto. Bolo, `ReadOverview` y el motor siguen sin leer
  el perfil, y `verify.ps1` comprueba además que `ScreenRenderer` no referencia
  perfil ni confirmaciones.
- Costuras de prueba de proceso en `MainActivity`: `profileTimeZoneRules` y
  `profileOperationIds`, junto a las existentes. La fábrica de repositorio
  devuelve `ClosableProfileRepository` (el puerto más `Closeable`) para poder
  envolver la base sintética en las pruebas.

Sin cambios de esquema SQLite, serialización, huellas, reglas aprobadas ni
dominio compartido. `allowsCalculation` y `allowsTreatment` siguen en `false`.
`input.profile.*` no se implementa ni se conecta.

## Garantías y evidencia

Clase nueva `ClinicalProfileConfirmationUiDeviceTest` (añadida a la lista
`-e class` de `verify.ps1`) con el envoltorio de prueba
`ControlledProfileRepository`, que falla lecturas o escrituras con un código y
retiene una escritura antes de empezar o después del commit. Todas las reglas
siguen ejecutándose en el adaptador SQLite real sobre ficheros sintéticos.

| Área (ADR §10) | Evidencia |
|---|---|
| E5 a E11 con sus textos, faltas completas y «Cálculo todavía bloqueado» | `everyStateHasItsTextAndConfirmedDataAlwaysComesWithTheCalculationBlock`, `retiredTimeZone…` (comprobación de textos prohibidos en cada estado) |
| Revisión desde lectura nueva, todas las franjas, `0` destacado, sin edición | `reviewIsBuiltFromAFreshReadShowsEverySegmentAndHighlightsExplicitZeros` (otra conexión guarda la versión 2 y añade eventos antes de abrir: se revisa y confirma la 2 con `observed_event_seq` 2) |
| Bloqueos: cambios, texto pendiente, panel abierto, cambio de unidad | `reviewIsBlockedByUnsavedChangesPendingTextOpenPanelOrPendingUnitChange` |
| Doble toque | `doubleTapRecordsOneConfirmationAndOneRevocationEachWithOneIdentity` |
| En vuelo y rotación | `operationInFlightBlocksOtherActionsAndSurvivesRecreation` |
| Commit terminado antes de recrear | `commitFinishedBeforeRecreationIsShownAsRecordedNeverAsConflictOrReconfirmation` (también con retirada posterior por otra conexión) |
| Lectura no demostrada al recrear y reintento con la misma identidad | `unprovenReadKeepsTheOperationAndTheRetryReusesItsIdentity` |
| Solo una operación no encontrada se cierra | `onlyAnOperationThatIsNotStoredIsClosedAsStaleOrChanged` |
| Revocar, reconfirmar e historial | `revokeAndReconfirmAreSeparateActionsAndHistoryShowsEveryFact` |
| Conflictos | `conflictsWhileReviewingAreVisibleAndRecordNothing` |
| Lectura y escritura fallidas | `readFailuresAreNeverShownAsUnconfirmed`, `failedWritesConfirmNothingAndTheSameIntentionSucceedsAfterwards` |
| Bolo | `bolusSaysTheProfileIsNotUsedEvenWithConfirmedData` (el repositorio no recibe ninguna lectura) y `ClinicalProfileDeviceTest.bolusStaysBlocked…` |
| Layouts 840×900, 900×840 y 411×914 dp, texto 1,0× y 1,8×, claro y oscuro | `confirmationScreensFitExpandedLayoutsAtLargeFontInBothThemes`: revisión con ocho franjas y ceros, éxito, pregunta de retirada, E11, E10 con faltas, historial, operación indeterminada y fallo de escritura. Sin recortes ni elipsis y botones de al menos 48 dp |

## Verificación

Comandos ejecutados en Windows desde la rama de trabajo:

```powershell
.\scripts\verify.ps1
.\scripts\verify.ps1 -DeviceTests
git diff --check
```

- `scripts/verify.ps1`: clean, compilación, lint (`warningsAsErrors`), pruebas
  comunes de `shared/*` (dominio y política del perfil sin cambios), Android/JVM
  (incluida `ClinicalProfileSchemaTextTest`), manifests, exclusiones de backup,
  independencia del motor, de `ReadOverview` y de `ScreenRenderer`, workflow y
  `git diff --check`. Resultado: correcto. La primera ejecución falló en lint
  por `PluralsCandidate` en textos donde el número identifica una versión y no
  es una cantidad (ignorado solo en esos cinco textos) y por `SetTextI18n` en
  literales de la prueba (sustituidos por un ayudante, como en las pruebas
  existentes).
- `scripts/verify.ps1 -DeviceTests` en Pixel 10 Pro Fold, único dispositivo
  autorizado, API 37, con `KEYCODE_WAKEUP` cada 10 s y sin cambiar ajustes, red
  ni modo avión. Primera ejecución: 111 de 113. Dos fallos de la prueba nueva,
  no del código: contar eventos abriendo el repositorio con el trigger
  sintético presente (la verificación de esquema lo rechaza, como debe; ahora se
  cuenta en crudo) y buscar «sin confirmar» en una pantalla de error cuyo texto
  dice precisamente «No se muestra como sin confirmar» (ahora se busca «datos
  sin confirmar» y solo en vistas visibles). Tras corregirlos: `OK (113 tests)`
  en 107,6 s, que son las 98 anteriores sin cambios y 15 nuevas. Los paquetes
  de prueba se desinstalaron al terminar.
- Persistencia y migración: `SqliteClinicalProfileRepositoryDeviceTest` y
  `ClinicalProfileConfirmationDeviceTest` en verde dentro de la misma ejecución.

Funcionamiento offline: todo es local. El manifest sigue sin permiso de
Internet (`verify.ps1`) y las pruebas no cambian red, ajustes ni modo avión.
Ninguna prueba usa datos personales. Todas las bases son sintéticas y se borran.

## Riesgos y pendientes

- Riesgo R-017 (registro de riesgos): interpretar «Datos confirmados» como
  aprobación clínica. Mitigación: textos de la sección 9 con el bloqueo siempre
  presente, comprobación de textos prohibidos en las pruebas,
  `allowsCalculation = false` y Bolo con un texto fijo que no lee el perfil.
- Reinicio completo del proceso sin estado guardado: la intención se pierde y la
  lectura posterior muestra el estado real (ADR §7.3). No hay cola persistente.
- Cancelar una revisión con operación indeterminada descarta la operación y
  vuelve a leer: si el commit ocurrió, el estado lo muestra.
- Pendiente fuera de este ADR: correspondencia `input.profile.*` (documentada,
  no implementada ni conectada), opción B o C de la sección 6.3 antes de
  cualquier aprobación clínica, P4 y P6 del ADR 0012, límites, vigencia, DIA e
  IOB.
