# Contrato de indisponibilidad v2 y traductor del perfil — 2026-10-03

Base Next: `b3cf7e6c869b2fe9aa7ed9a873a5323705145ec9` (PR #35, ADR 0015
aceptado). CI del SHA exacto comprobado antes de editar con
`gh run list --commit b3cf7e6…`: Verify
[37139124635](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37139124635),
evento `push`, conclusión `success`. Rama `claude/profile-unavailability-v2`.
Implementa el [ADR 0015](../adr/0015-profile-unavailability-contract.md) con
D1 a D10, autorizado por el propietario. Legacy no se consulta ni se modifica.

## Qué existe tras esta entrega

- `shared/bolus-engine/.../InputUnavailability.kt`: `UnavailabilityReasonV2`
  (13 motivos v1 + `unconfirmed`), `InputUnavailability`,
  `InputUnavailabilityReport` e `InputUnavailabilityErrors`. Admisibilidad,
  elevación v1 → v2, gramática, límites, copias, igualdad, orden y unión según
  C.1 y C.2. `@Throws(IllegalArgumentException::class)` en los dos
  constructores. Contrato publicado en
  [`unavailable-input-v2`](../contracts/unavailable-input-v2.md).
- `shared/profile-unavailability` (módulo común nuevo, KMP JVM + iOS):
  `ProfileUnavailability.report(ProfileGateState)`, con las reglas de 6.1 y la
  matriz de 6.2. Depende del perfil y del contrato (`api`). Ni el motor, ni el
  perfil, ni Android dependen de él.
- `ReadOverview`: perfil `input.profile.policy_not_approved` estático (D7), sin
  leer el perfil. `ReadOverviewTest` y `NavigationDeviceTest` actualizan solo
  esa expectativa. La segunda comprueba además que `input.profile.missing` ya
  no aparece en los detalles técnicos de Bolo.
- `verify.ps1`: el motor no referencia el perfil ni el traductor, el perfil no
  referencia el contrato ni el traductor, ningún fuente o build de `android/` ni
  de `shared/meal-drafts` usa el traductor, y `ReadOverview` y `ScreenRenderer`
  no usan tipos v2.
- `tests/swift/UnavailableInputReportSmoke.swift`: rechazos v2 de causa (motivo
  no admitido y detalle mal formado) y de informe vacío con `try`, y
  construcción correcta posterior en el mismo proceso.

Sin cambios de SQLite, huellas, serialización del perfil, confirmaciones,
consumidores, fórmulas, límites clínicos, vigencia, DIA, IOB, conversiones,
Dexcom, sincronización ni Legacy. `allowsCalculation` y `allowsTreatment`
siguen en `false`. v1 y sus pruebas sin cambios.

## Pruebas

| Clase | Pruebas | Cubre |
|---|---|---|
| `InputUnavailabilityTest` (común) | 13 | códigos v1 + `unconfirmed`, versión 2, 53 admitidas y 3 rechazadas con su identificador (contra una tabla independiente), admisión antes que detalle, elevación de las 52 de v1, longitud 1/128/129 y vacío, 17 detalles mal formados (mayúsculas, segmento vacío o numérico, espacios, salto de línea, no ASCII, texto traducido), espacio de nombres ajeno, 16 y 17 detalles y duplicados, rechazo independiente del orden del productor, igualdad por contenido normalizado, mutación de la lista del productor y de la devuelta, v1 intacto |
| `InputUnavailabilityReportTest` (común) | 8 | vacío, orden por código para todas las rotaciones de las 53 causas, unión ordenada sin duplicados, motivos distintos sin unir, límite aplicado tras la unión (16 aceptado, 17 rechazado sin recorte), mutaciones de lista de causas, `entries` y `details`, elevación de un informe v1 completo |
| `PublishedContractV2Test` (JVM) | 2 | lee las tablas marcadas del documento publicado y las compara con el código y con la tabla de las pruebas: 14 filas de admisibilidad y los 5 identificadores de rechazo |
| `ProfileUnavailabilityTest` (común) | 25 | E1 a E11 exactos: E2 con `read_failed` y `corrupt`, E3 y E4 sintéticos y desde una validación real, E5, E6 con solo faltas, con unidad y zona sin declarar, con solo zona y con ambas, E7, E8 completa, incompleta y con faltas y zona, E9 con y sin superadas, E10 completa, con superadas, con zona, con zona y superadas, y con faltas, zona y superadas, E11, confirmación vigente con faltas y zona, y los 27 fallos que no son de lectura rechazados. Tras cada prueba: `policy_not_approved` presente, nunca `expired` ni `conflicting`, solo `profile`, bloqueo intacto y todos los `detailCodes` del dominio presentes en el detalle |

Regresión sin cambios: `UnavailableInputTest` (4), `UnavailableInputReportTest`
(6), `GlucoseStatusPresenterTest` (4), pruebas Dexcom y de comidas. Los
historiales de las pruebas del traductor se construyen con
`ProfileWritePolicy`, `ConfirmationPolicy` y `ProfileRecord.validate`, con
valores sintéticos.

Prueba de mutación manual: quitar `superseded` del detalle de la retirada en el
traductor hizo fallar 3 pruebas de `ProfileUnavailabilityTest`. Se restauró el
código y se repitió la ejecución en verde.

## Comandos y resultados (Windows, PC del propietario)

```powershell
gh run list --commit b3cf7e6c869b2fe9aa7ed9a873a5323705145ec9   # Verify 37139124635 success
git switch -c claude/profile-unavailability-v2 origin/main
.\gradlew.bat --no-daemon --continue -q :shared:bolus-engine:androidJvmTest `
    :shared:clinical-profile:androidJvmTest :shared:profile-unavailability:androidJvmTest   # exit 0
.\scripts\verify.ps1 -DeviceTests   # Windows PowerShell 5.1, con adb keyevent KEYCODE_WAKEUP cada 10 s
git diff --check                    # sin salida, exit 0
```

`verify.ps1 -DeviceTests`: `BUILD SUCCESSFUL`, comprobaciones de manifiesto,
copias y arquitectura superadas, instrumentación `OK (115 tests)` en un Pixel
10 Pro Fold (API 37) con las clases de la lista de `verify.ps1`, paquetes
desechables (`senderfixture`, `next.test`) desinstalados al terminar. Pruebas
JVM: motor 34, perfil 87, comidas 17, traductor 25, Android 44. Cero fallos,
errores u omisiones. Repositorios de dispositivo sintéticos y aislados, como
fijan las pruebas existentes. Sin datos personales ni cambios de red.

## Limitaciones

- **iOS no verificado.** El binding de los tipos v2 y del módulo traductor no se
  ha compilado ni ejecutado en macOS. La prueba Swift está ampliada pero no se
  ejecuta sin autorización explícita (ADR 0004). No se añade CI macOS.
- El nombre Swift de la elevación (`InputUnavailability.companion.from(cause:)`)
  sigue la convención de Kotlin/Native y no está comprobado.
- `Unreadable` con un fallo que no es de lectura se rechaza con
  `profile_unavailability.unreadable_reason_not_supported`. Es una decisión de
  implementación registrada en el ADR 0015, sección 13, para revisión.
- La prueba de dispositivo no cubre el traductor: ningún consumidor Android lo
  usa. Solo cubre el código estático de D7 en Bolo.
