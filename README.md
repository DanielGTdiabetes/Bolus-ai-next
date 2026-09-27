# Bolus AI Next

Bolus AI Next es la nueva generación local-first de Bolus AI y está destinada a
sustituirlo con paridad funcional aprobada, no a depender de él como servicio.

## Objetivo

La aplicación móvil debe poder ejecutar localmente todas las funciones clínicas críticas, sin depender de NAS, Render, Telegram ni de una conexión a Internet para calcular un bolo.

Principios del proyecto:

- **Seguridad > consistencia > disponibilidad > comodidad.**
- **Local-first:** el móvil es la fuente principal de verdad durante el uso diario.
- **Offline-capable:** perder Internet no debe impedir recibir glucosa local, revisar datos disponibles ni utilizar el motor de cálculo local cuando existan entradas válidas.
- **Fail closed:** si falta un dato clínico necesario o no puede validarse, no se genera una recomendación aparentemente válida.
- **Un único motor clínico:** evitar divergencias entre Android, una futura app iOS y cualquier servicio auxiliar.
- **Cloud optional:** NAS/Render pueden permanecer como respaldo, histórico, sincronización o panel, pero no como dependencia clínica.

## Convivencia con Bolus AI actual

El proyecto `bolus_ai` actual continúa funcionando como producción durante el desarrollo y validación de Bolus AI Next.

Bolus AI Next se desarrollará inicialmente en paralelo y en modo sombra. No sustituirá ni registrará tratamientos reales automáticamente hasta que su comportamiento haya sido validado frente al sistema actual.

Cuando Bolus AI Next esté suficientemente depurado:

1. se migrará gradualmente el flujo principal al móvil;
2. se mantendrá temporalmente el sistema actual como respaldo;
3. NAS/Render podrán congelarse posteriormente, sin eliminar su código ni su histórico.

## Arquitectura objetivo

```text
Dexcom G7 local        MyFitnessPal online        Entrada manual
      |                       |                         |
      +-----------------------+-------------------------+
                              |
                        Bolus AI Next
                              |
                +-------------+-------------+
                |                           |
          Base de datos local          Motor clínico
                |                           |
                +-------------+-------------+
                              |
                    Revisión del usuario
                              |
                         Confirmación
                              |
                       Registro local
                              |
                 Sincronización opcional
                              |
             NAS / Render / Nightscout / etc.
```

## Primer hito

El primer hito técnico será deliberadamente pequeño:

> Abrir Bolus AI Next en Android, recibir una lectura local de Dexcom G7 y mostrarla correctamente aunque el dispositivo no tenga conexión a Internet.

Antes de implementar el cálculo de bolo se documentará y validará el motor clínico actual, especialmente IOB, DIA, ratios, ISF/CF, objetivos, redondeos y límites de seguridad.

## Estado

Android ya tiene una primera [estructura de interfaz basada en Legacy](docs/audit/legacy-ui-2026-09-12.md):
Inicio, Compañero, Escanear, Bolo y Más, con 25 destinos locales, accesos a comidas,
perfil, historial y herramientas Android. Conserva nombres y recorridos y mejora
la presentación móvil. La navegación no requiere cuenta ni red; las funciones
pendientes muestran su estado y las operaciones están deshabilitadas. No implica
que exista todavía cálculo, persistencia clínica ni recepción de glucosa real.
Véanse el [ADR 0007](docs/adr/0007-legacy-android-navigation.md) y la
[verificación en teléfono](docs/validation/android-navigation-2026-09-12.md).

Mis platos y Comidas ya permiten crear, editar, guardar y recuperar borradores
locales versionados. Vacío y cero explícito se conservan como estados distintos;
un plato puede copiarse a un borrador independiente. El flujo usa el modelo KMP
de `shared/meal-drafts` y SQLite Android, sin red. Guardar no registra una ingesta
ni un tratamiento, y el uso para cálculo permanece bloqueado. Véanse el
[ADR 0008](docs/adr/0008-local-meal-drafts.md), la
[auditoría Legacy](docs/audit/legacy-meal-entry-2026-09-13.md) y la
[validación](docs/validation/local-meal-drafts-2026-09-13.md).

