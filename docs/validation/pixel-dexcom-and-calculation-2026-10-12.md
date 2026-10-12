# Pixel: recepción Dexcom y cálculo de prueba — 2026-10-12

Validación complementaria de la PR #57, integrada como
`3355f219295b7ecf2ddfeb4ac369a6c1bb99ca7e`. Alcance: pruebas de dispositivo,
visibilidad I-3 y correcciones del arnés. No modifica perfil, límites ni motor,
que se desarrollan en paralelo por Claude. No cambia Dexcom, Legacy ni reloj.

## P2: condición I-3 demostrada

Pixel 10 Pro Fold, Android 17/API 37, usuario 0; un único dispositivo USB
autorizado. Al inicio solo estaba activo el servidor ADB, sin otros clientes
detectados. Next recibió su permiso Dexcom mediante `pm grant`.

El 2026-10-12 a las 05:49:15 UTC, el Diagnóstico de Next sin
`QUERY_ALL_PACKAGES` mostró:

```text
dexcom_reception.next_permission=granted
dexcom_reception.gate_now=dexcom_reception.visibility_unproven
dexcom_reception.full_visibility=false
dexcom_reception.visible_holders=com.dexcom.g7,org.bolusai.next
```

La consulta de permisos concedidos del sistema, a las 05:50:05 UTC, encontró
exactamente cuatro titulares: `com.dexcom.g7`, `org.bolusai.companion`,
`com.wtachtsugar` y `org.bolusai.next`. Next no veía Legacy ni reloj. Por tanto
se cumple la condición ya autorizada en ADR 0023 I-3 y se añade
`QUERY_ALL_PACKAGES`. Las consultas limitadas a paquetes conocidos no prueban
la ausencia de un titular inesperado. El aviso genérico de lint de ese permiso
se exceptúa solo en su declaración, con esta justificación; lint permanece
activo y la verificación exige el permiso en ambos manifiestos.

## Activación durante validación

En este Pixel, `adb shell pm enable`, incluso con `--user 0`, devuelve
`SecurityException: Shell cannot change component state`. El propietario
autorizó expresamente `run-as` para estas pruebas y cuando sea necesario.
El siguiente comando funcionó con la identidad propia de Next:

```text
adb shell run-as org.bolusai.next pm enable --user 0 org.bolusai.next/.glucose.dexcom.DexcomG7Receiver
```

El manifiesto conserva el receptor desactivado por defecto. Las pruebas
instrumentadas parten de `pm default-state` con esa misma identidad.

## P1: ejecución inicial y correcciones

`scripts/verify.ps1 -DeviceTests` sobre el código integrado: compilación,
unitarias y aislamiento correctos; 169 pruebas instrumentadas, 161 correctas
y 8 fallos. Se corrigieron las causas sin cambiar reglas del motor:

- El test antiguo prohibía solicitar el permiso Dexcom, ya solicitado por PR #57.
- Cuatro recorridos esperaban altura positiva incluso en el contenedor vacío
  del resultado de prueba. Se siguen comprobando todos los controles reales;
  se excluyen los contenedores estructurales sin hijos.
  La captura restante se reprodujo con la pantalla en reposo; pasó al despertar
  la pantalla. El arnés utiliza `KEYCODE_WAKEUP`, sin desbloquear credenciales
  ni cambiar ajustes, conservando los dos frames estables requeridos.
- El fixture SQL de corrupción omitía `provenance`, obligatorio en esquema v2.
- El test de versión futura usaba v2, que ahora es la versión soportada;
  utiliza la versión soportada más uno y conserva la comprobación de integridad.
- El test de acción distinta suponía que Android comprueba el permiso entre
  emisor y receptor del mismo UID. Se verifica su entrega e ignorado; el test
  separado de emisor sin permiso desde otro UID se mantiene.
- En la repetición llegó tráfico real mientras el test negativo tenía el
  receptor activo. La barrera impidió abrir el registro real (`payloadReads=0`),
  pero el test confundió ese recibo con una entrega del emisor sintético.
  El emisor de prueba ahora dirige una acción exclusiva al mismo componente
  protegido: una entrega indebida produce un recibo identificable antes de leer
  extras. El tráfico real no puede producir ese recibo. Se comprueba además
  que el UID emisor de prueba carece del permiso.

Se añaden dos pruebas instrumentadas de cálculo con perfil y lecturas sintéticas,
repositorios en memoria y bloqueo del acceso a los archivos reales. Comprueban
IOB ausente, total candidato conocido, aviso no terapéutico, confirmación
deshabilitada e invalidación tras editar hidratos o cambiar la lectura utilizada.

## Resultados finales

En ejecución. P1 corregido, P3, P4, P5 y P6 se registrarán aquí al obtener
evidencia; todavía no se declaran superados. B1 necesita aprobación del informe
por el propietario y sigue sin verificarse en la aplicación.

Evidencia local (logs y JSON minimizados, fuera del repositorio):
`%LOCALAPPDATA%\BolusAiNext\pixel-validation\20261012T054243Z`.
No se incluyen valores reales de glucosa ni identificadores de transmisor.
