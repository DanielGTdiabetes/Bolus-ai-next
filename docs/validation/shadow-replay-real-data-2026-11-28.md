# Validación: repetición en sombra con los cálculos reales de Legacy — 2026-11-28

[ADR 0030](../adr/0030-shadow-replay-of-legacy-calculations.md). Este documento
solo contiene recuentos y códigos. Ningún valor clínico, fecha ni
identificador de Legacy está en el repositorio.

Ningún resultado es utilizable para tratamiento. Legacy conserva la autoridad
y fue el único escritor; Next no escribió nada.

## Origen de los datos

- Legacy `main` auditado: `e48c4ab896751091f323379b17c6211aff793487`. Reglas de
  Next fijadas en `bcac891d85e6fb3e84398c118061e6aeae3f73f1`; la comparación
  entre ambos no toca el cálculo (ADR 0029).
- Acceso autorizado por el propietario el 2026-11-28: lectura desde el NAS.
  Consultas `SELECT` dentro del contenedor de la base de datos, con
  `default_transaction_read_only=on`, a través de Portainer. No se inspeccionó
  la configuración del contenedor ni se leyó ninguna credencial.
- Base: 1.240 tratamientos, 346 con `calculation_trace`, 325 con total
  recomendado. Periodo: del 2025-12-16 al 2026-11-28.
- Se exportaron solo los campos numéricos del cálculo, sin fechas, notas,
  usuario ni identificadores, y los ajustes de fibra y Warsaw.
- Integridad de la copia: recuento y ocho sumas de control (hidratos, grasa,
  proteína, IOB, comida, corrección, IOB aplicado y total) calculadas en la
  base y en la copia local coincidieron exactamente.
- La exportación, el texto minimizado y el detalle se guardaron en una carpeta
  temporal fuera del repositorio y se borraron al terminar.

## Resultado

Motor `0.5.0-candidate`, conjunto `legacy-backend-core-candidate-v2`.

| Clase | Cálculos |
|---|---|
| `MATCH` | 256 |
| `MATCH_SETTINGS_UNVERIFIED` | 56 |
| `MATCH_STRATEGY_INFERRED` | 0 |
| `MATCH_WITHIN_TRACE_PRECISION` | 6 |
| `MISMATCH` | 0 |
| `NOT_COMPARABLE` | 7 |
| Total | 325 |

Comparables: 318. Coincidencias: 318. Diferencias: 0.

| Clase y motivo | Cálculos |
|---|---|
| `MATCH`: `autosens.effective_ratios_replayed` | 232 |
| `MATCH_SETTINGS_UNVERIFIED`: `settings.unverified_for_nutrients` | 56 |
| `MATCH_WITHIN_TRACE_PRECISION`: `autosens.effective_ratio_rounded_to_three_decimals` | 6 |
| `NOT_COMPARABLE`: `legacy.exercise_reduction_applied` | 7 |

### Actualización tras implementar el ejercicio (ADR 0031)

Los 7 cálculos con ejercicio se leyeron de nuevo, en solo lectura, con los
minutos y la intensidad de su línea de desglose, y se repitieron con el motor
`0.6.0-candidate`: 6 `MATCH` y 1 `MATCH_SETTINGS_UNVERIFIED`, ninguna
diferencia. Total acumulado: **325 cálculos, 325 coincidencias**. Los otros 318
se ejecutaron con el motor `0.5.0`; no se repitieron con el `0.6.0`.

## Lectura del resultado

- 299 de los 325 cálculos usaron autosens. Se repiten con el ratio y la
  sensibilidad ya ajustados que guarda el rastro.
- Legacy calculó con cuatro versiones distintas de sus ajustes
  (`config_hash`). Se tomó como vigente la del cálculo más reciente, que cubre
  262 cálculos; es una suposición, porque Legacy no guarda ese valor junto a
  los ajustes. Los cálculos de otras versiones con nutrientes también
  coinciden, pero se cuentan aparte porque no se puede demostrar qué reglas de
  fibra y Warsaw regían entonces.
- Los 7 no comparables llevan reducción por ejercicio, que Next no aplica.
- Ningún cálculo tenía glucosa ausente o caducada, tendencia registrada para
  Techne ni techo de IOB configurado. Ninguno tenía fibra.

## Triaje

La primera ejecución dio 4 `MISMATCH` y 2 `NOT_COMPARABLE` más. En los seis el
total coincidía y la comida difería en unas 5 milésimas de unidad. Causa: con
autosens el rastro guarda el ratio ajustado con tres decimales, y la división
por ese valor redondeado se aparta de la de Legacy más que la tolerancia de
0,005 del redondeo a dos decimales. No es una diferencia de regla. Se añadió a
`ShadowReplay` la holgura que explican esos tres decimales, solo con autosens y
solo cuando el total coincide, con su prueba de regresión
(`theThreeDecimalsOfAnAdjustedRatioExplainAComponentButNeverATotal`).

Antes de eso la herramienta excluía todos los cálculos con autosens. Se
corrigió para repetirlos con los valores ajustados, porque excluirlos dejaba
fuera el 92 % de la muestra sin necesidad.

## Límites

- Sin datos de fibra, glucosa caducada, Techne ni techo de IOB en la muestra:
  esas ramas siguen cubiertas solo por los vectores sintéticos.
- No se comparó lo escrito en Dexcom ni en Nightscout.
- Coincidir con Legacy no es aprobación clínica de sus reglas.
- Los umbrales y la duración de la sombra para una promoción los decide el
  propietario; aquí no se fija ninguno.

## Incidencia durante la lectura

La primera consulta se lanzó con terminal interactiva y `psql` abrió un
paginador que quedó a la espera. Se cerró ese proceso propio desde el mismo
contenedor y se comprobó su finalización. Un intento posterior de enviar la
exportación por la red local a este equipo no llegó a conectar y terminó solo.
Ninguno de los dos escribió en la base de datos. Para repetir la lectura: `psql`
sin terminal, con `-P pager=off` y `</dev/null`.
