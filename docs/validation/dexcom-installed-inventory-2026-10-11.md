# Inventario de solo lectura de la recepción Dexcom instalada — 2026-10-11

- ADR: [0023](../adr/0023-dexcom-existing-transport-trust.md), prerrequisito L1
- Autorización: propietario, 2026-10-11. Solo lectura: sin cambiar permisos,
  instalaciones ni ajustes, y sin interrumpir Dexcom ni el sensor
- Ejecución: Codex en el PC del propietario, con el Pixel por USB; evidencia
  incorporada a este documento por Claude
- Inicio UTC: 2026-10-11T17:15:01.9221054Z
- Fin UTC: 2026-10-11T17:19:32.0037172Z
- Ficheros locales, no versionados: `%LOCALAPPDATA%\BolusAiNext\dexcom-inventory\20261011T171501Z`
  (volcados `dumpsys` inicial y final, solicitudes de permisos, APK copiadas y JSON
  con rutas y salidas de verificación)

Este documento registra lo observado. No aprueba anclas, conjunto, riesgos ni
uso clínico.

## Aclaración posterior del propietario — 2026-10-11

`com.wtachtsugar` es su app del reloj para ver la glucosa de Dexcom G7. El
propietario indica que también permite conectar directamente el sensor sin
la app Dexcom y que está previsto que retransmita glucosa hacia Next. Precisa
que el reloj solo emite cuando falla la app Dexcom: es una fuente de respaldo,
sin emisión simultánea prevista desde ambas apps. Ese comportamiento y el
cambio de fuente no fueron probados por este inventario. Esto
aclara su función y la necesidad de conservar su permiso; no es una capacidad
probada en este inventario ni aprueba anclas, riesgos o recepción de sus
futuros mensajes. Esa integración requiere contrato y validación propios y
no bloquea el hito actual de recepción con la Dexcom instalada.

## Alcance y comandos

Permitidos y usados: `adb devices`, `getprop`, `pm list users`, `pm path`,
`dumpsys package`, `adb pull` de las APK al PC, `apksigner verify --print-certs`
y `Get-FileHash`. No se usaron `am`, `pm grant/revoke/enable/disable/install/
uninstall/clear`, `settings`, `svc`, `cmd`, `run-as`, `logcat` ni cambios de red,
Bluetooth o modo avión. No se abrieron Dexcom, Legacy ni Next ni se leyeron
datos de glucosa.

Ningún comando de lectura del teléfono falló. Los fallos preliminares del
entorno local (aislamiento de ADB, CIM y localización del SDK) quedan en el JSON
local; no afectaron al teléfono.

## Dispositivo

| Campo | Valor |
|---|---|
| Dispositivos en `adb` | 1 autorizado; solo el servidor ADB habitual, sin otros clientes detectados (comprobación puntual) |
| SDK / Android | 37 / 17 |
| Build | `CP3A.260905.009` |
| Producto | `rango` |
| Usuarios/perfiles | `0` |

## Definición del permiso

| Campo | Valor |
|---|---|
| Permiso | `com.dexcom.cgm.EXTERNAL_PERMISSION` |
| Declarante | `com.dexcom.g7` (UID 10451) |
| Protección | `dangerous` |
| Declarantes observados | 1 |

## Solicitudes y concesiones

Paquetes activos examinados por `shell`: 527. Las 77 entradas adicionales de
sistema ocultas duplican nombres del conjunto; 527 nombres únicos.

| Paquete | Declara | Solicita | Concedido (usuario 0) |
|---|---|---|---|
| `com.dexcom.g7` | sí | sí | sí, runtime |
| `org.bolusai.companion` (Legacy) | no | sí | sí, runtime |
| `com.wtachtsugar` | no | sí | sí, runtime |
| `org.bolusai.next` | no | no | no |

No hay concesiones de instalación para este permiso. Ningún otro paquete lo
declara, solicita ni tiene concedido.

## Identidades

| Paquete | UID | `versionCode` | `versionName` | Instalador | `sharedUser` |
|---|---|---|---|---|---|
| `com.dexcom.g7` | 10451 | 4537 | `1.6.1.4537` | `null` | no |
| `org.bolusai.companion` | 10450 | 13 | `0.2.0-alpha10` | `null` | no |
| `org.bolusai.next` | 10495 | 1 | `0.1.0-dev` | `null` | no |
| `com.wtachtsugar` | 10350 | 3 | `1.1.1` | `null` | no |

Todos instalados en el usuario 0. Android muestra `appId`; para el usuario 0
coincide con el UID.

## APK y firmas

Cada paquete tiene una única `base.apk`, sin splits. Las cuatro verificaciones
de `apksigner` terminaron con salida 0.

| Paquete | SHA-256 de `base.apk` | SHA-256 del certificado firmante | Esquemas verificados |
|---|---|---|---|
| `com.dexcom.g7` | `6746ac78f5c6eaa4b4ad802f0dec59e55a64b05821d0ff43de4e0f39c8e4b572` | `d30e0d3f08b70cedbc0804c96f2ec4599f243b5e149ef73a601757ad94c74145` | v3 |
| `org.bolusai.companion` | `a6a2dad898b7ca0793ab4fe08926f09789ed6b22a386f4272db961d436a0dc8a` | `1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d` | v2 |
| `org.bolusai.next` | `5e19b6a09646dd5d1f4d87db77d505b9b2d8e4204a07dc5cb52fa1cf3485ef96` | `1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d` | v2 |
| `com.wtachtsugar` | `d8be6ff0cafbd9c3c05e891b6eb0ca84a18a8320bc1ec9c5abbdc449e793f986` | `1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d` | v2 |

`v4=false` significa que no se verificó: no se copiaron ficheros `.idsig`.
`PackageManager` muestra `past signatures:[]` en los cuatro: no hay rotación
registrada. No es una auditoría independiente del linaje de claves.

## Comparación con el contrato observado (2026-09-06)

- SHA-256 de la APK base de Dexcom: coincide exactamente con `6746ac78…`.
- `versionCode` 4537: coincide.

## Estabilidad

Sin cambios en definición, solicitudes, concesiones ni identidades relevantes
entre la lectura inicial y la final; 527 paquetes en ambas. No descarta cambios
transitorios entre las dos lecturas.

## Hallazgos para el ADR 0023

1. Dexcom tiene el permiso **concedido**, no solo declarado: la política por
   permiso es aplicable al productor instalado.
2. El conjunto real de titulares no es el candidato del ADR (Dexcom, Legacy y
   Next): incluye `com.wtachtsugar` y todavía no incluye Next, que no solicita
   el permiso (R2 del ADR 0022).
3. Legacy, Next y `com.wtachtsugar` comparten certificado firmante
   (`1d1f25bf…`). Distinguirlos exige anclar paquete y versión además de la
   firma. Este inventario no identifica qué almacén de claves es ni quién lo
   custodia.
4. Los cuatro tienen instalador `null` (instalación lateral). No hay tienda que
   aporte otra garantía de origen.
5. La enumeración la hizo `shell`, que ve todos los paquetes. No demuestra qué
   ve Next: eso sigue siendo la prueba de I-3.
