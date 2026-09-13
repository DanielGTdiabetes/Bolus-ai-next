# ADR 0008: borradores de comida y biblioteca local versionada

- Estado: aceptado para datos no clínicos pendientes de validación; no autoriza cálculo ni tratamiento.
- Fecha: 2026-09-13.
- Fase: incremento preparatorio de persistencia local y entrada manual.
- Base Next: `572e6dba06bca6a891c9442a6c1107b88c07ef3a`.
- Legacy remoto fijado: `f5417721d8019a9831126f4d843edfc4de87653d`.

## Contexto

Legacy tiene una biblioteca de favoritos, un catálogo embebido que prepara un
carro efímero, entradas de comida en Bolo y una entrada manual nativa que crea un
evento y trata de enviarlo. Esos recorridos convierten a menudo campos ausentes en
cero, asumen bases nutricionales o asignan instantes. Ninguna de esas decisiones
autoriza una regla clínica en Next.

Este incremento necesita un flujo útil y totalmente local antes del motor: crear,
editar, guardar y recuperar borradores y platos, sin afirmar que representan una
ingesta ni que son aptos para cálculo.

## Decisión

`shared/meal-drafts` contiene el modelo y los casos de uso Kotlin Multiplatform.
Android lo consume mediante un puerto `MealRepository` y un adaptador SQLite
privado de la aplicación. Un futuro cliente iOS consumirá el mismo modelo y podrá
implementar el puerto con SQLite nativo; no duplicará semántica de borradores.

El modelo distingue:

- `DRAFT`: copia editable o entrada manual que no representa consumo;
- `DISH`: plantilla de biblioteca reutilizable;
- recomendación: fuera de este módulo y todavía bloqueada;
- hecho clínico confirmado: fuera de este módulo y no creado por guardar.

Cada registro tiene UUID estable, `schemaVersion = 1`, clase, revisión creciente,
base nutricional explícita y campos con dos estados: ausente o texto introducido.
El texto se conserva sin parseo, conversión, redondeo ni límites. La base disponible
en esta versión es «totales en gramos de este plato/comida» y requiere una acción
explícita; si no se marca queda `UNSPECIFIED`. No se asignan fecha de consumo, hora
ni zona horaria.

SQLite usa una tabla de revisiones append-only y clave `(id, revision)`. Editar
añade una revisión, sin sobrescribir la anterior. El guardado compara la revisión,
es transaccional y un reintento idéntico devuelve la revisión ya confirmada. Un
editor obsoleto falla con `meal.storage.revision_conflict`. Un esquema desconocido
falla cerrado; como esta es la primera versión no existe una migración anterior
soportada. Toda futura versión deberá añadir y probar `onUpgrade` antes de cambiar
el número de base.

La UI solo muestra «guardado» después de que la transacción termine. Si falla,
mantiene los campos y presenta el código estable. Cargar un plato crea un nuevo
borrador con identidad independiente y referencia a la revisión de origen. No hay
borrado en este incremento; las correcciones son nuevas revisiones.

## Consecuencias y límites

El flujo funciona sin permisos de red ni servicios. Android posee la base local;
el dominio compartido no importa Android, SQLite, UI, reloj ni motor clínico. La
aplicación sigue sin autoridad de tratamiento y todo registro devuelve
`meal.draft.not_clinically_validated`, `allowsCalculation = false` y
`allowsTreatment = false`.

La versión no valida si el texto es numérico, finito, no negativo o clínicamente
admisible. Tampoco define bases por 100 g, porción, unidades alternativas, fecha de
ingesta, retención, exportación, sincronización, borrado, catálogo o composición
por ingredientes. Esas decisiones quedan pendientes y no se aproximan.

## Verificación requerida

Pruebas comunes preservan ausencia frente a `"0"`, copia independiente y fallos.
Instrumentación Android cubre creación, revisión, reinicio del repositorio,
idempotencia, conflicto, esquema desconocido y fallo visible de persistencia. Los
datos son sintéticos y las pruebas de navegación usan una base en memoria inyectada
dentro del proceso de prueba para no leer la biblioteca normal del dispositivo.
