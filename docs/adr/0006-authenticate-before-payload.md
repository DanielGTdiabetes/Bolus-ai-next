# ADR 0006: autenticar antes de acceder al contenido del broadcast

- Estado: implementación técnica preparatoria; sin autorización clínica
- Fecha: 2026-09-12
- Fase: preparación del primer hito Android de Fase 5

## Decisión

`DexcomAuthenticatedIngress` compone el autenticador del ADR 0005 con una función
diferida. Cada llamada obtiene y verifica de nuevo la evidencia del emisor;
únicamente después invoca esa función, una vez, con la identidad autenticada.
El consumidor debe colocar todo acceso a extras dentro de la función y ejecutar
la frontera síncronamente durante `onReceive`. Crear la función no debe evaluar
el payload. La API no recibe un payload previamente leído ni almacena confianza
entre mensajes.

Un rechazo devuelve `DexcomIngressResult.Rejected` conservando la causa y el
motivo compartido del autenticador. `Handled<T>` solo envuelve la respuesta de
la función: no significa lectura válida, persistencia ni aceptación clínica.
Los errores inesperados de evidencia o de la función se propagan; la frontera
no los convierte en errores de autenticación, no reintenta ni registra éxito.

No hay parser, puerto de almacenamiento ni receiver productivo. La composición
sigue usando `PendingDexcomSource`. Incluso una identidad autenticada necesita
un contrato de contenido y políticas aprobadas antes de producir una lectura.

## Pruebas y límites

Las pruebas JVM cubren todas las causas del enum de rechazo con una función que
fallaría si se ejecutara, orden de consultas, ejecución única, política clínica
todavía pendiente, reautenticación del mensaje siguiente y propagación de
defectos sin reintentos.

El arnés entre UID distintos usa esta frontera para leer un marcador sintético
del Intent exclusivamente tras autenticar. En cada rechazo verifica cero
ejecuciones del acceso; en el caso permitido verifica una y el valor del
marcador. No contiene glucosa ni un parser Dexcom. La prueba demuestra ausencia
de ejecución posterior en esta composición; no pretende probar transacciones
de una persistencia aún inexistente. Un futuro receiver debe mantener toda
lectura/validación/persistencia detrás de la frontera y añadir sus propias
pruebas. Kotlin no impide que otro código lea un Intent antes de invocarla.

No se consultó Legacy ni el productor Dexcom para esta decisión. Permanecen
pendientes la release reproducible, anclas aprobadas, identidad compartida real,
contrato de campos, políticas clínicas, persistencia y prueba real en modo avión.
R-010 y R-014 no se cierran con este incremento.

## Portabilidad

La coordinación queda en el adaptador Android, fuera del motor compartido. No
añade fórmulas ni dependencias al dominio. La alternativa iOS sigue pendiente
del puerto específico descrito en ADR 0005; no se declara soporte iOS nuevo.
