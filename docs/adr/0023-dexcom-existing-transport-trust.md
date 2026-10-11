# ADR 0023: recepción Dexcom por permiso y conjunto aprobado, y presentación local de la última lectura

- Estado: propuesto. Propuesta única del primer hito Android (Fase 5). Solo D1
  está decidida por el propietario (2026-10-11). Siguen pendientes la política
  de recepción, sus riesgos residuales y las decisiones de presentación
- Fecha: 2026-10-11
- Fase: 5, recepción local compatible con el productor instalado y presentación
  sin cálculo
- Responsable de decisión: propietario
- Evidencia clínica: la parte I no aplica (no habilita cálculo). La parte II
  muestra por primera vez un valor de glucosa al usuario y contiene decisiones
  clínicas pendientes (B4 y C5)
- Alcance de esta entrega: documentación, sin cambios de código, del teléfono,
  de Dexcom ni de Legacy

Este ADR unifica dos borradores del 2026-10-11: la evaluación de recepción por
permiso de la PR #55 y la propuesta de presentación de la rama
`claude/bold-cannon-yy4lvm`. Separa dos preguntas que no se aprueban juntas:

- **Parte I — recepción técnica:** qué mensajes deja entrar Next al registro
  local y qué garantía real ofrece esa barrera.
- **Parte II — presentación y validez clínica:** cómo se interpreta y se muestra
  lo registrado. Aceptar la parte I no aprueba nada de la parte II.

## Contexto

El propietario confirma que la Dexcom modificada ya funciona y Bolus AI la usa.
Next debe aprovechar su transporte sin modificar, recompilar ni reinstalar
Dexcom o Legacy. La rectificación del ADR 0022 conserva este alcance. No se ha
realizado una nueva auditoría de Legacy ni inspeccionado el teléfono para este
ADR; los detalles del productor remiten al
[contrato observado](../contracts/modified-dexcom-g7-broadcast-observed-v1.md).

La política implementada en Next exige identidad compartida por Android, que
el envío observado no proporciona. Por eso **no existe ninguna obligación de
activar `setShareIdentityEnabled(true)`, ni de recompilar, archivar como
release reproducible o reinstalar Dexcom**. La ruta estricta implementada
(ADR 0005, 0006, 0019 y 0022) se conserva como está, con `Pending`, y sus
pruebas siguen vigentes; esta propuesta añade una política distinta y no la
presenta como autenticación por mensaje.

Desde los ADR 0018 a 0022, Next ya parsea el payload observado y guarda
observaciones en `dexcom-observations.db`, con el receiver desactivado (R1),
sin permiso del productor (R2) y con política `Pending`. Ninguna pantalla
presenta glucosa y `ReadOverview` devuelve `input.glucose.policy_not_approved`.

## Prerrequisitos del propietario (solo lectura)

| ID | Acción | Bloquea |
|---|---|---|
| L1 | Autorizar una lectura de solo lectura en el teléfono (`dumpsys package`, `pm list packages`, `apksigner verify --print-certs` sobre una copia de la APK) para obtener paquete, `versionCode`, firmantes y titulares efectivos de `com.dexcom.cgm.EXTERNAL_PERMISSION`, y el propietario y nivel del permiso. Sin cambiar permisos, instalaciones ni ajustes | anclas (I-2) y conjunto (I-1) |
| L2 | Revisar los valores obtenidos y aprobarlos como anclas de la instalación actual. El hash histórico del contrato (`6746ac78…`) no se importa como ancla aprobada | política `Approved` de la parte I |

## Parte I — Recepción técnica

### Propuesta y garantías exactas

1. Declarar en el receiver de Next
   `android:permission="com.dexcom.cgm.EXTERNAL_PERMISSION"`. Para emisores
   ordinarios, Android comprueba ese permiso antes de entregar el broadcast
   [S1]. Next también necesita solicitar y tener concedido el permiso para que
   el productor lo descubra como destinatario. Son dos controles distintos.
