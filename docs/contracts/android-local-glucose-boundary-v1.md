# Límite Android de glucosa local, versión 1

- Fase: infraestructura preparatoria para Fase 5
- Alcance: Android; iOS conserva el consumo del núcleo KMP
- Estado: solo resultados no disponibles; productor local autorizado, lectura
  clínica aún no aprobada
- Evidencia Legacy consultada: `DanielGTdiabetes/bolus_ai@f5417721d8019a9831126f4d843edfc4de87653d`
- Evidencia oficial/productor/dispositivo: [investigación del 2026-09-06](../validation/dexcom-g7-android-local-reception-2026-09-06.md)

`LocalGlucoseSourcePort` separa la futura integración Android de la UI y del
contrato compartido. Su operación `readLatest()` devuelve
`LocalGlucoseSourceResult`. En esta versión solo existe la variante
`Unavailable(reason)`: no hay una variante de lectura ni campos de valor,
unidad, timestamp, tendencia, procedencia o identidad.

La ausencia deliberada de una variante disponible impide que un payload sin
validación se trate como glucosa válida. Cuando se aprueben el contrato del
productor modificado,
la autenticidad del emisor, las unidades, ambos timestamps, la identidad y las
políticas de validación, añadir una variante al tipo sellado obligará al
compilador a señalar todos los consumidores exhaustivos para revisión.

`ReadLocalGlucoseStatus` convierte una causa ya establecida por el adaptador en
`UnavailableInput(GLUCOSE, reason)`. Conserva sin precedencia ni relabeling los
trece motivos de `unavailable-input-v1`, incluidos:

- `missing`;
- `permission_denied`;
- `source_unavailable`;
- `invalid`;
- `policy_not_approved` y los demás motivos existentes.

El caso de uso no captura excepciones inesperadas ni las transforma en
`missing`, `source_unavailable` o «sin conexión». Un adaptador futuro deberá
convertir fallos conocidos en causas específicas y dejar los defectos técnicos
como tales para que no se pierda su diagnóstico.

La composición actual usa `PendingDexcomSource`, que siempre devuelve
`policy_not_approved`. Esto describe la situación real con más precisión que
`missing`: el productor previsto está autorizado por el propietario, pero Next
todavía no autentica al emisor ni valida el transporte observado. No se
solicitan permisos, no se registra un receiver y no se leen, simulan, persisten
ni sincronizan datos.

## Decisiones pendientes que bloquean una lectura disponible

1. Release reproducible y contrato versionado del productor modificado, además
   de un mecanismo demostrable de autenticidad del emisor. No sirven como prueba
   un extra con
   package name, la acción del intent, el paquete instalado ni por sí solo el
   permiso `com.dexcom.cgm.EXTERNAL_PERMISSION`, observado como `dangerous`.
2. Valor y unidad explícitos, sin conversión ambigua.
3. Timestamp de la medida, timestamp de recepción y semántica de zona/offset.
4. Identidad estable, duplicados, orden temporal y timestamps futuros.
5. Política aprobada de vigencia y validaciones clínicas aplicables.
6. Persistencia durable antes de exponer una lectura como utilizable.

Hasta resolver todas las decisiones aplicables, el puerto solo puede bloquear.
Este contrato no calcula bolos, no registra tratamientos y no concede autoridad
de escritura.

El [contrato observado del productor](modified-dexcom-g7-broadcast-observed-v1.md)
fija la forma del transporte sin aprobar todavía una lectura. La Dexcom Web API
V3 requiere OAuth 2.0 y acceso aprobado, funciona por red y documenta retraso
para datos subidos desde las apps móviles. Solo será contingencia de emergencia
y no un sustituto silencioso del productor local/offline. Health Connect dispone
de un tipo de glucosa, pero no se encontró evidencia oficial de que la versión
G7 Android inspeccionada lo produzca; tampoco autoriza una lectura disponible.

## Verificación

`LocalGlucoseSourcePortTest` comprueba que la composición real devuelve
`input.glucose.policy_not_approved`, que todos los motivos cruzan el límite sin
colapsarse, que ausencia/permiso/fuente/invalidez siguen siendo distintos y que
una excepción inesperada no se relabela como estado clínico.
