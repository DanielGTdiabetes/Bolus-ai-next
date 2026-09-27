Trabaja en E:\projects\Bolus-ai-next y continúa desde origin/main actualizado.

OBJETIVO DEL PROYECTO
Recrear Bolus AI en móvil con cálculo, perfil, IOB, comidas e historial locales.
El camino crítico debe funcionar sin NAS ni Render. Seguridad > consistencia >
disponibilidad > comodidad.

ESTADO REAL
Mis platos y Comidas permiten crear, editar y recuperar borradores versionados.
Bolo muestra una revisión seleccionada con campos exactos, ausencias, base,
identidad y origen. La selección persiste en SQLite; una corrección posterior la
marca obsoleta y exige reselección explícita. Se puede retirar la selección desde
Bolo sin borrar platos, borradores ni revisiones.
Cada plato o borrador permite consultar todas sus revisiones en solo lectura.
Desde esa consulta, una revisión anterior se restaura tras confirmación explícita
en pantalla: se carga en el editor como contenido de partida (se pide descartar
si hay cambios sin guardar) y guardar crea la revisión siguiente con
`restored_from`, mismo control de conflicto e idempotencia. No se reescribe
ninguna revisión ni cambia la selección de Bolo. SQLite está en v3 con migración
probada desde v1 y v2.
La UI sigue el tema claro/oscuro del sistema y dispone de pruebas de layouts
anchos y texto ampliado.

Consulta docs/adr/0008 a 0011 (borradores, selección, historial y restauración) y
docs/validation/local-meal-revision-restore-2026-09-27.md.
Comprueba el CI del SHA exacto integrado antes de empezar.
Esta entrega no necesitó consultar ni modificar Legacy. Su última auditoría
documentada continúa fijada en f5417721d8019a9831126f4d843edfc4de87653d; no afirmes
que ese SHA sigue siendo el main remoto sin comprobarlo si necesitas nueva evidencia.

LÍMITES
Los macros son textos sin validar/convertir; ausencia nunca equivale a cero. Solo
se muestran gramos cuando el usuario declaró esa base. No hay recepción Dexcom,
perfil confirmado, IOB local, catálogo ni historial clínico. iOS sigue pendiente
de verificación. Seleccionar, retirar, consultar o restaurar revisiones no crea
ingesta, recomendación, confirmación, sync ni tratamiento. No se pueden
seleccionar en Bolo revisiones antiguas ni borrar revisiones. Cálculo y
confirmación clínica siguen bloqueados.
Las pruebas USB necesitan el Pixel desbloqueado y con la pantalla encendida
durante toda la ejecución. Enviar `adb shell input keyevent KEYCODE_WAKEUP` cada
10 s mientras corre verify.ps1 lo evita sin cambiar ajustes. Toda clase nueva de
instrumentación debe añadirse a la lista `-e class` de scripts/verify.ps1.
En Windows, los XML de recursos están en CRLF en la copia de trabajo: al editarlos
con scripts conserva los finales de línea o `git diff --check` fallará.

SIGUIENTE INCREMENTO PROPUESTO
Primer paso del hito 6: perfil clínico local como captura versionada y no
confirmada. Modelo en un módulo compartido con esquema y versión explícitos,
unidades declaradas por el usuario, franjas horarias y ausencia distinta de cero;
revisiones append-only en SQLite con el mismo patrón de conflicto e idempotencia.
Solo validación estructural (franjas completas, sin solapes, unidades presentes).
Ningún rango, límite, DIA ni valor por defecto: todo lo que dependa de límites
aprobados queda bloqueado con motivo estable. Requiere un ADR previo que fije
esquema, unidades admitidas como texto declarado y qué significa «confirmado»
sin autorizar cálculo.

TRABAJO
1. Lee AGENTS.md, README, arquitectura, plan y ADRs relacionados. Actualiza origin,
   verifica Git/CI y crea una rama corta desde origin/main.
2. Si necesitas Legacy para el inventario de campos del perfil, consulta primero
   el mapa de fuentes y fija el SHA remoto; acceso exclusivamente de lectura.
3. Redacta el ADR antes de programar. Declara incógnitas como decisiones
   pendientes del propietario; no inventes límites.
4. Mantén UI, caso de uso y SQLite separados. No añadas red, Dexcom, MFP, NAS,
   Render, sync ni autoridad de tratamiento.
5. Prueba ausencia frente a cero, franjas incompletas o solapadas, conflicto,
   reintento idéntico, reinicio, fallo de escritura y migraciones si cambian.
6. Ejecuta scripts/verify.ps1 -DeviceTests con un único dispositivo autorizado;
   usa repositorios sintéticos aislados, sin datos personales ni cambios de red.
7. Actualiza documentación, publica mediante PR, integra y verifica CI del SHA
   exacto, ancestralidad en origin/main y árbol limpio.
8. Actualiza este prompt con el estado real y el siguiente incremento directo.

No repitas auditorías ni copies catálogos.
