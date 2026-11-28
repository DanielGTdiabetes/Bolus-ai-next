# ADR 0030: modo sombra por repetición de los cálculos guardados en Legacy

- Estado: aceptado para desarrollo y pruebas. No aprueba ninguna regla ni uso
  terapéutico y no cambia la autoridad de tratamiento, que sigue en Legacy
- Fecha: 2026-11-28
- Fase y criterio de aceptación: fase 9, puntos 1 a 5 (emparejar, ejecutar con
  los mismos datos, comparar por componente, clasificar e informar). El punto 6
  se cumple por construcción: la herramienta no escribe nada
- Responsables de decisión: Claude para lo técnico; el propietario para el
  acceso a los datos reales de Legacy y para los umbrales de promoción
- Evidencia clínica requerida: pendiente

## Contexto

El propietario pidió el 2026-11-28 tener las dos apps en paralelo, con Next sin
escribir, y comparar lo que calcula y escribe cada una.

Auditoría de Legacy, solo lectura, en `main`
`e48c4ab896751091f323379b17c6211aff793487`:

- Cada bolo aceptado guarda en `treatments.calculation_trace` (JSON) un rastro
  del cálculo, construido por `backend/app/services/bolus_trace.py` en el bot y
  por `frontend/src/lib/bolusTrace.js` en la app web, con la misma forma:
  - `snapshot`: total recomendado y aceptado, componente de comida, corrección,
    IOB, IOB aplicado a la corrección, parte inmediata y extendida, glucosa
    usada (valor, origen, tendencia, antigüedad, caducada) y el desglose.
  - `applied_ratios`: ratio, sensibilidad y objetivo ya resueltos (también los
    efectivos tras autosens), paso de redondeo, bolo y corrección máximos,
    techo de IOB, Techne, autosens, entrega dual y `config_hash`.
- La fila de `treatments` guarda hidratos, grasa, proteína y fibra.
- El rastro **no** guarda las reglas de fibra y Warsaw ni la estrategia de la
  petición. Sí guarda `config_hash`, que cubre esas reglas pero no se almacena
  junto a los ajustes: es un valor calculado.
- El rastro redondea: comida, corrección, IOB e IOB aplicado a 2 decimales. El
  total y las partes inmediata y extendida no se redondean.
- **Ninguna lectura existente devuelve el rastro.** `/api/nightscout/treatments`
  devuelve los campos del tratamiento sin él y
  `/api/integrations/mobile/bolus-events` solo los eventos finales. El rastro
  solo es accesible en la base de datos.
- Legacy escribe además eventos en el historial de Dexcom (inventario LF-031)
  y tratamientos en Nightscout. Next no escribe en ninguno.

Sin lectura del rastro no hay sombra en vivo desde el móvil sin modificar
Legacy, que está prohibido sin petición expresa. En cambio, los cálculos ya
guardados permiten algo más útil: repetirlos todos en el motor de Next.

## Decisión

### 1. Repetición fuera de línea

Tres piezas, ninguna con red:

1. `scripts/shadow/extract_legacy_calculations.py`: lee dos ficheros locales
   que exporta el propietario (filas de `treatments` con rastro y ajustes
   actuales) y escribe un texto minimizado. Los identificadores pasan a un
   prefijo SHA-256; fechas, notas, nombres, usuario e identificadores de
   Nightscout se descartan.
2. `org.bolusai.engine.shadow.ShadowReplay`, en el módulo del motor único:
   reconstruye la petición con los valores que Legacy resolvió, ejecuta
   `CandidateEngine` y compara comida, corrección, IOB aplicado y total.
3. `tools/shadow-replay`: lee el texto, imprime un resumen de recuentos y, si
   se pide, un fichero de detalle con cifras.

Sirve para lo ya guardado y para lo que Legacy registre en adelante: basta
repetir la exportación. El usuario no teclea nada dos veces y Next no necesita
MyFitnessPal para comparar, porque los nutrientes vienen de la fila de Legacy.

