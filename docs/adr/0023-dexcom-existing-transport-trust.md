# ADR 0023: recepción Dexcom por permiso y conjunto aprobado de aplicaciones

- Estado: propuesto; pendiente de decisión y aceptación de riesgos del propietario
- Fecha: 2026-10-11
- Fase: 5, recepción local compatible con el productor instalado
- Responsable de decisión: propietario
- Evidencia clínica: no aplica a la propuesta técnica; no habilita cálculo
- Alcance de esta entrega: documentación, sin cambios de código ni del teléfono

## Contexto

El propietario confirma que la Dexcom modificada ya funciona y Bolus AI la usa.
Next debe aprovechar su transporte sin modificar, recompilar ni reinstalar
Dexcom o Legacy. La rectificación del ADR 0022 conserva este alcance. No se ha
realizado una nueva auditoría de Legacy ni inspeccionado el teléfono para este
ADR; los detalles del productor remiten al
[contrato observado](../contracts/modified-dexcom-g7-broadcast-observed-v1.md).

La política implementada en Next exige identidad compartida por Android, que
el envío observado no proporciona. El propietario pide evaluar conjuntamente
permiso obligatorio para el emisor, conjunto exacto de titulares aprobado y
anclas de versión/firmantes de la Dexcom instalada. No ha aceptado todavía los
riesgos ni autorizado implementar esta política.

## Propuesta y garantías exactas

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
   propietario y definición del permiso. No importar el hash histórico del
   contrato como una ancla aprobada de la instalación actual.
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

## Riesgos residuales para decisión del propietario

| Riesgo | Garantía que no ofrece esta combinación |
|---|---|
| Una app aprobada falsifica datos | Legacy, Next u otro miembro aprobado pueden satisfacer la barrera; no hay firma del payload ni autoría Dexcom por mensaje |
| Cambios transitorios de permisos o de instalación | La ventana anterior puede ocultar un titular que existía al entregar; no se vinculan atómicamente lista, versión y mensaje |
| Emisión desde `adb`/shell | No se afirma que shell siempre eluda el permiso ni que siempre quede bloqueado; probar la ruta y versión soportadas con fixtures. El acceso de depuración permite además alterar concesiones o instalaciones |
| Root, sistema comprometido o privilegios equivalentes | No se garantiza el conjunto cerrado frente a ellos. AOSP contiene excepciones para UID root/sistema en el control de componentes [S5] |
| Enumeración incompleta o UID/perfil ambiguo | Un resultado parcial no demuestra cierre del conjunto; bloquear, no aceptarlo como una lista completa |
| Cambio detectado sin un mensaje nuevo | El estado visible se actualiza al ejecutar comprobaciones; no hay garantía de vigilancia continua ni de aviso de mensajes que Android descarta antes de invocar Next |

Ninguno de estos riesgos queda aceptado por este borrador. Aceptar recepción
técnica tampoco aprueba unidad, timestamps, vigencia, tendencia, datos para
calcular, registro de tratamiento ni autoridad de Next.

## Implementación posterior y pruebas de aceptación

Si el propietario aprueba la política, implementar y probar el recorrido como
una entrega funcional. Usar un tipo de evidencia distinto al `Authenticated`
por identidad del emisor: no fabricar UID/paquete ni pasar Dexcom configurado
como `authenticatedPackage` para satisfacer el parser actual. Mantener la
afirmación de paquete del payload como no verificada. Adaptar parser, composición
y procedencia persistida para conservar esa diferencia, con migración si el
esquema la necesita; no reetiquetar mensajes históricos. Las lecturas siguen
`NOT_APPROVED_FOR_USE` y cálculo/tratamiento permanecen bloqueados.

Pruebas requeridas para esa implementación, todavía no ejecutadas:

- Fixture sin permiso: Android no entrega, incluso con acción/extras copiados;
  fixture con permiso: entrega sin identity sharing.
- Conjunto exacto y anclas coincidentes; titular adicional, ausente, firma o
  versión cambiada y definición/propietario de permiso incorrectos: bloqueo.
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
- Procedencia sin identidad atribuida, duplicados, conflictos y persistencia
  offline; ausencia de uso clínico en todos los estados. No cortar Bluetooth.

La implementación debe actualizar las comprobaciones específicas de
`verify.ps1` que fijan el manifiesto y `Pending`, manteniendo protecciones y
pruebas; este borrador no modifica ni desactiva ninguna. Rollback: receiver
desactivado y política pendiente, sin borrar observaciones. iOS no consume este
broadcast: conserva el puerto compartido y requiere otra integración de origen.

## Decisiones pendientes

El propietario debe decidir conjuntamente el conjunto real y sus anclas/reglas
de actualización, el ámbito y mecanismo de visibilidad y la aceptación explícita
de los riesgos anteriores. La evidencia en dispositivo de concesiones y versión
queda pendiente de lectura autorizada. No se sustituye ninguna de estas
decisiones por una suposición. Hasta aprobar e implementar la entrega, continúa
el receiver desactivado, política `Pending` y ningún tratamiento desde Next.

## Fuentes y condiciones para revisar

Fuentes primarias consultadas el 2026-10-11; no prueban todavía el comportamiento
de la compilación del teléfono:

- [S1: Android, restricción de broadcasts por permiso](https://developer.android.com/training/permissions/restrict-interactions#restrict-interactions-with-your-apps-broadcast-receivers).
- [S2: PackageManager, titulares de permisos y paquetes por UID](https://developer.android.com/reference/android/content/pm/PackageManager#getPackagesHoldingPermissions(java.lang.String[],%20int)).
- [S3: AOSP Android 16, ComputerEngine.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/ComputerEngine.java), blob observado `38ac0473d1a326ee7421933d61e0b4eee74f8ac7`, símbolos `getPackagesHoldingPermissions`, `addPackageHoldingPermissions`, `generatePackageInfo`.
- [S4: Android, declaración de visibilidad](https://developer.android.com/training/package-visibility/declaring#all-apps).
- [S5: AOSP, excepciones de checkComponentPermission](https://android.googlesource.com/platform/frameworks/base/+/f8754ac2127f8adf40bd4731f39b16340acdb2f7/core/java/android/app/ActivityManager.java).

Revisar ante cambio de Android/OEM, usuario/perfil, permiso, miembros o anclas,
transporte, garantía observada en pruebas o propuesta de uso clínico. Resolver
hallazgos en este ADR y su entrega; no convertirlos en cambios de Dexcom.
