# Primer consumidor del informe del perfil: pantalla del perfil — 2026-10-04

Base Next: `14093c3e51a87b36031007a8d84f950f8665a51c` (PR #38, ADR 0016
aceptado). CI del SHA exacto: Verify
[37176403975](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37176403975),
evento `push`, conclusión `success`. Rama `claude/profile-unavailability-screen`.
Implementa la opción A del [ADR 0016](../adr/0016-profile-unavailability-consumer.md)
con A1 a A6 y A4 precisada. Legacy no se consulta ni se modifica.

## Qué existe tras esta entrega

- `ui/ProfileUnavailabilityBlock.kt`: función pura de `ProfileGateState?` a
  `Report` (informe v2 del traductor) o `Rejected` (identificador estable).
  `null` se traduce como E1. Solo captura `IllegalArgumentException` con el
  mensaje `profile_unavailability.unreadable_reason_not_supported`. Cualquier
  otra excepción se propaga.
- `ClinicalProfileModel.unavailability`: propiedad derivada de `gate` en cada
  acceso. Sin estado propio ni persistencia.
- `ClinicalProfileScreen`: bloque al final de la vista principal, con botón
  `profile:unavailability:toggle` («Detalles del bloqueo», el mismo recurso que
  Bolo) y texto seleccionable `profile:unavailability`, plegado por defecto.
- `MainActivity.profileDetailsOpen`: recuerda si el bloque está abierto entre
  repintados de la misma sección. No se guarda: se pliega al cambiar de sección
  o destino y tras recreación.
- `android/app/build.gradle.kts`: dependencia de `:shared:profile-unavailability`.
- `verify.ps1`: lista cerrada de ficheros de Android que pueden usar el
  traductor, `MainActivity` sin tipos v2 y la nueva clase de instrumentación.

Sin cambios en Bolo, `ReadOverview`, `ScreenRenderer`, el motor, SQLite, el
contrato v2, el traductor ni las confirmaciones. `allowsCalculation` y
`allowsTreatment` siguen en `false`.

## Pruebas

| Clase | Pruebas | Cubre |
|---|---|---|
| `ProfileUnavailabilityBlockTest` (JVM Android) | 6 | `null` y `ReadPending` como E1, informe idéntico al del traductor para E2 a E7 con su orden, formato con y sin detalle, rechazo conocido para los 27 fallos que no son de lectura con el estado aún bloqueado, propagación de otro `IllegalArgumentException`, de uno sin mensaje y de un `IllegalStateException` con el mismo texto, y secuencias de estados sin restos del anterior |
| `ClinicalProfileUnavailabilityDeviceTest` (dispositivo) | 5 | bloque plegado y seleccionable, abrir y cerrar. Códigos exactos para E5, E6, E7, E9, E10, E11 y E8. E2 (`read_failed`, `corrupt`), E3 y E4 con `ControlledProfileRepository`. Rechazo conocido con un fallo de guardado inyectado como lectura: muestra el identificador y ninguna acción de confirmación. Transición de lectura retenida al resultado sin conservar `profile.read.pending`. Reintento tras E2: pasa por la lectura pendiente y termina en E7 sin conservar códigos anteriores. Recreación: plegado y con el estado vigente. Bolo con el código estático, sin el informe del perfil y con el cálculo deshabilitado |

Las comparaciones del dispositivo son de igualdad exacta de la lista de líneas,
así que cualquier línea de un estado anterior las haría fallar.

## Comandos y resultados (Windows, PC del propietario)

```powershell
gh run list --commit 14093c3e51a87b36031007a8d84f950f8665a51c   # Verify 37176403975 success
git switch -c claude/profile-unavailability-screen origin/main
.\gradlew.bat --no-daemon :android:app:testDebugUnitTest :android:app:compileDebugAndroidTestKotlin   # exit 0
.\scripts\verify.ps1 -DeviceTests   # Windows PowerShell 5.1, con adb keyevent KEYCODE_WAKEUP cada 10 s
git diff --check                    # sin salida
```

`verify.ps1 -DeviceTests`: `BUILD SUCCESSFUL`, comprobaciones de manifiesto,
copias y arquitectura superadas, instrumentación `OK (120 tests)` (115
anteriores más 5 nuevas) en un Pixel 10 Pro Fold (API 37), paquetes
desechables desinstalados al terminar. Pruebas JVM: motor 34, perfil 87,
comidas 17, traductor 25, Android 50. Cero fallos. Repositorios sintéticos y
aislados. Sin datos personales ni cambios de red.

Una primera ejecución de `verify.ps1` sin dispositivo terminó la parte de
Gradle en verde y falló en `git diff --check` por una línea en blanco al final
del ADR 0016. Se corrigió antes de la ejecución con dispositivo.

## Limitaciones

- El rechazo conocido solo se puede provocar en dispositivo inyectando un fallo
  que no es de lectura en el repositorio de prueba. Ningún productor real lo
  genera.
- iOS no aplica: el bloque es una pantalla Android. El traductor común sigue
  sin verificar en iOS (ADR 0004).
- Bolo sigue con el código estático del perfil. Su conexión (B1) es solo
  planificación.

## Integración

- Aprobación del propietario sobre `a267f67` (Windows verification
  [37177046158](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37177046158)
  y GitGuardian en verde).
- `gh pr merge 39 --merge --match-head-commit a267f67f3e3e834b317d2a17dd5f6fe6f2be3ed9`:
  merge commit `c583e6a3cdeb34fb4ddaf75bbcffdc620c629f0b`.
- CI del merge commit en `main`: [Verify 37177820636](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37177820636), evento `push`, conclusión `success`.
- Una PR posterior, solo documental, registra estos datos.
