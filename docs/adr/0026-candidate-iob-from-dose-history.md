# ADR 0026: IOB candidato calculado desde un historial de dosis explícito

- Estado: aceptado para desarrollo y pruebas, según la autorización del
  propietario de 2026-10-12 (AGENTS.md §3). No aprueba ningún uso terapéutico
- Fecha: 2026-10-12
- Fase y criterio de aceptación: fase 6, puntos 4 y 5 (IOB con DIA, curva y
  eventos explícitos en el motor común; historial insuficiente, duplicado o
  conflictivo bloquea). Cubre en el motor LC-004 y LC-005
- Responsables de decisión: propietario. Las decisiones técnicas reversibles las
  toma el agente (EXECUTION_PLAN §3.3)
- Evidencia clínica requerida: pendiente. Curvas, tablas y bolo extendido son
  reglas candidatas observadas en Legacy, sin aprobación clínica

## Contexto

El cálculo de prueba de Bolo usa un IOB tecleado a mano (ADR 0024, LC-011). Legacy
calcula el IOB con `backend/app/services/iob.py::compute_iob_from_sources` y las
curvas de `backend/app/services/math/curves.py`. El plan de continuación pone el
historial local de insulina y el IOB calculado como siguiente regla candidata.

Se resolvió `main` de Legacy antes de leer: `bcac891d85e6fb3e84398c118061e6aeae3f73f1`.
`git diff f5417721 bcac891` no muestra cambios en `iob.py`, `curves.py` ni
`models/settings.py`, así que la auditoría de `docs/audit/legacy-clinical.md`
sigue aplicando a estos ficheros.

Lo observado en Legacy:

- `compute_iob_from_sources` suma, en el orden de las fuentes fusionadas, la
  insulina restante de cada bolo. Si `duration > 10` min, reparte el bolo en
  trozos de 5 min (`ceil(duration / 5)`); un trozo aún no administrado cuenta
  entero. El resultado final es `max(total, 0)`.
- `InsulinCurves.get_iob` admite `walsh`/`exponential`, `bilinear`/`triangle`,
  `fiasp` y `novorapid` (tablas interpoladas de actividad en % del GIR máximo,
  estiradas a la DIA si difiere más de 1 min de 300 min) y, para cualquier otro
  nombre, incluido `linear`, `1 - t / DIA`.
- Con Walsh y `tau <= 0` (pico `>= DIA / 2`) cae en silencio a la curva lineal.
  Con bilineal y pico `>= DIA` divide por cero o da negativos.
- `IOBConfig` tiene defaults `dia_hours = 4`, `curve = walsh`,
  `peak_minutes = 75` y valida el pico entre 10 y 300 min.
- La consulta de tratamientos usa una ventana de `DIA + 1 h`. Un fallo de base de
  datos o Nightscout deja el IOB `unavailable`/`stale`; un fallo de eventos
  locales lo deja `partial` y sigue calculando.
- La basal se excluye buscando `basal` o `lenta` en el tipo o las notas.
- Las dosis se deduplican por identidad estable y, si una no la tiene, por
  instante y dosis exactos. Dos dosis iguales con identidades distintas suman.

## Decisión

### 1. Cálculo en el motor único, sin estado

`org.bolusai.engine.iob.CandidateIob` es la única implementación. Es pura y
determinista: recibe una `IobRequest` con el instante de cálculo, la acción de
la insulina (`InsulinAction`) y el historial (`DoseHistory`), y no lee reloj,
base de datos ni red. Motor `0.4.0-candidate`, conjunto
`legacy-iob-candidate-v1`, SHA de Legacy fijado y validación `pending`.
`usableForTreatment` es siempre `false`.

Entradas:

- `InsulinAction`: versión, huella y confirmación del perfil, curva, DIA en horas
  (decimal canónico) y pico en minutos como `Input`. El motor no conoce el
  módulo de perfil; quien llama copia los datos de la versión usada.
- `DoseHistory`: referencia de la fuente, intervalo que el productor garantiza
  completo `[desde, hasta]`, duración extendida máxima que el productor puede
  registrar y las dosis. Cada dosis lleva identidad, instante, unidades y
  duración extendida (`0` es un bolo normal, un hecho registrado).
- Un productor que no puede garantizar el historial pasa `Input.Unavailable`, no
  una lista vacía. Una lista vacía con cobertura suficiente es un IOB conocido de
  `0 U`; es la única forma de obtener cero.