2. Antes de acceder a los extras de cada mensaje, obtener una lectura nueva de
   los titulares efectivos del permiso y compararla por igualdad con el conjunto
   aprobado. No usar solo permisos solicitados o declarados en manifiestos.
   Dexcom entra en ese conjunto si realmente tiene concedido el permiso;
   declararlo como propietario no demuestra que su UID lo tenga. Si no lo tiene,
   esta combinación no permite recibir del productor y se debe revisar en Next.
3. Comprobar en esa lectura `com.dexcom.g7`, su `versionCode` exacto y el conjunto
   completo de firmantes actuales contra las anclas aprobadas. Verificar también
   propietario y definición del permiso.
4. El conjunto candidato es Dexcom, Legacy y Next según sus concesiones reales;
   no es aún una lista aprobada. Fijar sus identidades, no solo nombres de
   paquete: se recomienda anclar también firmantes de Legacy y Next y declarar
   cómo se autorizan sus actualizaciones. Resolver asociaciones de UID compartido
   y usuario/perfil; no inferir que un paquete equivale siempre a un UID [S2].
5. Si cambia el conjunto, una ancla o la definición del permiso, o falla la
   comprobación, rechazar antes de leer/guardar el payload y mostrar la causa.
   Conservar un bloqueo durable hasta aprobación explícita del propietario,
   aunque luego vuelva el conjunto anterior o se reinicie Next. Un fallo al
   persistir ese bloqueo también impide recibir. La aprobación referencia la
   versión de política y las identidades revisadas; no incorpora apps nuevas
   automáticamente.

**Garantía condicionada:** con un sistema de permisos íntegro, enumeración
completa y conjunto estable durante las comprobaciones, se limita el emisor a
un conjunto aprobado de aplicaciones; no se demuestra que sea Dexcom. Sin esos
supuestos, lo comprobado es que el mensaje superó la barrera de permiso de
Android y que la lista observada al consultarla coincidía con la aprobada.
No hay una atribución atómica de ese mensaje a un miembro concreto del conjunto.
La comparación de APK/versiones/firmas identifica instalaciones, no mensajes.

### Visibilidad y ventana temporal

`getPackagesHoldingPermissions` devuelve titulares concedidos, pero no se debe
asumir que una consulta desde Next ve todo el dispositivo [S2]. En el código
AOSP Android 16 examinado, `addPackageHoldingPermissions` genera metadatos con
`generatePackageInfo`, que aplica `shouldFilterApplication` [S3]. Declarar solo
Dexcom/Legacy en `<queries>` no demuestra ausencia de otro titular oculto.

Evaluar y probar en la plataforma soportada la visibilidad necesaria, incluida
`QUERY_ALL_PACKAGES` si procede [S4]. No añadir ese permiso por este ADR propuesto.
Si no puede demostrarse cobertura del ámbito de entrega, la comparación falla
cerrada; no reducir silenciosamente la garantía a «apps conocidas». No extender
el resultado a otros usuarios/perfiles que no se hayan comprobado.

La enumeración, el permiso al entregar y las lecturas de versión no forman una
transacción común. Repetir la consulta por mensaje reduce la ventana, pero no la
elimina. Por ejemplo, una app obtiene permiso, envía y lo pierde antes de la
consulta de Next: la lista puede volver a coincidir. Es una inferencia del modelo
de comprobaciones separadas, no un ataque reproducido aquí. Una segunda lectura
puede detectar cambios, sin convertir la operación en atómica. Comprobar también
al iniciar y al abrir Diagnóstico permite mostrar cambios sin mensajes nuevos;
no se promete detección instantánea de cada concesión o revocación.

### Riesgos residuales para decisión del propietario

