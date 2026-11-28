# ADR 0029: fibra y Warsaw en el cálculo de prueba

- Estado: aceptado para desarrollo y pruebas, según la autorización del
  propietario de 2026-10-12 (AGENTS.md §3). No aprueba ninguna regla, valor ni
  uso terapéutico
- Fecha: 2026-11-28
- Fase y criterio de aceptación: fase 3 (motor, reglas con paridad) y fase 7
  (cálculo local completo), en modo de prueba. Cubre LC-008 salvo la entrega
  dual
- Responsables de decisión: Claude como responsable del proyecto desde el
  2026-11-28 para lo técnico; el propietario para la aprobación clínica
- Evidencia clínica requerida: pendiente. Reglas observadas en Legacy, no
  aprobadas

## Contexto

El cálculo de prueba ignoraba fibra, grasa y proteína y lo declaraba en
«reglas no aplicadas». Legacy sí las aplica, y Warsaw viene activado por
defecto, así que el total de Next se apartaba del de `bolus_ai` en cualquier
comida con grasa o proteína.

Legacy leído por GitHub, solo lectura. `main` remoto el 2026-11-28:
`e48c4ab896751091f323379b17c6211aff793487`. La comparación
`bcac891d…e48c4ab8` no toca `bolus_engine.py`, `iob.py`, `curves.py` ni
`dtos/math_models.py` (solo Gemini, visión, Companion y despliegue), por lo
que las reglas siguen fijadas en `bcac891d85e6fb3e84398c118061e6aeae3f73f1`.

Observado en `backend/app/services/bolus_engine.py::_calculate_core`:

- Fibra. Si `fiber_g >= carbs_g` no se descuenta nada. Si no, con el
  interruptor activo y `fiber_g > fiber_threshold`,
  `eff_carbs = max(0, carbs_g - fiber_g * fiber_factor)`.
- Warsaw. Con el interruptor activo y grasa o proteína mayores que cero:
  `total_kcal = fat_g * 9 + protein_g * 4`. Solo se aplica si `total_kcal > 50`.
  `fpu = total_kcal / 100` y los hidratos equivalentes son `fpu * 10 * factor`.
  Si `total_kcal >= warsaw_trigger` y la estrategia no es `normal`, se usa
  `warsaw_factor_dual` y la insulina queda como parte extendida; si no, se usa
  `warsaw_factor_simple` y se suma a la comida.
- La parte inmediata y la extendida se redondean por separado y se suman.
  Después se aplican el bolo máximo y la parada por glucosa baja. Con la
  entrega dual desactivada la parte extendida se entrega junto a la inmediata:
  el total es el mismo.
- La rama `is_high_fiber` que repartía el 50 % es código muerto: nunca se
  activa.

Ajustes en `backend/app/models/settings.py`: `CalculatorConfig`
(`subtract_fiber`, `fiber_factor` 0,5, `fiber_threshold_g` 5) y `WarsawConfig`
(`enabled`, `safety_factor` 0,1, `safety_factor_dual` 0,2,
`trigger_threshold_kcal` 300). Next no copia ningún valor por defecto ni rango.

## Decisión

### 1. Reglas en el motor único

`CandidateEngine` (conjunto `legacy-backend-core-candidate-v2`, motor
`0.5.0-candidate`) aplica las reglas anteriores con los mismos literales (9,
4, 50, 100, 10), ahora constantes con nombre y procedencia. La parte extendida
es un paso propio (`extended`) que se redondea aparte y se suma al total.

Paridad exacta, bit a bit, en 23 vectores nuevos capturados con
`scripts/legacy-vectors/capture_nutrition_vectors.py`, además de los 12
anteriores. `verify.ps1` y la CI los reproducen desde el SHA fijado.

### 2. Sin interruptores: factor 0 desactiva

