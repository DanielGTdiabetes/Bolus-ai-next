# Parser estructural del payload Dexcom G7 — 2026-10-11

Base Next: `5c6816d1d27ee2238a4a320a159d5ed71285ef6d` (PR #43). CI del SHA
exacto: Verify
[37902507655](https://github.com/DanielGTdiabetes/Bolus-ai-next/actions/runs/37902507655),
evento `push`, conclusión `success`. Rama `claude/brave-pascal-ki8rt0`.
Implementa el [ADR 0018](../adr/0018-dexcom-g7-payload-parser.md) con D1 a D4.
Legacy no se consulta ni se modifica. No se usa ningún dispositivo.

## Qué existe tras esta entrega

- `shared/dexcom-g7-payload`: `DexcomG7Payload`, `PayloadValue`,
  `DexcomG7PayloadParser`, `ObservedDexcomG7Message`, `ObservedDexcomG7Reading`,
  `ObservedReadingStatus` (un solo valor), `ProducerUnitClaim` y
  `DexcomG7RejectionCode`.
- `settings.gradle.kts` incluye el módulo. Solo objetivo JVM.
- `verify.ps1`: el módulo no depende de motor, perfil, comidas, Android ni Java
  y nada fuera de él lo usa.

Sin cambios en Android, receivers, permisos, manifiestos, SQLite, motor,
perfil, comidas ni contratos publicados. `PendingDexcomSource` sigue
devolviendo `policy_not_approved`.

## Pruebas

| Clase | Pruebas | Cubre |
|---|---|---|
| `DexcomG7PayloadParserTest` (JVM común) | 21 | véase la sección «Seguridad y pruebas» del ADR 0018 |

## Comandos y resultados (contenedor Linux de la sesión)

```bash
./gradlew --no-daemon :shared:dexcom-g7-payload:androidJvmTest   # BUILD SUCCESSFUL, 21 tests, 0 fallos
./gradlew --no-daemon :shared:dexcom-g7-payload:allMetadataJar   # BUILD SUCCESSFUL
git diff --check                                                  # sin salida
```

El contenedor no tiene Android SDK ni PowerShell: `verify.ps1` completo se
ejecuta en el CI de Windows de la PR. `verify.ps1 -DeviceTests` no es necesario
para este incremento: no cambia la APK ni la instrumentación.

## Pendiente

Release reproducible del productor, opt-in de identidad, anclas aprobadas,
semántica de `timestamp`/`egvTxTime`, tendencia, identidad de lectura, vigencia,
persistencia, receiver productivo y prueba real en modo avión. R-010 y R-014
siguen abiertos.
