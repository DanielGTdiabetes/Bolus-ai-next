# ADR 0023: recepción Dexcom por permiso y conjunto aprobado, y presentación local de la última lectura

- Estado: aceptado para implementar recepción y presentación técnica, sin
  cálculo ni tratamientos. Aprobación explícita del propietario: 2026-10-12
- Fecha: 2026-10-11
- Fase: 5, recepción local compatible con el productor instalado y presentación
  sin cálculo
- Responsable de decisión: propietario
- Evidencia clínica: B4 y C5 aprobadas exclusivamente para presentación. No se
  aprueba vigencia clínica, cálculo ni tratamiento
- Alcance de la entrega de aceptación: documentación, sin cambios de código, del
  teléfono, de Dexcom ni de Legacy
- Implementación: pasos 1 a 6 del plan en la entrega del 2026-10-12
  ([validación](../validation/dexcom-permission-reception-and-test-calculation-2026-10-12.md)).
  I-3 y recepción real offline antes y después de reiniciar ejecutadas en el
  [Pixel](../validation/pixel-dexcom-and-calculation-2026-10-12.md).
  B1 tiene evidencia preliminar y sigue pendiente de aprobación del informe

Este ADR unifica dos borradores del 2026-10-11: la evaluación de recepción por
permiso de la PR #55 y la propuesta de presentación de la rama
`claude/bold-cannon-yy4lvm`. Separa dos preguntas que no se aprueban juntas:

- **Parte I — recepción técnica:** qué mensajes deja entrar Next al registro
  local y qué garantía real ofrece esa barrera.
- **Parte II — presentación y validez clínica:** cómo se interpreta y se muestra
  lo registrado. La aceptación de ambas partes tiene el alcance limitado del
  registro de aprobación siguiente.

## Registro de aprobación del propietario — 2026-10-12

El propietario respondió «sí» a la propuesta agrupada que incluía las opciones
siguientes y la aceptación de riesgos para recepción y presentación técnica,
sin uso para calcular. Esta es la selección vigente; no volver a solicitarla.

| Decisión | Aprobación y límite |
|---|---|
| L2, I-1 | Identidades observadas en L1 y conjunto Dexcom, Legacy, reloj y Next. La pertenencia al conjunto no atribuye cada mensaje a Dexcom |
| I-2 | Opción **b**: paquete, versión 4537 y firmantes exactos para Dexcom; paquete y firmantes exactos para Legacy y reloj, permitiendo sus versiones nuevas. Next sin ancla de versión. No bloquear por el mero cambio de versión de Legacy o reloj; los cambios del conjunto, firmantes o definición del permiso mantienen el bloqueo durable |
| I-3 | `QUERY_ALL_PACKAGES` autorizado solo si la prueba en el Pixel demuestra que es necesario para enumerar todos los titulares del ámbito. Registrar esa evidencia; si no puede demostrarse cobertura, bloquear |
| I-4 | Riesgos residuales descritos en este ADR aceptados únicamente para recepción y presentación técnica: no hay identidad individual por mensaje, una app admitida puede falsificar datos, existen ventanas transitorias y límites ante accesos privilegiados, además de las limitaciones de las anclas y visibilidad. No se acepta su uso para calcular |
| I-5 a I-8 | Recomendaciones aprobadas: anclas en código y PR, permiso de Next por `adb` durante validación y botón al cerrar el hito, receiver activable solo por `adb` hasta validar, Legacy conserva autoridad y Next solo registra localmente |
| B1 a B3, B5 | Recomendaciones aprobadas: B1 sigue condicionado a evidencia real y aprobación del informe por el propietario; hasta entonces ordenar y contar tiempo por recepción, con hora de medida «no verificada». Conservar `egvTxTime` sin usar; mg/dL sin conversión; sin flecha principal |
| B4 | Opción **b**: fuera de `1..400`, aviso sin número. Es filtro de presentación basado en el productor observado, no rango clínico ni aprobación del cálculo |
| C1 a C4, C6, C7 | Recomendaciones aprobadas: Inicio y Bolo en solo lectura; selección, conflictos y anomalías sujetos a B1; todos los transmisores; siete causas visibles sin lectura y sin contador de fallos en este hito |
| C5 | Opción **a**: mostrar antigüedad y «vigencia sin política aprobada», nunca «actual». Hasta verificar B1, solo tiempo desde recepción, etiquetado como tal. No se aprueba umbral de vigencia |
| D1, D2 | D1 ya decidida el 2026-10-11: sin Internet, Bluetooth activo y sin modo avión. D2 aprobado: evidencia sin valores de glucosa ni transmisor en claro |

