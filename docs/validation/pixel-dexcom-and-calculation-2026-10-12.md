# Pixel: recepción Dexcom y cálculo de prueba — 2026-10-12

Validación complementaria de la PR #57, integrada como
`3355f219295b7ecf2ddfeb4ac369a6c1bb99ca7e`. Alcance: pruebas de dispositivo,
visibilidad I-3 y correcciones del arnés. No modifica modelo del perfil, límites ni motor,
que desarrolló Claude en la PR #58, integrada después como `fe967f7` y conservada
al combinar esta validación. La repetición encontró además una previsualización
obsoleta en su editor: se corrige el refresco visual, sin cambiar modelo, límites
ni motor. No cambia Dexcom, Legacy ni reloj.

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

La repetición sobre PR #58 ejecutó 176 pruebas, con cinco fallos adicionales:
el fixture de esquema desconocido usaba el ahora válido esquema 2; dos lecturas
de copias v1 utilizaban el codec actual, que ya consulta `profile_limits`; el
test de Bolo prohibía el texto de confirmación que ahora muestra el bloque de
prueba; y al introducir el primer límite, las previsualizaciones de los otros
seguían mostrando «versión sin límites» aunque el borrador ya era esquema 2.
Se usa esquema actual + 1, se congela el codec de lectura v1 de Next `3355f21`
solo en tests (sin escribir ni migrar las copias), se comprueba por separado
el bloque de límites de prueba y se refrescan todas las previsualizaciones al
editar un campo. La prueba existente reproduce esa transición y protege la
corrección. No se cambiaron reglas ni valores clínicos.

## Resultados finales

- **P1 inicial corregido:** `scripts/verify.ps1 -DeviceTests`, exit 0,
  `VERIFY_DEVICE_TESTS_SUCCESS`, 171 pruebas instrumentadas correctas en
  142,654 segundos. Se conservaron las barreras de aislamiento. Tras integrar
  PR #58, `scripts/verify.ps1` volvió a terminar con exit 0; la repetición
  instrumentada sobre ambas entregas, después de corregir los cinco fallos
  descritos, terminó con **exit 0, `OK (176 tests)` en 144,512 segundos**.
  También pasaron **374 pruebas unitarias**, lint, compilación y las barreras
  estáticas. Comando final: `scripts/verify.ps1 -DeviceTests`.
  Log local: `verify-combined-device-final.log`. APK instalada SHA-256:
  `2c2cb2f6aea94ea0fd079a25e8ad183d9fd6a1fc1aebac2b132fbea9304417d8`.
- **P3:** Diagnóstico a las 06:21:45 UTC: receptor habilitado, permiso concedido,
  `gate_now=open`, `full_visibility=true`, exactamente los cuatro titulares
  aprobados. Un mensaje y una lectura almacenada, sin conflictos ni duplicados.
- **P4, antes de reiniciar:** recibido a las 06:21:39.340 UTC, después de apagar
  Wi-Fi y datos a las 06:19 UTC. Inicio y Bolo muestran número y las etiquetas
  de hora no verificada, vigencia pendiente y procedencia del conjunto aprobado.
  Tras reiniciar a las 06:26 UTC se conservaron el registro y esas etiquetas.
  A las **06:31:38.806 UTC** llegó otro mensaje con **dos lecturas nuevas**,
  sin duplicados, conflictos ni bloqueos; integridad SQLite `ok`. Inicio mostró
  `selection_pending_b1` sin número, como exige la selección por recepción
  cuando el último mensaje trae varias lecturas y B1 sigue sin verificar.
  Se comprobaron recepción y conservación offline, incluida esta limitación
  visible de presentación; no se declara selección cronológica ya validada.
- **Red:** Wi-Fi `0`, datos de la suscripción activa (`mobile_data3`) `0`,
  Bluetooth `1`, modo avión `0`, `Active default network: none`. El ajuste
  global histórico `mobile_data=1` no refleja la suscripción desactivada. Antes
  del reinicio persistía un agente VPN con capacidades anteriores; después solo
  un agente celular IMS, sin capacidad `INTERNET`. No se confunde `VALIDATED`
  de IMS o de registros históricos con conectividad a Internet. Wi-Fi y datos
  se restauraron a `1` a las 06:32 UTC; Bluetooth permaneció `1` y modo avión `0`.
- **P5/B1:** tres lecturas en dos mensajes conservan timestamp del productor y
  recepción. Al interpretar el primero como segundos UTC, la primera tiene
  una diferencia de 3.340 ms; tras reiniciar, las dos del lote tienen
  diferencias de 304.806 y 4.806 ms, separadas por 300 segundos de productor.
  Es compatible con recuperación de una lectura anterior y una reciente,
  pero no acredita por sí solo la semántica del campo ni aprueba B1. No se
  cambió la selección por recepción ni sus etiquetas. Evidencia minimizada:
  `p4-before-reboot-counts.json` y `p4-after-reboot-counts.json` fuera del repo.
- **P6:** las dos pruebas instrumentadas de cálculo con datos sintéticos
  pasaron. En el recorrido normal del Pixel, los campos ausentes producen
  `profile.missing`, `glucose.manual.missing` e `iob.unknown`. No se creó un
  perfil ficticio persistente. El cálculo con perfil real confirmado sigue
  pendiente de disponer de ese perfil.

B1 necesita aprobación del informe por el propietario y sigue sin verificarse
en la aplicación. Los tiempos anteriores documentan observaciones técnicas,
sin establecer vigencia clínica ni tiempos de conmutación del reloj.

Evidencia local (logs y JSON minimizados, fuera del repositorio):
`%LOCALAPPDATA%\BolusAiNext\pixel-validation\20261012T054243Z`.
No se incluyen valores reales de glucosa ni identificadores de transmisor.

Ventana de ejecución del Pixel: 2026-10-12 05:42–06:41 UTC. Al terminar se
habilita de nuevo el receiver mediante el comando `run-as` autorizado; el
manifiesto mantiene su valor desactivado por defecto. No se guardan resultados
de cálculo ni se registran tratamientos. Las pruebas de migración v1/v2 → v3
pasaron con archivos sintéticos; P7 sobre un perfil v2 real con historial no se
puede acreditar con el estado sin perfil de este Pixel. Los pendientes B1/P6/P7
no impiden continuar el desarrollo autorizado desde la nube.
