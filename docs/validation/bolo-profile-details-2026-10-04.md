# Informe del perfil en Bolo y Diagnóstico — 2026-10-04

Base Next: `6f101fa2f3c03d21b957b9f6029badfa93759011` (PR #41, ADR 0017
aceptado). Rama `claude/bolo-profile-details`. Implementa el
[ADR 0017](../adr/0017-bolo-profile-unavailability-details.md) con B2 a B10,
la ampliación a Diagnóstico y las precisiones de su sección 14. Legacy no se
consulta ni se modifica.

## Qué existe tras esta entrega

- `ui/BlockingDetails.kt`: función pura de `UnavailableOverview` y
  `ProfileUnavailabilityBlock` a líneas. Un único informe v2 con glucosa, IOB y
  comida elevadas desde v1 y las causas del perfil del traductor. La causa
  estática del perfil de `ReadOverview` no se muestra: todo informe válido ya
  contiene `input.profile.policy_not_approved`. Con el rechazo conocido, las
  tres líneas elevadas y el identificador estable, sin causa del perfil. No
  captura nada. `destinations` fija `BOLUS`, `MANUAL`, `OFFLINE_BOLUS` y
  `DIAGNOSTICS`.
- `ProfileUnavailabilityBlock.line`: formato A5 compartido por Ajustes, Bolo y
  Diagnóstico.
- `MainActivity`: solo para esos destinos llama a `profile.ensureLoaded()` y
  compone las líneas con `profile.unavailability`. Nunca invoca `retry()` ni
  operaciones de escritura. `profile.changed` repinta también esos destinos.
  `blockingDetailsOpen` recuerda si el bloque está abierto mientras el destino
  no cambia. No se guarda: se pliega al cambiar de destino y tras recreación.
- `ScreenRenderer`: recibe `BlockingView` (líneas ya compuestas y estado
  abierto) y une las líneas con salto de línea. Sigue sin referenciar el
  perfil, confirmaciones ni tipos v2. `R.string.technical_codes` eliminado.
- `verify.ps1`: `BlockingDetails` y sus pruebas en la lista cerrada del
  traductor. Solo `ClinicalProfileModel`, `ClinicalProfileScreen` y
  `MainActivity` pueden nombrar `ensureLoaded` y el modelo del perfil.
  `MainActivity` lo invoca una sola vez y nunca `profile.retry(`. La lista de
  destinos es exactamente la aprobada. Nueva clase de instrumentación.

Sin cambios en `ReadOverview`, el motor, SQLite, el contrato v2, el traductor
ni las confirmaciones. `allowsCalculation` y `allowsTreatment` siguen en
`false`. La tarjeta «Perfil · no se usa para calcular» y los botones
deshabilitados no cambian. Sin textos nuevos.

## Pruebas

| Clase | Pruebas | Cubre |
|---|---|---|
| `BlockingDetailsTest` (JVM Android) | 7 | lista cerrada de destinos. E1 antes de la primera lectura. Para `null`, E1 a E11, retirada y zona retirada: líneas del perfil idénticas al traductor, entradas elevadas, orden por código, `policy_not_approved` presente una sola vez. Formato con detalle. Los 13 motivos de glucosa con todos los estados sin rechazo. Rechazo conocido: tres líneas y el identificador, ninguna `input.profile.*`. Otra excepción del traductor se propaga |
| `BolusProfileDetailsDeviceTest` (dispositivo) | 8 | ver abajo |
| `ClinicalProfileUnavailabilityDeviceTest` (dispositivo) | 4 | se retira la prueba que exigía el código estático en Bolo. El resto sin cambios |
| Regresiones ajustadas (dispositivo) | — | `NavigationDeviceTest`: con el perfil sintético en memoria, Bolo muestra `input.profile.missing` y `policy_not_approved` con su detalle. `ClinicalProfileConfirmationUiDeviceTest`: Bolo con datos confirmados mantiene el texto fijo y los botones deshabilitados, hace una lectura y cero escrituras. `MealDraftDeviceTest` y `DarkThemeDeviceTest` pasan líneas sintéticas de E1 a `ScreenRenderer`. `MealDraftDeviceTest`, `MealHistoryDeviceTest` y `MealRestoreDeviceTest` inyectan un repositorio de perfil en memoria |

`BolusProfileDetailsDeviceTest`, con repositorios sintéticos
(`ControlledProfileRepository` cuenta lecturas, versiones guardadas y
peticiones de confirmación) y recuento de filas en crudo de la base sintética:

- Inicio, Más, Escanear y Avisos no leen el perfil. Bolo hace una lectura.
- Bolo abierto sin pasar por Ajustes: E1 con la lectura retenida, después
  exactamente el estado leído. Bloque plegado por defecto, abierto durante el
  repintado, plegado al volver y tras recreación. Una sola lectura. Cero
  escrituras y filas sin cambios.
- Sin versión, incompleta, completa sin confirmar, confirmada, retirada, zona
  retirada y superada, en Bolo y Diagnóstico. Confirmada sigue con
  `policy_not_approved` y con «Calcular» y «Confirmar» deshabilitados.
- Lectura fallida: Bolo y Diagnóstico la muestran sin reintentar (una lectura).
  El reintento en Ajustes la sustituye sin conservar códigos anteriores.
- Rechazo conocido: identificador sin causa del perfil.
- Operación de confirmación pendiente tras un fallo de escritura: abrir Bolo y
  Diagnóstico, también tras recreación, no añade escrituras. Filas sin cambios.
- Modelo nuevo desde el estado guardado (muerte del proceso), sin y con
  operación pendiente: E1 antes del resultado, resolución solo de lectura, cero
  escrituras y filas sin cambios.