### 2. Clasificación

| Clase | Significado |
|---|---|
| `MATCH` | mismas componentes y total con las entradas del rastro |
| `MATCH_SETTINGS_UNVERIFIED` | coincide, pero con nutrientes y sin poder verificar por `config_hash` que las reglas de fibra y Warsaw eran las exportadas |
| `MATCH_STRATEGY_INFERRED` | coincide solo con la estrategia Warsaw `normal`, que el rastro no guarda |
| `MATCH_WITHIN_TRACE_PRECISION` | el total de Legacy queda dentro del intervalo que explican los 2 decimales del IOB guardado |
| `MISMATCH` | entradas equivalentes y resultado distinto: hay que explicarlo, reproducirlo y cubrirlo con una prueba |
| `NOT_COMPARABLE` | no se puede demostrar que las entradas sean equivalentes; nunca cuenta como coincidencia |

No comparable, con su código: falta un campo del rastro; un valor no es
representable; glucosa ausente o caducada (Legacy calcula sin corrección, Next
bloquea); autosens activo; Techne con tendencia; reducción por ejercicio; el
techo de IOB habría recortado; Next bloquea; o hay diferencia con reglas sin
verificar. Una diferencia con reglas sin verificar no se achaca a una regla.

Tolerancias: 0,005 en las tres componentes que el rastro redondea a 2
decimales y `1e-9` en el total. Son consecuencia del formato del rastro, no
umbrales clínicos.

Un interruptor de Legacy apagado se repite como factor `0` (ADR 0029).

### 3. Privacidad

- La exportación, el texto minimizado y el detalle contienen valores clínicos
  reales: se guardan fuera del repositorio y se borran al terminar.
- El resumen solo tiene recuentos y códigos y puede conservarse.
- En el repositorio solo hay filas sintéticas. `verify.ps1` rechaza cualquier
  `.jsonl` o texto de sombra que no sea un fixture sintético.

### 4. Escritura de Next

Next no emite a Dexcom, a Nightscout ni a Legacy, y no tiene permiso de red.
Cuando se implemente esa escritura nacerá con un interruptor apagado por
defecto y solo se encenderá tras un relevo explícito con Legacy (AGENTS.md §7).

## Límites y pendientes

- Compara cálculos, no lo escrito: la comparación de eventos de Dexcom y de
  Nightscout llegará con la escritura de Next, hoy inexistente.
- No es sombra en vivo en el móvil: haría falta una lectura del rastro en
  Legacy, que es un cambio en producción y requiere petición del propietario.
- La fila guarda los hidratos del tratamiento; si difieren de los de la
  petición original, el registro saldrá como diferencia y habrá que triarlo.
- El acceso a los datos reales, las métricas, la duración mínima y los
  umbrales de promoción los decide el propietario; aquí no se fija ninguno.
- `IobValue.replayed` existe solo para esta repetición.

## Pruebas

- `ShadowReplayTest`: los 35 vectores de Legacy se repiten como coincidencia;
  cada causa de no comparable; diferencia; reglas sin verificar; interruptor
  apagado; estrategia inferida; precisión del IOB; resumen y análisis del texto.
- `ShadowReportTest`: el fixture sintético se clasifica registro a registro;
  el resumen no contiene cifras clínicas ni referencias.
- `verify.ps1`: el extractor reproduce su salida sintética, no exporta campos
  privados, y ninguna pieza usa red, base de datos ni broadcasts.

## Uso

```text
python -I scripts/shadow/extract_legacy_calculations.py --rows <filas.jsonl> --settings <ajustes.json> --legacy-sha <sha> > <fuera-del-repo>/shadow.txt
.\gradlew.bat -q :tools:shadow-replay:run --args="<fuera-del-repo>/shadow.txt <fuera-del-repo>/detalle.md"
```

## Reversión

Borrar el módulo `tools/shadow-replay`, el paquete `engine.shadow` y
`scripts/shadow`. No hay estado, migraciones ni efectos en Legacy.
