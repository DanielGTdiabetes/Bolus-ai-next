# Bolus AI Next

Bolus AI Next es la nueva generación local-first de Bolus AI.

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
No se conecta todavía a Dexcom, no solicita permisos, no calcula bolos ni
registra tratamientos. Las versiones mínimas y el contrato Dexcom definitivos
continúan pendientes. La [investigación de recepción local G7 en Android](docs/validation/dexcom-g7-android-local-reception-2026-09-06.md)
solo demostró la Web API oficial online y metadatos no suficientes de un
broadcast local; el puerto devuelve `policy_not_approved` hasta obtener un
contrato Dexcom autorizado y autenticar el emisor.

## Documentación para ejecutar el proyecto

- [`AGENTS.md`](AGENTS.md): contrato de trabajo, seguridad, arquitectura, pruebas y Git para Codex y otros agentes.
- [`docs/EXECUTION_PLAN.md`](docs/EXECUTION_PLAN.md): plan completo por fases, puertas de seguridad, criterios de aceptación y comandos de entrega.
- [`docs/LEGACY_SOURCE_MAP.md`](docs/LEGACY_SOURCE_MAP.md): mapa reproducible del GitHub de Bolus AI Legacy y fuentes concretas para la auditoría de solo lectura.
- [`docs/audit/legacy-clinical.md`](docs/audit/legacy-clinical.md): trazabilidad clínica por SHA, divergencias y decisiones pendientes.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): arquitectura objetivo.
- [`docs/ROADMAP.md`](docs/ROADMAP.md): resumen de hitos.