La aprobación permite preparar la implementación completa de Next. No confirma
que esté implementada ni probada, no modifica Dexcom, Legacy o el reloj, y no
convierte el inventario en validación funcional de recepción. B1, la prueba de
visibilidad y la validación real permanecen pendientes de ejecución. Las
restricciones de la autorización L1 describen aquel inventario; este cambio
documental no ejecuta acciones en el teléfono.

**Ampliación posterior del propietario — 2026-10-12:** se autoriza desarrollar
y ejecutar cálculos de prueba, también con lecturas reales, según AGENTS.md §3.
Esta autorización separada no cambia las garantías de recepción ni valida
clínicamente sus lecturas; los resultados no se utilizan para tratamiento.
Las menciones a cálculo bloqueado en este ADR describen la ruta terapéutica y
el código actual, y no impiden implementar el recorrido de prueba autorizado.

## Contexto

El propietario confirma que la Dexcom modificada ya funciona y Bolus AI la usa.
Next debe aprovechar su transporte sin modificar, recompilar ni reinstalar
Dexcom o Legacy. La rectificación del ADR 0022 conserva este alcance. No se ha
realizado una nueva auditoría de Legacy; los detalles del productor remiten al
[contrato observado](../contracts/modified-dexcom-g7-broadcast-observed-v1.md).
El teléfono se inspeccionó solo con el inventario de solo lectura L1
([validación](../validation/dexcom-installed-inventory-2026-10-11.md)).

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
| L1 | Inventario de solo lectura en el teléfono: paquete, UID, `versionCode`, firmantes y titulares efectivos de `com.dexcom.cgm.EXTERNAL_PERMISSION`, y propietario y nivel del permiso. **Autorizado y hecho el 2026-10-11** sin cambiar permisos, instalaciones ni ajustes ([validación](../validation/dexcom-installed-inventory-2026-10-11.md)) | — |
| L2 | **Aprobada el 2026-10-12** con las anclas de la sección «Valores observados y anclas aprobadas» y las reglas I-2b. El hash histórico del contrato (`6746ac78…`) coincide con la APK instalada, pero es evidencia de inventario, no un ancla binaria añadida | implementar la política aprobada |

## Parte I — Recepción técnica

### Decisión y garantías exactas

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
4. El conjunto aprobado es Dexcom, Legacy, reloj y Next, conforme a I-1 e
   I-2. Comprobar los firmantes de Legacy y reloj, sin congelar sus versiones.
   Resolver asociaciones actuales de UID compartido y usuario/perfil; no
   inferir que un paquete equivale siempre a un UID [S2].
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
`QUERY_ALL_PACKAGES` si procede [S4]. Su incorporación está autorizada solo
cuando la prueba de I-3 demuestre que es necesaria.
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

### Riesgos residuales aceptados para recepción y presentación técnica