| Riesgo | Garantía que no ofrece esta combinación |
|---|---|
| Una app aprobada falsifica datos | Legacy, Next u otro miembro aprobado pueden satisfacer la barrera; no hay firma del payload ni autoría Dexcom por mensaje |
| Cambios transitorios de permisos o de instalación | La ventana anterior puede ocultar un titular que existía al entregar; no se vinculan atómicamente lista, versión y mensaje |
| Emisión desde `adb`/shell | No se afirma que shell siempre eluda el permiso ni que siempre quede bloqueado; probar la ruta y versión soportadas con fixtures. El acceso de depuración permite además alterar concesiones o instalaciones |
| Root, sistema comprometido o privilegios equivalentes | No se garantiza el conjunto cerrado frente a ellos. AOSP contiene excepciones para UID root/sistema en el control de componentes [S5] |
| Enumeración incompleta o UID/perfil ambiguo | Un resultado parcial no demuestra cierre del conjunto; bloquear, no aceptarlo como una lista completa |
| Cambio detectado sin un mensaje nuevo | El estado visible se actualiza al ejecutar comprobaciones; no hay garantía de vigilancia continua ni de aviso de mensajes que Android descarta antes de invocar Next |

Ninguno de estos riesgos queda aceptado por este borrador.

### Decisiones solicitadas de la parte I

Recomendación en negrita. Ninguna se aplica sin respuesta.

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| I-1 | Conjunto aprobado de titulares | Dexcom, Legacy y Next según L1; otro conjunto | **El que muestre L1**, revisado miembro a miembro. Si Dexcom no aparece como titular efectivo, la política no sirve y se vuelve a este ADR |
| I-2 | Anclas y reglas de actualización | (a) paquete, `versionCode` y firmantes de Dexcom, Legacy y Next; toda actualización de cualquiera genera bloqueo durable hasta aprobar; (b) anclar solo firmantes de Legacy y Next y aceptar sus versiones nuevas | **(a)**. Más fricción al actualizar, pero ningún cambio entra sin revisión. (b) es aceptable para Next si el propietario lo prefiere |
| I-3 | Visibilidad de paquetes | (a) `QUERY_ALL_PACKAGES` si la prueba en el Pixel demuestra que sin él la enumeración es parcial; (b) solo `<queries>` | **Decidir tras la prueba**. Con (b) la comparación debe fallar cerrada si no puede demostrar cobertura |
| I-4 | Riesgos residuales de la tabla anterior | Aceptarlos todos, aceptar algunos o rechazar la política | **Decisión exclusiva del propietario**. El agente no recomienda aceptar riesgos |
| I-5 | Dónde viven las anclas aprobadas | (a) constante en código de producción revisada por PR y comprobada por `verify.ps1` contra una tabla del contrato; (b) propiedad de Gradle local | **(a)**. Paquetes, versiones y digests no son secretos y quedan trazados por commit |
| I-6 | Concesión del permiso a Next (nivel `dangerous`) | (a) solicitud en tiempo de ejecución con un botón explícito y estado visible; (b) `adb shell pm grant` solo durante la validación | **(b) durante la validación y (a) al cerrar el hito**. Sin permiso, la UI muestra la causa y nunca «sin lecturas» |
| I-7 | Interruptor del receiver (R1 del ADR 0022) | (a) mantener activación por `adb` hasta terminar la validación real; (b) activado por defecto en cuanto haya política aprobada | **(a)**. El paso a (b) se propone en el informe de validación |
| I-8 | Convivencia con Legacy | Next solo recibe y registra en local, sin sync ni tratamientos; Legacy conserva la autoridad | **Confirmar**. No requiere código ni cambia nada en Legacy (AGENTS.md §2 y §7) |

## Parte II — Presentación y validez clínica (pendiente de aprobación)

Estas decisiones solo se aplican a mensajes que superen la parte I. Ninguna
habilita cálculo, IOB, tratamiento ni sync, y aceptar la parte I no las
aprueba. Mientras no se respondan, Next no presenta valores de glucosa.