Salida: IOB, contribución de cada dosis (también las que ya no aportan nada) con
el tiempo transcurrido evaluado, inicio de cobertura exigido, avisos y huella
SHA-256 de una codificación canónica de toda la petición.

### 2. Reglas candidatas portadas

Se portan con el mismo orden de operaciones IEEE que Legacy: las cinco curvas,
las dos tablas interpoladas con su estiramiento, el reparto del bolo extendido y
el transcurrido `(segundos exactos) / 60`. Los literales de reparto (`> 10` min,
trozos de 5 min) y las tablas quedan en `CandidateIobRules`/`InsulinCurves` como
candidatos trazados. Las tablas citan estudios EMA/EPAR en Legacy, pero esa cita
no se ha revisado: su procedencia aquí es Legacy.

### 3. Diferencias deliberadas con Legacy, todas más restrictivas

| Legacy | Next |
|---|---|
| Defaults de DIA, curva y pico | sin defaults: la acción procede de la versión del perfil; ausente bloquea |
| Walsh con `tau <= 0` cae a lineal | `iob.action.walsh_peak_incompatible` |
| Bilineal con pico `>= DIA` divide por cero | `iob.action.peak_not_before_end` |
| Pico validado entre 10 y 300 min | solo coherencia estructural (`> 0`, antes del final); el rango clínico queda pendiente |
| Ventana fija `DIA + 1 h` | el historial debe cubrir desde `ahora - max(DIA, duración efectiva) - duración extendida máxima declarada` hasta `ahora`; si no, `iob.history.insufficient_window` (§3.1) |
| Fuente local que falla → `partial` y calcula | el productor no puede declarar parcial: o garantiza la cobertura o pasa `Unavailable` |
| Dosis con instante futuro cuentan enteras | `iob.history.future_dose` |
| Dosis `<= 0` se ignoran | `iob.history.non_positive_dose` |
| Identidad repetida se descarta en la fusión | `iob.history.duplicate_identity`: el productor debe resolverlo antes |
| Basal filtrada por texto | el historial solo contiene bolos rápidos; el productor separa por tipo |
| Desglose omite contribuciones `<= 0,01 U` | se listan todas; la suma es la misma |
| Caché `iob_cache.json` como último conocido | no hay caché: un IOB anterior nunca sustituye al actual |

### 3.1 Duración efectiva de las curvas tabuladas

Las tablas de Fiasp y NovoRapid terminan en 300 min y Legacy solo las estira si
la DIA difiere más de 1 min. Con una DIA entre 299 y 301 min la curva sigue
actuando 300 min. La primera versión de esta entrega exigía cobertura solo desde
`ahora - DIA`: con DIA 4,99 h (299,4 min) y una dosis de hace 299,7 min, que aún
deja insulina, un historial que cubriera solo la DIA daba un `0 U` conocido. Era
un falso cero.

Corrección: la ventana exigida usa `max(DIA, duración efectiva de la curva)`.
La duración efectiva sale en el resultado (`effectiveActionMinutes`) y, cuando no
coincide con la DIA, se avisa con `iob.action.tabulated_duration_not_stretched`.
El cálculo del IOB no cambia y la paridad con Legacy se mantiene. La regresión
`unstretchedTabulatedCurveRequiresItsOwnDurationNotTheShorterDia` falla con el
motor anterior (`a4daa93`), que devolvía `Computed(units=0.0)`, y pasa con la
corrección.

Una dosis fuera del intervalo declarado (`iob.history.dose_outside_coverage`) o
más larga que la duración extendida declarada
(`iob.history.extended_beyond_declared_maximum`) también bloquea. Un perfil sin
confirmar da `profile.unconfirmed`. Todas las causas se acumulan.

### 4. Vectores capturados de Legacy

`scripts/legacy-vectors/capture_iob_vectors.py` carga `curves.py` e `iob.py`
desde una copia local separada en el SHA fijado. Sustituye por stubs inertes
solo SQLAlchemy, la base de datos, el modelo de ajustes, el almacén y los modelos
de respuesta; reemplaza `_load_iob_sources` para que las dosis sintéticas lleguen
como única fuente correcta, sin Nightscout, y no lee ni escribe la caché. El
resultado se guarda en `CandidateIobLegacyVectors.kt` con los bits exactos.
`CandidateIobTest` exige igualdad exacta del total y de cada contribución que
Legacy publica en 19 vectores: las cinco curvas, DIA nativa y estirada, la
frontera de 1 min sin estirar, dosis en el instante de cálculo y más allá de la
DIA, bolos extendidos parciales, completos y no alineados a 5 min, duración de
10 min como bolo normal y lista vacía. Son vectores de paridad observada, no
golden vectors clínicos.

