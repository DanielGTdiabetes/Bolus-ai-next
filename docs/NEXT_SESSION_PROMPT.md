Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

OBJETIVO DEL PROYECTO
Recrear Bolus AI en móvil con cálculo, perfil, IOB, comidas e historial locales.
El camino crítico debe funcionar sin NAS ni Render. Seguridad > consistencia >
disponibilidad > comodidad.

CONTEXTO DE LA ENTREGA ANTERIOR
Mis platos y Comidas tienen un primer flujo local de borradores: crear, editar,
guardar, reabrir y copiar un plato como borrador independiente. El modelo KMP
`shared/meal-drafts` distingue ausencia de texto introducido y bloquea siempre
cálculo/tratamiento. Android usa SQLite con IDs estables, schema v1, revisiones
append-only, transacciones, conflictos y errores visibles. No hay borrado.

Consulta docs/adr/0008-local-meal-drafts.md,
docs/audit/legacy-meal-entry-2026-09-13.md y
docs/validation/local-meal-drafts-2026-09-13.md. La entrega quedó integrada y su
CI exacto debe comprobarse antes de empezar. Legacy permaneció solo lectura en
`f5417721d8019a9831126f4d843edfc4de87653d`.

LÍMITES REALES
Los textos de macros no están validados ni convertidos; la base en gramos solo se
conserva si el usuario la declara. Guardar no crea fecha de ingesta, recomendación,
hecho clínico, cálculo, confirmación, sync ni tratamiento. No hay recepción Dexcom,
perfil confirmado, IOB local, catálogo ni historial clínico. No presentes pantallas
o borradores como capacidad clínica terminada.

SIGUIENTE INCREMENTO PROPUESTO
Conectar la selección de un borrador/plato local a la revisión de entrada de Bolo,
manteniendo el cálculo bloqueado. El usuario debe poder elegir una revisión,
ver exactamente sus campos, identidad/origen y ausencias en Bolo, volver a editarla
y detectar que la selección quedó obsoleta si aparece una revisión posterior.

TRABAJO
1. Lee AGENTS.md y los documentos de arquitectura/plan/ADR relacionados. Comprueba
   Git y CI del commit integrado, actualiza origin y crea una rama corta.
2. No repitas la auditoría de platos ni copies catálogos. Consulta Legacy solo si
   una duda concreta del recorrido no está resuelta en el informe existente.
3. Modela una selección/snapshot explícita por ID y revisión. No conviertas textos,
   no infieras unidades y no transportes un valor ausente como cero.
4. Desde Mis platos/Comidas permite «usar en Bolo» y presenta allí nombre, macros,
   base, origen y revisión. La selección no es una ingesta ni una confirmación.
5. Si el registro cambia después de seleccionarlo, muestra estado obsoleto y exige
   una nueva selección/revisión; no actualices silenciosamente.
6. Conserva UI, caso de uso, persistencia y motor separados. No añadas fórmulas,
   Dexcom, MFP, NAS, Render, red, sync ni autoridad de tratamiento.
7. Prueba datos sintéticos, reinicio, selección ausente, revisión obsoleta, fallo de
   lectura y todos los bloqueos. La UI nunca debe insinuar que puede calcular.
8. Ejecuta scripts/verify.ps1 y pruebas USB si hay un único dispositivo autorizado,
   sin leer datos personales ni cambiar conectividad.
9. Actualiza documentación, publica mediante PR, integra, verifica CI del SHA exacto,
   ancestralidad y árbol limpio.
10. Deja un nuevo prompt con el estado real y el siguiente incremento más directo.

No modificar Legacy, Dexcom ni servicios. No recoger datos personales. Si surge una
regla clínica o de validación nutricional no aprobada, deja el resultado revisable y
bloqueado; no la inventes.