### Interpretación del dato

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| B1 | Semántica de `timestamp` | (a) epoch UTC en segundos (el productor divide un valor interno entre 1000), aceptada solo tras comprobar en la validación real que la diferencia con la recepción es coherente; (b) no interpretarlo y mostrar solo la hora de recepción | **(a) con verificación**. Hasta superar la validación se presenta la hora de recepción, etiquetada como tal, y la hora de medida como «no verificada». El informe registra diferencias observadas, sin fijar umbrales clínicos. La verificación la aprueba el propietario sobre ese informe; el agente no la da por cumplida |
| B2 | `egvTxTime` | (a) conservar sin interpretar; (b) investigar semántica | **(a)**. No se presenta ni se usa |
| B3 | Unidad de `glucoseValue` | (a) mg/dL como afirmación versionada del productor (especificación `06f25370…`); (b) unidad desconocida y no mostrar valor | **(a)**, mostrando siempre «mg/dL». **Sin conversión** a mmol/L aunque el perfil esté en mmol/L: convertir es una regla pendiente |
| B4 | **Clínica** — valores extremos (D4 del ADR 0018 no aplica rango) | (a) mostrar el entero recibido con el estado «no aprobada para uso»; (b) no mostrar valores fuera de `1..400` y presentar «valor fuera del intervalo emitido por el productor» | **Decisión del propietario**. (b) usa un literal observado del productor como filtro de presentación, no como rango clínico; (a) no inventa nada pero puede enseñar un número sin sentido |
| B5 | Tendencia `trendArrow` | (a) no mostrarla en la vista principal y conservar el nombre literal en detalles; (b) traducirla a flechas | **(a)**. Su significado no está contratado |

### Qué se presenta y dónde

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| C1 | Pantallas | (a) Inicio y Bolo, solo lectura; (b) solo Inicio | **(a)**. Bolo sigue con el cálculo bloqueado. Diagnóstico mantiene F1 (solo recuentos) |
| C2 | Lectura a mostrar | (a) la de mayor `timestamp` del productor entre todas las observaciones, una vez verificada su semántica según B1; (b) la última recibida, identificada solo por su recepción | **(a)** tras verificar B1, porque el backfill puede llegar después. Hasta entonces se usa (b), sin afirmar que sea la medida más reciente. Si la lectura seleccionada tiene un conflicto registrado (E2), se muestra «conflicto» sin valor |
| C3 | Cambio de transmisor | (a) C2 abarca todos los transmisores y la huella abreviada aparece en detalles; (b) solo el último transmisor visto | **(a)**. No hay contrato de estabilidad de `transmitterId` |
| C4 | Timestamp futuro, tras verificar B1 | (a) cualquier `timestamp` posterior al instante de recepción del mismo mensaje es anomalía `future_timestamp`, sin valor ni antigüedad; (b) tolerancia de segundos | **(a)**. Emisor y receptor comparten reloj y el productor trunca a segundos. Se compara en segundos sin redondear a favor. Antes de verificar B1, la hora de medida permanece «no verificada» y no se compara como un instante interpretado |
| C5 | **Clínica** — vigencia | (a) mostrar la antigüedad («hace N min», reloj inyectado) con el estado «vigencia sin política aprobada»; nunca «actual»; (b) aprobar un umbral (la auditoría documentada de Legacy en `f5417721…`, no revalidada aquí, observa 10 min al resolver la fuente y 15 min para marcar histórico) | **(a)** en este hito. La antigüedad de la medida requiere B1 verificada; hasta entonces solo se indica tiempo desde la recepción, etiquetado como tal. Esto acredita recepción y presentación técnica; no cumple por sí solo el criterio de «lectura válida» ni aprueba su vigencia o uso para calcular |
| C6 | Estados sin lectura | Receiver desactivado, permiso de Next ausente, política pendiente, bloqueo por cambio del conjunto o de anclas (parte I, punto 5), ninguna lectura registrada, registro ilegible, último mensaje rechazado | **Adoptar** esos siete estados con códigos estables `dexcom_presentation.*`. Nunca un valor anterior presentado como nuevo |
| C7 | Contador duradero de fallos del receiver (pendiente de R10/R11) | (a) no ahora; (b) añadirlo | **(a)**. No es necesario para el hito |

La procedencia mostrada dice «recibida mediante permiso de un emisor del
conjunto aprobado», nunca «enviada por Dexcom».

### Validación