Legacy tiene dos interruptores. Next no: un factor `0` da exactamente el
resultado de Legacy con el interruptor apagado. El script de captura lo
comprueba en cada caso con factor 0 y falla si deja de cumplirse. Así se
evita un tipo nuevo de campo en el perfil y un «apagado» que no sea una
declaración explícita del usuario.

### 3. Nada ausente vale cero

- Fibra, grasa y proteína de la comida son entradas explícitas
  (`MealNutrition`). Vacío es desconocido y bloquea el paso de comida con
  `meal.fiber.missing`, `meal.fat.missing` o `meal.protein.missing`. `0` es un
  dato declarado.
- Las cinco reglas salen de la versión confirmada del perfil. Si falta una que
  haría falta leer, bloquea con `profile.<regla>.not_configured` o
  `…not_in_schema`.
- Solo se lee lo que la regla de Legacy usaría. No bloquean: una comida con
  los tres nutrientes a `0`, fibra mayor o igual que los hidratos, grasa y
  proteína de 50 kcal o menos, ni un nutriente cuya regla está desactivada.
  Con estrategia `normal` no se leen el umbral ni el factor dual.

Consecuencia: una comida tecleada a mano necesita ahora fibra, grasa y
proteína, aunque sea `0`, salvo que las reglas estén desactivadas en el perfil.
Antes se calculaba sin ellas, con un total distinto al de Legacy.

### 4. Perfil: esquema de contenido 4

Cinco campos numéricos nuevos en el mecanismo de límites existente
(`ProfileLimit`, grupo `NUTRITION_RULES`): `fiber_factor`, `fiber_threshold`
(g), `warsaw_factor_simple`, `warsaw_factor_dual` y `warsaw_trigger` (kcal
enteras, como el `int` de Legacy). Sin defaults ni rangos. Se editan en
Ajustes → Cálculo, se versionan y se confirman con el resto. Las versiones de
esquema 1 a 3 los dejan desconocidos para siempre. `clinical-profile.db` no
cambia: los valores usan `profile_limits`, que es genérica.

### 5. Bolo

- Con hidratos a mano: tres campos más (fibra, grasa, proteína).
- Con la comida seleccionada: los tres salen de la misma revisión que los
  hidratos, con las mismas condiciones; un campo sin dato es desconocido.
- Estrategia Warsaw: «automática» (valor por defecto de la petición en
  Legacy, `strategy = "auto"`) o «normal», visible y cambiable.
- El bloque de límites muestra las cinco reglas de la versión usada y marca
  las desactivadas.
- La huella de entradas (códec v3) cubre nutrientes, reglas y estrategia.

## Límites y pendientes

- Entrega dual sin aplicar (`dual_bolus` sigue en «no aplicadas»): Next
  calcula el total con la parte extendida sumada y no reparte ni programa. El
  registro de dosis tampoco anota bolos extendidos (ADR 0028).
- Sin techo de IOB, intervalo mínimo, ejercicio, autosens ni Techne.
- Los sobreescritos por petición de Legacy (`warsaw_safety_factor`, etc.) no
  existen: las reglas se cambian en el perfil.
- La estrategia tiene un valor inicial tomado de Legacy; es una opción de la
  petición, no un parámetro clínico del perfil.
- iOS sin verificar.

## Pruebas

- `CandidateNutritionTest`: 23 vectores de Legacy y los bloqueos, el factor 0,
  la estrategia y la huella.
- `ProfileNutritionRulesTest`: esquema 4, sin defaults, `0` configurado, kcal
  enteras, texto canónico y esquemas anteriores intactos.
- `NutritionCalculationTest` y `TestCalculationTest`: del perfil y la comida al
  total, con nutrientes en blanco, inválidos o tomados de la revisión.
- `TestCalculationDeviceTest` adaptada: la comida manual declara sus nutrientes.

## Reversión

Declarar los tres factores a `0` en el perfil devuelve el cálculo anterior.
Revertir el cambio deja las versiones de esquema 4 ilegibles para el código
antiguo, que rechaza esquemas desconocidos sin modificarlos.