| Riesgo | Garantía que no ofrece esta combinación |
|---|---|
| Una app aprobada falsifica datos | Legacy, Next u otro miembro aprobado pueden satisfacer la barrera; no hay firma del payload ni autoría Dexcom por mensaje |
| Cambios transitorios de permisos o de instalación | La ventana anterior puede ocultar un titular que existía al entregar; no se vinculan atómicamente lista, versión y mensaje |
| Emisión desde `adb`/shell | No se afirma que shell siempre eluda el permiso ni que siempre quede bloqueado; probar la ruta y versión soportadas con fixtures. El acceso de depuración permite además alterar concesiones o instalaciones |
| Root, sistema comprometido o privilegios equivalentes | No se garantiza el conjunto cerrado frente a ellos. AOSP contiene excepciones para UID root/sistema en el control de componentes [S5] |
| Enumeración incompleta o UID/perfil ambiguo | Un resultado parcial no demuestra cierre del conjunto; bloquear, no aceptarlo como una lista completa |
| Cambio detectado sin un mensaje nuevo | El estado visible se actualiza al ejecutar comprobaciones; no hay garantía de vigilancia continua ni de aviso de mensajes que Android descarta antes de invocar Next |
| Clave de firma compartida por apps del propietario | Legacy, Next y `com.wtachtsugar` comparten certificado (`1d1f25bf…`, inventario L1). Una app nueva que obtenga el permiso cambia el conjunto y bloquea. I-2b permite actualizaciones de los paquetes Legacy y reloj con el firmante aprobado, sin revisar cada versión. Las anclas identifican paquete/firmante y, para Dexcom, versión; no prueban igualdad binaria ni protegen frente a una sustitución que conserve esos valores y use la clave admitida. No se sabe qué almacén es ni quién lo custodia |
| Identidad del emisor durante la contingencia del reloj | El propietario indica que el reloj solo emite cuando falla la app Dexcom: es un respaldo, no dos fuentes previstas emitiendo en paralelo. Si ese respaldo usa la misma acción y forma, la política por permiso no distingue al emisor. La contingencia no convierte esa evidencia en identidad Dexcom. El inventario no comprobó la emisión del reloj ni el cambio de fuente. Si llegan repeticiones, el ADR 0020 solo las clasifica dentro de una misma clave (transmisor y `timestamp`): contenido idéntico es duplicado; contenido distinto es conflicto E2, mostrado sin valor según C2. Todo mensaje aceptado por esta política se presenta como «emisor del conjunto aprobado» |
| Origen no acreditado por el campo instalador | Los cuatro paquetes tienen `installerPackageName=null`; ese campo no acredita su origen ni una garantía de tienda. La confianza de esta política descansa en las anclas aprobadas |

El propietario acepta estos riesgos con el alcance del registro anterior:
recepción y presentación técnica, sin cálculo ni tratamientos. La aceptación
no elimina controles ni da por realizadas las pruebas.

### Valores observados y anclas aprobadas

Valores del inventario L1 del 2026-10-11 (Android 17, API 37, build
`CP3A.260905.009`, usuario único `0`). Son la base de L2, I-1 e I-2,
aprobadas el 2026-10-12 con las reglas de actualización I-2b.

**Definición del permiso.** Declarante único `com.dexcom.g7` (UID 10451),
protección `dangerous`. Comprobarla en cada lectura: un segundo
declarante, otro propietario u otro nivel de protección bloquea.

**Titulares efectivos hoy:** `com.dexcom.g7`, `org.bolusai.companion` y
`com.wtachtsugar`, los tres por concesión runtime en el usuario 0. Next no
solicita el permiso (R2 del ADR 0022); al concedérselo (I-6) pasaría a ser el
cuarto titular.

**Función de `com.wtachtsugar`, confirmada por el propietario — 2026-10-11.**
Es la app del reloj que utiliza para ver la glucosa de Dexcom G7. Según el
propietario, también permite conectar el sensor directamente sin la app
Dexcom, y está previsto que retransmita glucosa hacia Next. El propietario
precisa que el reloj solo emite cuando falla la app Dexcom: su papel es de
respaldo, sin emisión simultánea prevista desde ambas apps. Su identidad y la
necesidad de conservar el permiso quedan aclaradas; no tratarla como una app
desconocida ni proponer retirarle el permiso para completar este hito.
Estas capacidades proceden de la declaración del propietario, no de una
prueba funcional del inventario. La aceptación de L2 y de los riesgos I-4
procede de la aprobación explícita del 2026-10-12, no de esta identificación.

La futura retransmisión desde el reloj es una integración prevista, pendiente
de contrato de payload, procedencia y validación. No atribuir sus mensajes a
Dexcom por tener el permiso, ni asumir que implementa el transporte Dexcom
observado. Puede existir un origen directo del sensor mediante la app del
reloj; Next debe conservar esa distinción y el papel de contingencia indicado
por el propietario. La definición verificable de fallo de Dexcom y del cambio
de fuente queda para ese contrato; no inventar tiempos, umbrales ni mecanismos
de detección. Este trabajo futuro no bloquea el
hito actual con la Dexcom instalada ni autoriza modificar la app del reloj.