| ID | Pregunta | Opciones | Recomendación |
|---|---|---|---|
| D1 | Prueba sin red | Wi-Fi y datos móviles apagados, Bluetooth activo y sin activar modo avión; comprobar con `dumpsys connectivity` que no hay red validada y que el sensor sigue entregando lecturas. Repetir tras reiniciar y comprobar de nuevo esos estados | **Decidida por el propietario — 2026-10-11**. Indica que activar modo avión desconecta Bluetooth y el sensor G7 de Dexcom. No interrumpir esa conexión para realizar la prueba. Se registra la excepción específica en AGENTS.md §9; se mantiene la validación offline |
| D2 | Datos en la evidencia | Recuentos, diferencias de tiempo y códigos; nunca valores de glucosa ni `transmitterId` en claro | **Adoptar** |

## Documentos afectados al aceptar

Cada uno lleva ya una nota de rectificación: el opt-in de identidad y la
release reproducible dejan de ser trabajo pendiente del productor, y la
recepción real sigue bloqueada mientras este ADR siga propuesto. Al aceptarlo,
la entrega los actualiza para distinguir la ruta estricta (que se conserva) de
la política por permiso:

- `README.md` (estado: «hasta fijar una release reproducible, autenticar el emisor»);
- `docs/contracts/android-local-glucose-boundary-v1.md` y
  `docs/adr/0003-android-local-glucose-boundary.md` (puertas de identidad);
- `docs/adr/0019-dexcom-g7-reception-composition.md` (pendientes de release y opt-in);
- `docs/safety/INITIAL_RISK_REGISTER.md`, R-010 (pasa a describir la
  garantía condicionada y los riesgos aceptados o rechazados en I-4);
- `docs/contracts/modified-dexcom-g7-broadcast-observed-v1.md` (lista de
  pruebas de la ruta estricta frente a las de la política por permiso).

## Plan de implementación tras aprobar

Una entrega funcional, con sus pruebas en cada paso:

1. **Evidencia por permiso.** Un tipo distinto de `Authenticated`: no fabricar
   UID/paquete ni pasar Dexcom configurado como `authenticatedPackage` para
   satisfacer el parser. La afirmación de paquete del payload queda como no
   verificada. Adaptar parser, composición y procedencia persistida, con
   migración de `dexcom-observations.db` si el esquema la necesita; no
   reetiquetar mensajes históricos.
2. **Comprobación del conjunto y bloqueo durable** (parte I, puntos 2 a 5).
3. **Manifiesto:** permiso del receiver, `uses-permission` y, si I-3 lo exige,
   visibilidad. Actualizar las comprobaciones de `verify.ps1` que fijan el
   manifiesto y `Pending`, sin rebajar ninguna otra.
4. **Contrato del transporte v2** con B1 a B5.
5. **Caso de uso común puro** `PresentLatestDexcomReading(log, clock)` con C2 a
   C6, lectura de solo lectura del registro y modelo Android fuera del hilo
   principal.
6. **UI** en Inicio y Bolo. `ReadOverview`, el motor y
   `LocalGlucoseSourceResult` siguen sin glucosa disponible: `verify.ps1`
   seguirá exigiendo que no dependan del registro ni de la presentación.
7. **Validación real** según D1 y D2.

Rollback: receiver desactivado y política pendiente, sin borrar observaciones.
iOS no consume este broadcast: conserva el puerto compartido y requiere otra
integración de origen.

## Pruebas de aceptación (propuestas, no ejecutadas)

Recepción por permiso:

- Fixture sin permiso: Android no entrega, incluso con acción/extras copiados;
  fixture con permiso: entrega sin identity sharing.
- Conjunto exacto y anclas coincidentes: se acepta. Titular adicional, ausente,
  firma o versión cambiada y definición/propietario de permiso incorrectos:
  bloqueo antes de leer extras.
- App sintética oculta que adquiere el permiso, usuario/perfil y UID compartido:
  demostrar cobertura o bloqueo por imposibilidad de comprobarla.
- Cambio entre comprobaciones y concesión/envío/revocación transitorios:
  documentar la ventana residual, sin afirmar que el test la elimina.
