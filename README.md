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

Proyecto nuevo. El sistema Bolus AI actual permanece sin cambios y sigue siendo la referencia de producción durante esta fase.
