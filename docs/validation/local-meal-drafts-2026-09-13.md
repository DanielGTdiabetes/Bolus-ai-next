# Borradores locales de comida — validación 2026-09-13

Base Next: `572e6dba06bca6a891c9442a6c1107b88c07ef3a`.
Legacy consultado solo en lectura:
`f5417721d8019a9831126f4d843edfc4de87653d`.
Alcance: [ADR 0008](../adr/0008-local-meal-drafts.md) y
[trazado Legacy](../audit/legacy-meal-entry-2026-09-13.md).

## Resultado local

`scripts/verify.ps1 -DeviceTests` terminó con código 0 en Windows. Ejecutó clean,
compilación KMP/Android, tests JVM, lint, APK debug/test, manifests debug/release y
la instrumentación USB. El arnés confirmó `OK (20 tests)` en el teléfono API 37.

| Suite | Casos | Cobertura relevante |
|---|---:|---|
| Núcleo de bloqueos KMP/JVM | 11 | Regresión de contratos no disponibles. |
| Borradores KMP/JVM | 3 | Ausencia frente a `"0"`, copia independiente, fallo/conflicto e idempotencia. |
| Android/JVM | 40 | Navegación, frontera Dexcom y presentación fail-closed existentes. |
| Instrumentación USB | 20 | 8 autenticación, 7 navegación/bloqueos, 2 UI de borradores y 3 SQLite. |

Las pruebas SQLite usan bases sintéticas con nombre aleatorio y las eliminan al
terminar. Cubren revisión 1, corrección como revisión 2, cierre/reapertura del
repositorio, lectura exacta, reintento idéntico, conflicto con editor obsoleto y
rechazo de una base preversionada desconocida. No se fabrica una migración desde
una versión anterior porque v1 es el primer esquema; una estructura desconocida no
se presenta como biblioteca vacía.

La prueba UI crea y edita una comida sintética, conserva grasa ausente y HC `"0"`,
espera la confirmación durable y comprueba que no existen controles de cálculo o
confirmación. Otra prueba inyecta `meal.storage.save_failed`: el editor conserva
el texto y nunca muestra «guardado». El recorrido Bolo → Mis platos y Bolo → comida
manual queda cubierto con cálculo/confirmación deshabilitados.

La primera ejecución USB detectó un error en el test: comprobaba el valor de retorno
de `CheckBox.performClick()` en vez del estado marcado. La aplicación y las pruebas
SQLite ya habían pasado. Se corrigió la aserción para comprobar `isChecked` y se
repitió el arnés completo; la ejecución final terminó verde.

## Dispositivo y aislamiento

Se instaló el APK final de Next en el único teléfono USB autorizado. Los APK de test
y emisor sintético se retiraron al acabar; `pm path` no devolvió ninguno. Next quedó
instalada y el arranque final devolvió `Status: ok`, `LaunchState: COLD`.

No se leyó la base normal de comidas durante instrumentación: las pruebas de
navegación inyectan SQLite en memoria mediante una factoría interna del proceso de
test, sin selector por Intent. Las otras pruebas usan bases sintéticas aisladas. No
se capturó logcat, no se consultaron datos personales, no se cambió conectividad y
no se accedió a Legacy, Dexcom o servicios.

## Límites comprobados

La app sigue sin permiso INTERNET, receivers productivos o permisos Dexcom. Guardar
un borrador no crea timestamps de ingesta, recomendaciones, confirmaciones, eventos
clínicos, items de sync ni tratamientos. Cada registro mantiene
`meal.draft.not_clinically_validated`; cálculo y tratamiento permanecen bloqueados.
No se validó iOS en este host Windows y no se afirma soporte iOS actual.