- Miembro aprobado que falsifica: documentar que la política no distingue su
  autoría; fixtures aislados, nunca emisiones Dexcom/Legacy en producción.
- Fallo de consulta o de persistencia del bloqueo, reinicio, restauración del
  conjunto y aprobación nueva: sin desbloqueo automático ni acceso al payload
  rechazado; estado visible con causa específica.
- Ruta `adb`/shell en entorno aislado y permiso empaquetado del receiver;
  no obtener root ni modificar permisos de Dexcom/Legacy para probarlos.
- Las pruebas de la ruta estricta existente siguen pasando sin cambios.

Presentación:

- Antes de verificar B1: sin lecturas, conflicto, varios transmisores,
  duplicados y registro ilegible; selección por recepción (C2 b), tiempo desde
  la recepción con reloj inyectado y hora de medida siempre «no verificada»,
  sin ordenar, comparar ni calcular antigüedad con el `timestamp` del productor.
- Tras verificar B1: timestamp futuro (C4), reordenación y backfill (C2 a) y
  antigüedad de la medida con reloj inyectado.
- Cada estado de C6 visible con su causa; ningún valor antiguo presentado como
  nuevo.
- Ausencia de uso clínico en todos los estados: el motor y `ReadOverview` no
  reciben glucosa.
- Recepción y presentación offline tras reinicio según D1, sin cortar la
  recepción del sensor.

## Alternativas consideradas

- **Pedir identity sharing al productor.** Descartada por el propietario:
  Dexcom no se modifica, recompila ni reinstala.
- **Confiar en el extra `packageName`.** Descartada: es controlable por
  cualquier emisor.
- **Implementar la presentación con supuestos y aprobar después.** Descartada:
  unidad, timestamp y vigencia son lo que AGENTS.md §3 prohíbe suponer.
- **Dexcom Web API mientras tanto.** Fuera de alcance: contingencia con
  retraso, no cumple el hito local.

## Decisiones pendientes

Parte I: L1, L2 e I-1 a I-8, con I-4 como aceptación explícita de riesgos.
Parte II: B1 a B5, C1 a C7 y D2, con B4 y C5 reservadas al propietario.
D1 ya está decidida: prueba sin Internet con Bluetooth activo y sin modo avión.
Esta decisión no acepta riesgos ni aprueba otras partes de la propuesta.
Hasta aprobar e implementar la entrega, el receiver sigue desactivado, la
política en `Pending` y no hay tratamiento ni cálculo desde Next.

## Fuentes y condiciones para revisar

Fuentes primarias consultadas el 2026-10-11; no prueban todavía el comportamiento
de la compilación del teléfono:

- [S1: Android, restricción de broadcasts por permiso](https://developer.android.com/training/permissions/restrict-interactions#restrict-interactions-with-your-apps-broadcast-receivers).
- [S2: PackageManager, titulares de permisos y paquetes por UID](https://developer.android.com/reference/android/content/pm/PackageManager#getPackagesHoldingPermissions(java.lang.String[],%20int)).
- [S3: AOSP Android 16, ComputerEngine.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/ComputerEngine.java), blob observado `38ac0473d1a326ee7421933d61e0b4eee74f8ac7`, símbolos `getPackagesHoldingPermissions`, `addPackageHoldingPermissions`, `generatePackageInfo`.
- [S4: Android, declaración de visibilidad](https://developer.android.com/training/package-visibility/declaring#all-apps).
- [S5: AOSP, excepciones de checkComponentPermission](https://android.googlesource.com/platform/frameworks/base/+/f8754ac2127f8adf40bd4731f39b16340acdb2f7/core/java/android/app/ActivityManager.java).

Revisar ante cambio de Android/OEM, usuario/perfil, permiso, miembros o anclas,
transporte, garantía observada en pruebas, evidencia de la validación real que
contradiga B1, B3 o C4, o propuesta de uso clínico. Resolver hallazgos en este
ADR y su entrega; no convertirlos en cambios de Dexcom.