## Comandos y resultados (Windows, PC del propietario)

```powershell
git switch -c claude/bolo-profile-details origin/main   # 6f101fa, Verify 37181107344 success
.\scripts\verify.ps1                                     # RESULT=OK
adb disconnect 192.168.0.38:45015                        # reloj desconectado por el propietario, entrada offline
.\scripts\verify.ps1 -DeviceTests                        # con adb keyevent KEYCODE_WAKEUP cada 10 s
git diff --check                                         # sin salida
```

`verify.ps1` sin dispositivo: `BUILD SUCCESSFUL`, comprobaciones de manifiesto,
copias y arquitectura superadas. Pruebas JVM: motor 34, perfil 87, comidas 17,
traductor 25, Android 57 (50 anteriores más 7). Cero fallos.

Una primera ejecución falló en la regla nueva de reintentos, que también
detectaba el `retry()` del modelo de comidas. Se limitó al modelo del perfil
antes de repetir.

`verify.ps1 -DeviceTests`, tras confirmar el propietario que el móvil estaba
libre: `BUILD SUCCESSFUL`, comprobaciones superadas e instrumentación
`OK (127 tests)` (120 anteriores, menos la retirada, más 8 nuevas) en un Pixel
10 Pro Fold (API 37), único dispositivo autorizado. Paquetes desechables
desinstalados al terminar. Repositorios sintéticos y aislados. Sin datos
personales ni cambios de red.

### Primera ejecución en dispositivo: fallida

`Tests run: 127, Failures: 4`. Las 8 pruebas nuevas pasaron. Fallaron:

- `NavigationDeviceTest` y `ClinicalProfileConfirmationUiDeviceTest`: exigían
  el código estático y ninguna lectura en Bolo, el comportamiento que el ADR
  0017 sustituye. Se actualizaron a la conducta aprobada.
- `MealDraftDeviceTest` y `DarkThemeDeviceTest`: llamaban a
  `ScreenRenderer.render` para Bolo sin líneas y el renderizador rechazó
  pintar el bloque vacío (`blocking_details.missing`). Ahora reciben líneas
  sintéticas de E1.

Además, `MealDraftDeviceTest`, `MealHistoryDeviceTest` y `MealRestoreDeviceTest`
abrían Bolo sin inyectar repositorio de perfil, así que esa ejecución leyó la
`clinical-profile.db` propia de la app en el Pixel. Se comprobó sin extraer
contenido que la base está vacía (0 versiones, 0 franjas, 0 eventos,
`user_version` 2). Su fecha de modificación es la de esa ejecución
(2026-10-04 08:14:33): esa ejecución la creó o la abrió, pero no contiene
datos. La segunda ejecución no la modificó. Esas tres clases inyectan ahora un
repositorio en memoria y `verify.ps1` exige que toda clase de instrumentación
que lanza `MainActivity` inyecte un repositorio de perfil sintético.

### Barrera en tiempo de ejecución

A petición del propietario, la comprobación estática pasa a ser solo apoyo. La
protección efectiva está en el proceso de instrumentación:

- `ProfileStorageGuard` (código de la app): solo se puede activar, nunca
  desactivar. Producción no lo activa.
- `ProfileIsolationTestRunner` (androidTest) lo activa en `onCreate`, antes de
  crear la aplicación. Es el `testInstrumentationRunner` del módulo y el que
  usa `verify.ps1` en `am instrument`.
- `SqliteClinicalProfileRepository` comprueba la barrera en su primer `init`,
  antes de construir el `SQLiteOpenHelper`: si el nombre resuelve, con rutas
  canónicas y el mismo contexto que usa SQLite, a la `clinical-profile.db`
  propia, falla con `profile_storage.real_database_denied_in_instrumentation`.
  Cubre el nombre por defecto, el explícito, la ruta absoluta y rutas con `.`.
- `MainActivity.profileRepository` falla con
  `profile_storage.synthetic_factory_missing_in_instrumentation` si no hay
  fábrica sintética, antes de construir ningún repositorio.
- `ProfileStorageGuardDeviceTest` (4 pruebas) lo demuestra con un contexto
  aislado cuyo directorio de bases es una carpeta nueva en la caché de pruebas:
  allí la base «real» es la de esa carpeta, nunca la de la app instalada.
  Barrera activa antes de cualquier prueba, fábrica ausente, cuatro fábricas
  que apuntan a la base real y repositorios sintéticos permitidos. En los
  casos rechazados la carpeta queda vacía: nada se abrió ni se creó.
- `verify.ps1` conserva la comprobación estática de fábricas y añade que el
  runner y la llamada a la barrera sigan conectados.

Revalidación con la barrera: `verify.ps1` sin dispositivo `RESULT=OK` (JVM sin
cambios) y, con confirmación del propietario de que el móvil estaba libre,
`verify.ps1 -DeviceTests` con `OK (131 tests)` (127 más las 4 de la barrera)
en el mismo Pixel, único dispositivo en adb. La `clinical-profile.db` vacía de
la app conserva la fecha de modificación 2026-10-04 08:14:33: ninguna
ejecución posterior la ha abierto. Paquetes desechables desinstalados.

## Limitaciones

- La base vacía `clinical-profile.db` de la app en el Pixel se conserva por
  decisión del propietario.
- La barrera protege solo `clinical-profile.db`. La base de comidas sigue
  protegida únicamente por las fábricas de cada prueba.
- La muerte real del proceso no se provoca en instrumentación. Se reproduce
  creando un modelo nuevo desde el estado guardado, como en el ADR 0014.
- iOS no aplica: son pantallas Android. El traductor común sigue sin
  verificar en iOS (ADR 0004).