Desde Mis platos y Comidas se puede seleccionar una revisión guardada para
revisarla en Bolo. La selección local conserva campos exactos, ausencias,
identidad y origen tras reiniciar. Si el registro cambia, muestra la selección
obsoleta y exige una nueva selección explícita. Cálculo y confirmación permanecen
bloqueados. Véanse el [ADR 0009](docs/adr/0009-local-meal-selection.md) y la
[validación](docs/validation/local-meal-selection-2026-09-26.md).
También se puede retirar explícitamente la selección desde Bolo, conservando
platos, borradores y revisiones. El estado sin selección persiste al relanzar;
un fallo de escritura se muestra sin afirmar que se haya retirado. Véase la
[validación de retirada](docs/validation/local-meal-selection-removal-2026-09-26.md).
Cada plato o borrador permite consultar sus revisiones guardadas en modo de solo
lectura, de la más reciente a la más antigua, con textos exactos, ausencias,
identidad y origen. La consulta no restaura, no edita ni cambia la selección de
Bolo, y vuelve al editor abierto sin perder cambios. Véanse el
[ADR 0010](docs/adr/0010-local-meal-revision-history.md) y la
[validación](docs/validation/local-meal-revision-history-2026-09-27.md).
Se ha verificado el flujo en la pantalla interior del Pixel 10 Pro Fold, además
de layouts anchos vertical/horizontal con texto ampliado. Pausar y reanudar los
editores conserva sus campos, foco y posición de desplazamiento.
La interfaz sigue además el modo claro u oscuro del sistema, con paletas
explícitas para textos, controles y avisos. Véase la
[validación del tema oscuro](docs/validation/android-dark-theme-2026-09-26.md).

Fase 1 en curso: auditoría clínica y técnica de Legacy en modo de solo lectura.
El inventario funcional y el primer trazado de cálculo, perfil, IOB y glucosa
están documentados, pero ninguna regla clínica está aprobada todavía. El sistema
Bolus AI actual permanece sin cambios y sigue siendo la referencia de producción.
Bolus AI Next todavía no implementa reglas clínicas ni tiene autoridad de
tratamiento.

La fase 2 cuenta con un primer [contrato de entradas no disponibles](docs/contracts/unavailable-input-v1.md)
en el núcleo compartido y pruebas comunes de sus códigos estables. Es un contrato
en memoria sin valores clínicos; aún no implementa validación ni cálculo.
El [informe de entradas no disponibles](docs/contracts/unavailable-input-report-v1.md)
reúne causas sin perder motivos distintos, con orden determinista y copia
independiente de las listas del consumidor. CI prueba los contratos en JVM. La
ruta iOS KMP se conserva para el futuro, pero iOS no es actualmente una
plataforma soportada y su ejecución automática en runners macOS quedó
desactivada por coste según el
[ADR 0004](docs/adr/0004-disable-automatic-ios-ci.md). Una prueba JVM no sustituye
la verificación iOS necesaria antes de promover ese binding a soportado.

Android dispone ya de un [esqueleto instalable](docs/adr/0002-android-app-foundation.md)
y de un [límite local fail-closed](docs/adr/0003-android-local-glucose-boundary.md)
que solo admite causas no disponibles y marca la compilación como no autoritaria.
Un [verificador preparatorio del emisor](docs/contracts/android-dexcom-sender-authentication-v1.md)
exige identidad atribuida por Android y anclas exactas de release, pero no está
conectado a un receiver productivo ni contiene package, versión o certificado real.
El [adaptador de evidencia Android](docs/adr/0005-android-sender-evidence-adapter.md)
extrae identidad y firmantes del sistema y dispone de un arnés instrumentado con
emisor sintético de distinto UID. La composición productiva sigue bloqueada.
Una [frontera de acceso diferido](docs/adr/0006-authenticate-before-payload.md)
verifica cada mensaje antes de ejecutar el acceso al contenido; las pruebas USB
comprueban que los rechazos no ejecutan ese acceso usando un marcador sintético.
No se conecta todavía a Dexcom, no solicita permisos Dexcom, no calcula bolos ni
registra tratamientos. Las versiones mínimas y el contrato Dexcom definitivos
continúan pendientes. La [investigación de recepción local G7 en Android](docs/validation/dexcom-g7-android-local-reception-2026-09-06.md)
identifica la aplicación Dexcom modificada por el propietario como fuente local
primaria y la Web API como contingencia de emergencia con retraso. El puerto
devuelve `policy_not_approved` hasta fijar una release reproducible, autenticar
el emisor y aprobar las políticas clínicas restantes.

## Documentación para ejecutar el proyecto

- [`docs/NEXT_SESSION_PROMPT.md`](docs/NEXT_SESSION_PROMPT.md): estado y siguiente incremento de revisión local de comidas y Mis platos.
- [`AGENTS.md`](AGENTS.md): contrato de trabajo, seguridad, arquitectura, pruebas y Git para Codex y otros agentes.
- [`docs/EXECUTION_PLAN.md`](docs/EXECUTION_PLAN.md): plan completo por fases, puertas de seguridad, criterios de aceptación y comandos de entrega.
- [`docs/LEGACY_SOURCE_MAP.md`](docs/LEGACY_SOURCE_MAP.md): mapa reproducible del GitHub de Bolus AI Legacy y fuentes concretas para la auditoría de solo lectura.
- [`docs/audit/legacy-clinical.md`](docs/audit/legacy-clinical.md): trazabilidad clínica por SHA, divergencias y decisiones pendientes.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): arquitectura objetivo.
- [`docs/ROADMAP.md`](docs/ROADMAP.md): resumen de hitos.