| Miembro aprobado | Paquete | UID observado | `versionCode` observado | SHA-256 del firmante | Ancla aprobada |
|---|---|---|---|---|---|
| Dexcom (productor) | `com.dexcom.g7` | 10451 | 4537 | `d30e0d3f08b70cedbc0804c96f2ec4599f243b5e149ef73a601757ad94c74145` | paquete, versión y firmante exactos. Además declara el permiso |
| Legacy | `org.bolusai.companion` | 10450 | 13 | `1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d` | paquete y firmante exactos; versiones nuevas permitidas |
| App del reloj | `com.wtachtsugar` | 10350 | 3 | `1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d` | paquete y firmante exactos; versiones nuevas permitidas |
| Next (receptor) | `org.bolusai.next` | el propio (`Process.myUid()`) | sin ancla | se registra, no se ancla | Next se identifica a sí mismo. Anclar su versión bloquearía cada actualización de Next sin aportar defensa: si otra app ocupara ese paquete, sería ella quien ejecuta la comprobación |

Los UID y versiones de Legacy/reloj de la tabla son datos del inventario,
no anclas históricas adicionales a las aprobadas en I-2b. La comprobación
resuelve los UID actuales y su ámbito; no promete detectar toda reinstalación.
Un cambio de las anclas aprobadas, del conjunto de titulares o de la definición
del permiso bloquea de forma durable hasta aprobación nueva (punto 5).

### Opciones de la parte I y selección vigente

Las opciones se conservan como contexto. La selección vigente es la del
registro de aprobación, en particular I-2b frente a la recomendación inicial.

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| I-1 | Conjunto aprobado de titulares | (a) Dexcom, Legacy, app del reloj `com.wtachtsugar` y Next; (b) otro conjunto autorizado expresamente por el propietario; (c) no aprobar la política | **Aprobada (a)** el 2026-10-12, con los límites y riesgos del registro de aprobación |
| I-2 | Anclas y reglas de actualización | (a) congelar versiones de Dexcom, Legacy y reloj; (b) para Legacy y `com.wtachtsugar`, aceptar versiones nuevas con paquete y firmantes aprobados | **Aprobada (b)** el 2026-10-12. Dexcom mantiene versión exacta; Legacy y reloj no bloquean por actualizar versión. Next sin ancla de versión |
| I-3 | Visibilidad de paquetes | (a) `QUERY_ALL_PACKAGES` si la prueba en el Pixel demuestra que sin él la enumeración es parcial; (b) solo `<queries>` | **Autorización condicional aprobada** el 2026-10-12: añadirlo solo si la prueba demuestra que hace falta; sin cobertura demostrada, bloquear |
| I-4 | Riesgos residuales de la tabla anterior | Aceptarlos todos, aceptar algunos o rechazar la política | **Aceptados por el propietario** el 2026-10-12 solo para recepción y presentación técnica, sin cálculo ni tratamientos |
| I-5 | Dónde viven las anclas aprobadas | (a) constante en código de producción revisada por PR y comprobada por `verify.ps1` contra una tabla del contrato; (b) propiedad de Gradle local | **(a)**. Paquetes, versiones y digests no son secretos y quedan trazados por commit |
| I-6 | Concesión del permiso a Next (nivel `dangerous`) | (a) solicitud en tiempo de ejecución con un botón explícito y estado visible; (b) `adb shell pm grant` solo durante la validación | **(b) durante la validación y (a) al cerrar el hito**. Sin permiso, la UI muestra la causa y nunca «sin lecturas» |
| I-7 | Interruptor del receiver (R1 del ADR 0022) | (a) mantener activación por `adb` hasta terminar la validación real; (b) activado por defecto en cuanto haya política aprobada | **(a)**. El paso a (b) se propone en el informe de validación |
| I-8 | Convivencia con Legacy | Next solo recibe y registra en local, sin sync ni tratamientos; Legacy conserva la autoridad | **Aprobada** el 2026-10-12. No cambia nada en Legacy (AGENTS.md §2 y §7) |

## Parte II — Presentación técnica aprobada y validez clínica pendiente

Estas decisiones solo se aplican a mensajes que superen la parte I. Ninguna
habilita cálculo, IOB, tratamiento ni sync. Las opciones de presentación están
aprobadas según el registro; la semántica de B1 y la recepción real requieren
su validación. El código actual aún no presenta valores de glucosa.

### Interpretación del dato

