# Validación: historial local de dosis e IOB calculado (ADR 0028) — 2026-11-28

Rama `claude/local-dose-history`, PR 62. Ningún resultado es utilizable para
tratamiento. Legacy solo se leyó por GitHub (`main` remoto `e48c4ab8`; reglas
fijadas en `bcac891d`). Dexcom y la app del reloj no se tocaron.

## Comandos y resultados

| Comando | Resultado |
|---|---|
| `.\scripts\verify.ps1` | salida 0, `BUILD SUCCESSFUL`, vectores de Legacy reproducidos |
| `.\scripts\verify.ps1 -DeviceTests` (1.ª ejecución) | 189 pruebas, 3 fallos ajenos al cambio (ver abajo) |
| `.\scripts\verify.ps1 -DeviceTests` (2.ª, con las correcciones de revisión) | salida 0, `OK (189 tests)` |

Dispositivo: Pixel 10 Pro Fold (`rango`), único en `adb devices`, conectado por
el propietario para esta prueba. `SqliteDoseLedgerDeviceTest` pasó 8/8 sobre un
fichero sintético; la base real `insulin-doses.db` no se abre en
instrumentación (`DoseStorageGuard`).

## Fallos de la primera ejecución

`DexcomLogDiagnosticsDeviceTest` (2) y `DexcomG7ReceiverDeviceTest` (1): el
receptor de Next estaba habilitado para la recepción real y esas pruebas
esperan el estado por defecto del manifiesto. Es el mismo caso de la
[validación del 2026-10-12](profile-insulin-action-2026-10-12.md). No se cambió
código ni expectativas. La propia prueba dejó el componente en estado por
defecto al terminar.

Para la segunda ejecución:

```text
adb shell run-as org.bolusai.next pm default-state --user 0 org.bolusai.next/.glucose.dexcom.DexcomG7Receiver
adb shell run-as org.bolusai.next pm enable --user 0 org.bolusai.next/.glucose.dexcom.DexcomG7Receiver
```

El segundo se ejecutó al terminar; `dumpsys package org.bolusai.next` muestra el
receptor en `enabledComponents`. Entre la primera ejecución y la restauración
el receptor estuvo en estado por defecto (desactivado), así que Next no recibió
lecturas reales en ese intervalo.

## Correcciones de revisión incluidas

- Una dosis registrada desde un cálculo guarda en su misma fila el registro de
  auditoría: petición canónica (su SHA-256 es la huella) y resultado con pasos,
  avisos e IOB usado. Antes solo guardaba unidades y huella.
- El total del motor puede traer ruido binario (`0,1 × 3`): ya no oculta el
  botón de registrar. Solo se elimina ruido por debajo de `1e-9`; no se redondea.

## No comprobado

- Recorrido manual en Bolo con el registro real: empezar, anotar, registrar un
  total, anular y cálculo con IOB tras cubrir la acción de la insulina.
- Prueba instrumentada de los diálogos de confirmación.
- iOS.