### 5. Fuera de esta entrega

- **Integración con el cálculo de Bolo.** `CandidateRequest` sigue con IOB
  manual. Conectar el IOB calculado exige DIA, curva y pico en el perfil y un
  historial local; hasta entonces no hay productor. No se mezcla con esta
  entrega para no cambiar el codec del cálculo dos veces.
- **Campos del perfil.** DIA, curva y pico entrarán como nueva versión del
  esquema de contenido, sin defaults, como en el ADR 0025.
- **Historial local de insulina.** Next no registra tratamientos reales
  (AGENTS.md §2, §7). El historial que alimente este cálculo debe definir qué
  dosis contiene y de dónde vienen (registro manual de dosis administradas,
  importación) sin convertir a Next en escritor de tratamientos. Requiere
  decisión del propietario.
- **COB, techo de IOB y aviso de apilamiento.** Dependen de este cálculo y van
  después.

## Alternativas consideradas

- **Portar la fusión de fuentes de Legacy al motor.** Descartada: deduplicar y
  elegir fuentes es trabajo del productor del historial. El motor solo verifica
  que el resultado sea coherente y bloquea si no lo es.
- **Ventana `DIA + 1 h` como Legacy.** Descartada: no garantiza nada para bolos
  extendidos de más de 1 h y es un literal sin aprobar. La cobertura exigida se
  deriva de la DIA y de la duración máxima que el productor declara.
- **Aceptar el rango de pico 10-300 min de Legacy.** Pospuesta: es un rango
  clínico sin aprobar. Se valida solo la coherencia que evita fallbacks y
  divisiones por cero.
- **Comparar con tolerancia.** Innecesaria en JVM: la paridad es exacta, incluida
  la exponencial de Walsh.

## Consecuencias

- Android y un futuro iOS comparten el cálculo. En Kotlin/Native `exp` usa la
  libm de la plataforma; la paridad exacta de Walsh en iOS no se ha comprobado.
- El cambio de versión del motor a `0.4.0-candidate` cambia la huella de las
  peticiones del cálculo de prueba, que solo viven en memoria.
- Rollback: quitar el paquete `iob`. No hay estado persistido ni consumidores.

## Seguridad y pruebas

- `CandidateIobTest` (15 pruebas): paridad exacta con los 19 vectores; ventana
  con la duración efectiva de las tablas sin estirar y nunca menor que la DIA
  (§3.1, regresión); resultado
  no utilizable; lista vacía como único cero; historial ausente, acción ausente,
  sin confirmar, pico ausente y DIA cero; pico no positivo, al final y Walsh
  incompatible; aviso de pico ignorado; ventana exacta, insuficiente y ampliada
  por la duración extendida; historial no actual o incoherente; identidad
  duplicada, dosis futura, fuera de cobertura, nula o con extendido mayor que el
  declarado; acumulación de causas; trozos no administrados; huella determinista
  y sensible a cada entrada; rechazo de entradas mal formadas.
- `scripts/verify.ps1` y la CI reproducen los vectores de los ADR 0024 y 0026:
  `scripts/legacy-vectors/verify_legacy_vectors.py` obtiene solo el commit fijado
  del repositorio público de Legacy en un directorio temporal sin push, ejecuta
  los scripts de captura con `python -I` y compara byte a byte. Un vector
  alterado hace fallar la verificación. Sin red se puede omitir con
  `-SkipLegacyVectors`, con aviso visible; la CI no lo omite y fija Python 3.13.
- `verify.ps1` exige además que ambos resultados mantengan
  `usableForTreatment = false` y validación `pending`, y que ningún código Android
  use todavía `org.bolusai.engine.iob`.
- No se ejecutó nada contra Legacy ni sus despliegues. La captura usa una copia
  local separada.
- Queda pendiente la aprobación clínica de curvas, tablas, bolo extendido y del
  rango del pico.

## Condiciones para revisar este ADR

- Cambios en `iob.py` o `curves.py` en un SHA posterior de Legacy.
- Decisión del propietario sobre el origen del historial local de insulina.
- Aprobación o rechazo clínico de alguna curva o del bolo extendido.
