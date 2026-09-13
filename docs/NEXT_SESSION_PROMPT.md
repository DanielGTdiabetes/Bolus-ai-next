Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

OBJETIVO DEL PROYECTO
Recrear Bolus AI conservando sus funciones y experiencia, llevando al móvil el
cálculo, perfil, IOB, comidas e historial. El camino crítico debe funcionar sin
NAS ni Render. Dexcom es una entrada, no el objetivo completo.

CONTEXTO DE LA ENTREGA ANTERIOR
Se ha construido la primera estructura de UI Android basada en la interfaz real
de Legacy: Inicio, Compañero, Escanear, Bolo y Más, con 25 destinos locales.
Se mejoró la presentación y se mantuvieron explícitos los bloqueos clínicos.
Consulta docs/audit/legacy-ui-2026-09-12.md,
docs/adr/0007-legacy-android-navigation.md y
docs/validation/android-navigation-2026-09-12.md.

La UI es navegable, pero las operaciones de comida, perfil, historial y cálculo
siguen pendientes. No hay recepción de glucosa real, motor de bolo operativo,
perfil clínico confirmado, IOB local ni autoridad de tratamientos. No confundas
pantallas existentes con funcionalidades ya completadas.

Antes de empezar, comprueba Git y CI del commit exacto integrado. Si la entrega
anterior no quedó integrada o con todas sus pruebas en verde, termina ese cierre
antes de añadir otro incremento. No repitas auditorías ni infraestructura ya
resueltas sin una razón concreta.

SIGUIENTE ENTREGA PROPUESTA
Convertir Mis platos y la entrada manual de comida en el primer flujo local
utilizable de borradores: crear, editar, guardar y recuperar platos/comidas sin
red, sin habilitar cálculo ni registrar tratamientos reales.

TRABAJO
1. Lee AGENTS.md, README.md, docs/ARCHITECTURE.md, docs/EXECUTION_PLAN.md,
   docs/LEGACY_SOURCE_MAP.md y los ADR/contratos relacionados. Preserva cambios
   ajenos, actualiza origin y crea una rama corta desde origin/main.
2. Consulta Legacy exclusivamente en lectura. Resuelve y registra el SHA exacto
   actual de main. Traza FavoritesPage, FoodDatabasePage, BolusPage y el flujo
   manual del Companion para decidir los campos y recorridos realmente existentes.
3. Delimita claramente borrador de comida, plato guardado, recomendación y hecho
   clínico confirmado. Esta entrega solo habilita borradores/biblioteca local.
   No copies la base de alimentos, recetas personales ni historiales de Legacy.
4. Implementa el almacenamiento local durable, con modelo y versión explícitos,
   identificadores estables y pruebas de persistencia/reinicio/migración aplicables.
   Documenta en un ADR breve la elección y su encaje con iOS y la arquitectura.
5. Conecta Mis platos y los accesos de comida desde Bolo. Conserva la UI y los
   recorridos establecidos. Permite revisar campos y distinguir datos ausentes
   de valores introducidos explícitamente. No rellenes macros con cero, no inventes
   unidades, fechas, zona horaria ni límites clínicos. Las reglas no aprobadas se
   documentan y bloquean el uso clínico, aunque pueda conservarse un borrador
   explícitamente incompleto sin presentarlo como apto para cálculo.
6. Mantén UI, casos de uso, persistencia y motor separados. No añadas fórmulas a
   Android ni dependencias remotas. No conectes MFP, Dexcom, NAS o Render en este
   incremento. No habilites cálculo, envío, sincronización ni tratamiento.
7. Usa únicamente datos sintéticos en las pruebas. Verifica creación, edición,
   recuperación tras reinicio y fallos de guardado: la UI nunca puede informar
   que guardó si la persistencia falló. Cualquier eliminación/corrección debe
   respetar AGENTS.md y distinguir borradores de historial clínico.
8. Ejecuta scripts/verify.ps1 y las pruebas pertinentes. Si el teléfono está
   conectado, instala Next y prueba el flujo local y los bloqueos con datos
   sintéticos. No leas datos personales ni cambies conectividad del dispositivo.
9. Actualiza documentación/pendientes. Publica e integra conforme a AGENTS.md,
   verifica CI del commit integrado y ancestralidad de origin/main, y deja el
   repositorio limpio. Si aparece un cambio clínico que requiera revisión del
   propietario, deja el resultado concreto revisable y solicita esa revisión;
   no conviertas evidencia Legacy en aprobación clínica.
10. Prepara de nuevo un prompt para la sesión siguiente, con lo realmente
    terminado, evidencia y un siguiente incremento acotado.

LÍMITES
No modificar Legacy, Dexcom ni servicios; no recoger datos clínicos personales;
no cambiar conectividad del teléfono. Mantener seguridad > consistencia >
disponibilidad > comodidad. No invertir esta entrega únicamente en autenticación
ni limitarse a otro inventario: entregar un flujo visible y probado en Next.
