# Roadmap inicial

## Fase 0 — Mantener Legacy estable

- `DanielGTdiabetes/bolus_ai` continúa siendo producción.
- Solo correcciones críticas de seguridad durante la migración.
- Documentar el punto de referencia actual para comparar resultados.

## Fase 1 — Auditoría clínica y técnica

- Localizar el motor de cálculo real del backend actual.
- Documentar CR, ISF/CF, objetivos, DIA, IOB, curva de acción, Warsaw/otras reglas, límites y redondeos.
- Identificar todas las fuentes de datos clínicas y sus fallos posibles.
- Crear vectores de cálculo de referencia a partir del sistema actual.

Criterio de salida: se puede explicar y reproducir determinísticamente cada componente del cálculo actual.

## Fase 2 — Núcleo compartido

- Crear `shared/bolus-engine` sin dependencias de Android, UI o red.
- Implementar únicamente reglas validadas contra Legacy.
- Añadir tests unitarios y golden vectors.
- Prohibir defaults clínicos silenciosos.

Criterio de salida: los vectores Legacy y Next coinciden dentro del redondeo definido.

## Fase 3 — Persistencia local Android

- Crear base de datos Room/SQLite.
- Modelar glucosa, comidas/revisiones, perfil, cálculos, bolos y cola de sincronización.
- Diseñar migraciones y reglas de idempotencia desde el principio.

## Fase 4 — Dexcom local

- Reutilizar/adaptar únicamente la integración validada del proyecto actual.
- Recibir glucosa directamente de Dexcom G7.
- Persistir valor, timestamp, tendencia y origen.
- Mostrar antigüedad y bloquear el uso automático de lecturas caducadas.
- Probar en modo avión.

Primer hito funcional: mostrar correctamente la glucosa Dexcom sin Internet.

## Fase 5 — Perfil e IOB local

- Persistir perfil clínico versionado.
- Validación estricta de todos los parámetros necesarios.
- Implementar IOB con la misma lógica validada de Legacy.
- IOB desconocido nunca se convierte en 0 U.

## Fase 6 — Cálculo local completo

- Introducción manual de comida.
- Desglose transparente del cálculo.
- Confirmación explícita.
- Persistencia local antes de cualquier sincronización.

## Fase 7 — MyFitnessPal local-first

- Auditar cómo acceder de forma fiable desde Android.
- Importar alimentos/comidas al estado local.
- Modelar revisiones exactas y fingerprints/versiones.
- Nunca reutilizar una revisión antigua como nueva.
- Permitir revisar, editar o descartar antes del cálculo.

## Fase 8 — Modo sombra

- Legacy continúa siendo autoridad de tratamiento.
- Next recibe los mismos datos y calcula en paralelo.
- Comparar automáticamente inputs, IOB, componentes y recomendación.
- No escribir tratamientos reales desde Next.

## Fase 9 — Beta controlada

- Activar progresivamente confirmación y registro desde Next.
- Garantizar que solo un sistema tiene autoridad de escritura.
- Mantener Legacy disponible como rollback.

## Fase 10 — Servicios auxiliares y sincronización

- Sincronización idempotente con los servicios que se decida conservar.
- Nightscout, backup, panel web y Telegram pasan a ser opcionales.
- Una sincronización nunca recalcula un bolo ya confirmado.

## Fase 11 — Migración final

Solo cuando existan suficientes pruebas y uso estable:

- Next pasa a ser sistema principal.
- Legacy queda temporalmente disponible como respaldo.
- Posteriormente se congelan NAS/Render y se conserva su código/configuración para una posible vuelta a una arquitectura compatible con iOS.

## Regla de entrega

Los cambios de Bolus AI Next se consideran integrados cuando están revisados, probados y presentes en `origin/main`. Durante fases experimentales se pueden utilizar ramas, pero `main` representa siempre el estado estable del nuevo proyecto.