| ID | Pregunta | Opciones | Recomendación y motivo |
|---|---|---|---|
| B1 | Semántica de `timestamp` | (a) epoch UTC en segundos (el productor divide un valor interno entre 1000), aceptada solo tras comprobar en la validación real que la diferencia con la recepción es coherente; (b) no interpretarlo y mostrar solo la hora de recepción | **(a) con verificación**. Hasta superar la validación se presenta la hora de recepción, etiquetada como tal, y la hora de medida como «no verificada». El informe registra diferencias observadas, sin fijar umbrales clínicos. La verificación la aprueba el propietario sobre ese informe; el agente no la da por cumplida |
| B2 | `egvTxTime` | (a) conservar sin interpretar; (b) investigar semántica | **(a)**. No se presenta ni se usa |
| B3 | Unidad de `glucoseValue` | (a) mg/dL como afirmación versionada del productor (especificación `06f25370…`); (b) unidad desconocida y no mostrar valor | **(a)**, mostrando siempre «mg/dL». **Sin conversión** a mmol/L aunque el perfil esté en mmol/L: convertir es una regla pendiente |
| B4 | **Clínica** — valores extremos (D4 del ADR 0018 no aplica rango) | (a) mostrar el entero recibido con el estado «no aprobada para uso»; (b) no mostrar valores fuera de `1..400` y presentar «valor fuera del intervalo emitido por el productor» | **Aprobada (b)** el 2026-10-12, como filtro de presentación, no rango clínico |
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
| D2 | Datos en la evidencia | Recuentos, diferencias de tiempo y códigos; nunca valores de glucosa ni `transmitterId` en claro | **Aprobada** el 2026-10-12 |

## Documentos afectados por la aceptación

Cada uno lleva ya una nota de rectificación: el opt-in de identidad y la
release reproducible dejan de ser trabajo pendiente del productor, y la
recepción real sigue bloqueada hasta implementar y validar la política
aceptada. La entrega actualiza esos contratos para distinguir la ruta estricta
(que se conserva) de la política por permiso:

- `README.md` (estado: «hasta fijar una release reproducible, autenticar el emisor»);
- `docs/contracts/android-local-glucose-boundary-v1.md` y
  `docs/adr/0003-android-local-glucose-boundary.md` (puertas de identidad);
- `docs/adr/0019-dexcom-g7-reception-composition.md` (pendientes de release y opt-in);
- `docs/safety/INITIAL_RISK_REGISTER.md`, R-010 (pasa a describir la
  garantía condicionada y los riesgos aceptados o rechazados en I-4);
- `docs/contracts/modified-dexcom-g7-broadcast-observed-v1.md` (lista de
  pruebas de la ruta estricta frente a las de la política por permiso).

## Plan de implementación autorizado

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
  firma cambiada, versión Dexcom distinta y definición/propietario de permiso
  incorrectos: bloqueo antes de leer extras. Una versión nueva de Legacy o
  reloj con paquete y firmantes aprobados se acepta según I-2b.
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

## Ejecución y validación pendientes

L1 está hecho y L2, I-1 a I-8, B1 a B5, C1 a C7 y D2 están aprobados con las
opciones y condiciones del registro del 2026-10-12. D1 ya estaba decidida.
No quedan esas preguntas pendientes del propietario. Implementación, I-3 y
recepción real offline antes y después de reiniciar constan en la
[validación del Pixel](../validation/pixel-dexcom-and-calculation-2026-10-12.md).
Queda aprobar el informe B1; hasta entonces la hora de medida sigue no
verificada. No hay umbral clínico de vigencia ni autoridad de tratamiento.
El cálculo de prueba tiene su autorización separada (ADR 0024).

## Estado de implementación — 2026-10-12

Los pasos 1 a 6 están implementados y probados en JVM y lint. Consecuencias
técnicas, sin nuevas decisiones clínicas:

- La visibilidad se comprueba antes que el conjunto. Sin `QUERY_ALL_PACKAGES` en
  Android 11 o posterior, la puerta se cierra como `visibility_unproven`, que no
  es durable. I-3 demostró en el Pixel que sin él se ocultaban Legacy y reloj;
  ahora se declara y la puerta abre con los cuatro titulares aprobados.
- `dexcom-observations.db` pasa a v2. Solo se migra un v1 exacto y vacío; un v1
  con filas queda intacto como `unsupported_schema`. Los bloqueos durables viven
  en una tabla append-only y se aplican por versión de política. Solo una
  versión nueva revisada por PR los levanta.
- Antes de B1, un último mensaje con varias lecturas o que solo repite una ya
  registrada no identifica una lectura única: se presenta
  `dexcom_presentation.selection_pending_b1` sin valor.
- La ruta estricta se conserva para una política aprobada inyectada. En
  producción, con `Pending`, el receiver usa la ruta por permiso.

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
