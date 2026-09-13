# Legacy: platos y entrada de comida

Fecha: 2026-09-13. Consulta exclusivamente de lectura.
Legacy fijado: `DanielGTdiabetes/bolus_ai@f5417721d8019a9831126f4d843edfc4de87653d`.

Se resolvió el SHA con `git ls-remote` y se inspeccionaron objetos mediante
`git show SHA:ruta` en la copia temporal con push `DISABLED`. No se ejecutó Legacy,
no se accedió a datos personales y no se copiaron catálogos, favoritos ni historial.

## Recorridos observados

| Fuente y símbolo | Recorrido/campos | Decisión para Next |
|---|---|---|
| `frontend/src/pages/FavoritesPage.jsx`, `FavoritesPage` | Lista, busca, crea, edita, borra y carga favoritos. Edita `name`, `carbs`, `fat`, `protein`, `fiber`, `notes`. `handleLoad` solo pasa HC y nombre a Bolo. | Conservar campos y recorrido de biblioteca; cargar crea una copia completa e independiente. Sin borrado en este lote. |
| `frontend/src/pages/FoodDatabasePage.jsx`, `FoodDatabasePage` | Catálogo embebido, búsqueda/categoría, gramos, carro efímero y paso a Bolo. Favorito desde catálogo asume referencia de 100 g y macros cero. | No copiar catálogo ni reglas de porción. El destino permanece visible y bloqueado. |
| `frontend/src/pages/BolusPage.jsx`, `BolusPage` | Nombre, HC, perfil de absorción, grasa, proteína, fibra, importaciones y accesos a favoritos/alimentos. «Nueva comida» limpia memoria. | Habilitar solo acceso a borradores/platos; no portar cálculo, perfil de absorción ni defaults. |
| `frontend/src/components/bolus/FoodSmartAutocomplete.jsx`, `FoodSmartAutocomplete` | Selecciona favorito y rellena nombre/HC/macros, convirtiendo ausencias en cero. | No portar conversión; ausencia permanece distinta de cero explícito. |
| `android-companion/src/main/java/org/bolusai/companion/MainActivity.kt`, `HomeScreen`, `manualNutritionRecord`, `MealsScreen` | HC, proteína, grasa y fibra; crea timestamps con `Instant.now`, encola y trata de enviar. | No reutilizar como borrador: es un evento de integración. Next guarda localmente sin timestamp, sync ni hecho clínico. |

Rutas observadas: `frontend/src/main.js::registerView` registra `#/bolus`,
`#/food-db` y `#/favorites`; Inicio y Más enlazan esos destinos.

## Fallbacks no portados

- `parseFloat(...) || 0`, campos inicializados a `"0"` y null mostrado como cero;
- grasa/proteína/fibra inventadas como cero al guardar desde el catálogo;
- base de 100 g, regla de raciones, clamps y umbrales nutricionales no aprobados;
- identidad por nombre o `Date.now()`;
- fecha local, zona horaria o instante de ingesta creados automáticamente;
- envío inmediato y estados de cola presentados como si fueran biblioteca local.

Estas fuentes demuestran experiencia y comportamiento existente. No aprueban
fórmulas, límites, unidades ni aptitud clínica.
