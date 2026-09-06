# Límite Android de glucosa local, versión 1

- Fase: infraestructura preparatoria para Fase 5
- Alcance: Android; iOS conserva el consumo del núcleo KMP
- Estado: solo resultados no disponibles; recepción Dexcom no autorizada
- Evidencia Legacy consultada: `DanielGTdiabetes/bolus_ai@f5417721d8019a9831126f4d843edfc4de87653d`

`LocalGlucoseSourcePort` separa la futura integración Android de la UI y del
contrato compartido. Su operación `readLatest()` devuelve
`LocalGlucoseSourceResult`. En esta versión solo existe la variante
`Unavailable(reason)`: no hay una variante de lectura ni campos de valor,
unidad, timestamp, tendencia, procedencia o identidad.

La ausencia deliberada de una variante disponible impide que un payload sin
contrato se trate como glucosa válida. Cuando se autoricen el contrato Dexcom,
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
`missing`: no existe todavía un productor autenticado y autorizado al que se le
haya solicitado una lectura. No se solicitan permisos, no se registra un
receiver y no se leen, simulan, persisten ni sincronizan datos.

## Decisiones pendientes que bloquean una lectura disponible

1. Contrato autorizado para recepción local Dexcom G7 y mecanismo demostrable
   de autenticidad/autorización del emisor.
2. Valor y unidad explícitos, sin conversión ambigua.
3. Timestamp de la medida, timestamp de recepción y semántica de zona/offset.
4. Identidad estable, duplicados, orden temporal y timestamps futuros.
5. Política aprobada de vigencia y validaciones clínicas aplicables.
6. Persistencia durable antes de exponer una lectura como utilizable.

Hasta resolver todas las decisiones aplicables, el puerto solo puede bloquear.
Este contrato no calcula bolos, no registra tratamientos y no concede autoridad
de escritura.

## Verificación

`LocalGlucoseSourcePortTest` comprueba que la composición real devuelve
`input.glucose.policy_not_approved`, que todos los motivos cruzan el límite sin
colapsarse, que ausencia/permiso/fuente/invalidez siguen siendo distintos y que
una excepción inesperada no se relabela como estado clínico.
