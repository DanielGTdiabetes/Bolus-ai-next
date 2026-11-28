# ADR 0031: reducción por ejercicio en el cálculo de prueba

- Estado: aceptado para desarrollo y pruebas, según la autorización del
  propietario de 2026-10-12 (AGENTS.md §3). No aprueba la regla ni uso
  terapéutico
- Fecha: 2026-11-28
- Fase y criterio de aceptación: fase 3 (regla con paridad) y fase 7, en modo
  de prueba; fase 9 (los cálculos reales con ejercicio pasan a ser comparables)
- Responsables de decisión: Claude para lo técnico; el propietario para la
  aprobación clínica. El propietario pidió esta regla el 2026-11-28
- Evidencia clínica requerida: pendiente. Regla observada en Legacy

## Contexto

La repetición en sombra dejaba 7 cálculos reales como no comparables porque
Legacy había reducido el bolo por ejercicio y Next no aplicaba esa regla
([validación](../validation/shadow-replay-real-data-2026-11-28.md)).

Observado en Legacy `bcac891d85e6fb3e84398c118061e6aeae3f73f1`, solo lectura:

- `backend/app/core/constants.py::EXERCISE_REDUCTION_TABLE`: fracción de
  reducción a 60 y a 120 minutos por intensidad. Baja 0,15 y 0,30; moderada
  0,30 y 0,55; alta 0,45 y 0,75.
- `backend/app/services/bolus_engine.py::calculate_exercise_reduction`: 0 con
  0 minutos; lineal de 0 a 60 y de 60 a 120; constante a partir de 120. Una
  intensidad desconocida se lee como moderada.
- `_calculate_core`: `red = min(red, 0.9)` y se aplica `× (1 − red)` a la parte
  inmediata y a la extendida, después del IOB y del techo de IOB y antes del
  redondeo. Solo si los minutos son mayores que 0.
- `backend/app/models/bolus_v2.py::ExerciseParams`: `planned`, `minutes`
  (entero, ≥ 0) e `intensity` (`low`, `moderate`, `high`). Con `planned` falso
  los minutos se toman como 0.

## Decisión

- `CandidateEngine` (conjunto `legacy-backend-core-candidate-v3`, motor
  `0.6.0-candidate`, códec de petición v4) añade el paso `exercise` entre el IOB
  y el redondeo, con la tabla y el tope como constantes con procedencia.
- Paridad exacta en 23 vectores nuevos
  (`scripts/legacy-vectors/capture_exercise_vectors.py`). Cada vector incluye el
  mismo caso sin ejercicio, que debe dar el total sin reducir.
- El ejercicio es una entrada explícita de la petición. «Sin ejercicio
  previsto» es una declaración, como `planned = false`. Un ejercicio previsto
  con minutos en blanco o inválidos, o sin intensidad, bloquea el paso con
  `exercise.minutes.missing`, `exercise.minutes.invalid` o
  `exercise.intensity.missing`; nunca se lee como «sin ejercicio». Importa
  porque la regla reduce la dosis: omitirla da un bolo mayor.
- Next no tiene el recurso de Legacy de leer una intensidad desconocida como
  moderada: solo existen las tres.
- Bolo: elección entre sin ejercicio y ejercicio previsto, con minutos enteros
  e intensidad.
- Sombra: la reducción no está en el rastro como campo. El extractor lee
  minutos e intensidad de la línea del desglose que escribe Legacy
  (`D) Ejercicio (<intensidad> <minutos>m)`). Una intensidad que no sea una de
  las tres deja el registro como no comparable. El techo de IOB se comprueba
  con la dosis anterior a la reducción, como hace Legacy.

## Resultado con datos reales

Los 7 cálculos reales con ejercicio, leídos de nuevo en solo lectura, coinciden:
6 `MATCH` y 1 `MATCH_SETTINGS_UNVERIFIED`. Con los 318 anteriores, los 325
cálculos guardados en Legacy coinciden con el motor de Next. Los 318 se
ejecutaron con el motor `0.5.0`; el paso nuevo no altera un cálculo sin
ejercicio, y lo demuestran los vectores, que exigen el mismo total con y sin el
paso en cada caso.

## Límites y pendientes

- La reducción máxima del 90 % nunca se alcanza con la tabla actual; se
  conserva porque Legacy la aplica.
- Sin autosens propio, Techne, techo de IOB ni entrega dual. iOS sin verificar.
- La tabla es de Legacy, no una recomendación clínica validada.

## Pruebas

- `CandidateExerciseTest`: 23 vectores, tabla, orden de pasos, bloqueo y huella.
- `NutritionCalculationTest`: del formulario al total; datos en blanco o
  inválidos bloquean; 0 minutos no reduce.
- `ShadowReplayTest` y `ShadowReportTest`: ejercicio repetido desde el desglose,
  intensidad no repetible, y el fixture sintético con un caso de ejercicio.

## Reversión

Elegir «Sin ejercicio previsto» da el cálculo anterior. Revertir el cambio
devuelve los cálculos con ejercicio a no comparables.
