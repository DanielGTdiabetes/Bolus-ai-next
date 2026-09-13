# ADR 0007: navegación Android local basada en la interfaz Legacy

- Estado: decisión técnica para la estructura de UI; ninguna regla clínica nueva.
- Fecha: 2026-09-12.
- Alcance: adelanto de presentación de fases 6–8 y flujos auxiliares, sin dar esas
  fases por completadas. Complementa los ADR 0002 y 0003.
- Base Next: `1036ecc` (PR #19).
- Legacy remoto fijado: `f5417721d8019a9831126f4d843edfc4de87653d`.

## Contexto y decisión

El propietario solicita trasladar la aplicación completa, con una primera UI
utilizable, y autoriza mejorar la presentación. El cliente Android Legacy inicia
`CompanionScreen.WEB`: su navegación principal es el frontend cargado por
`InAppPortal`, no el panel nativo de sincronización. La evidencia y la matriz de
pantallas están en [la auditoría de interfaz](../audit/legacy-ui-2026-09-12.md).

Se mantienen las cinco entradas Inicio / Compañero / Escanear / Bolo / Más, los
cuatro grupos funcionales de Más y sus herramientas Android. Las rutas son
identificadores internos de navegación, nunca URLs que se cargan. La barra,
encabezado y aviso de bloqueo son persistentes; cada contenido es desplazable.
Inicio conserva glucosa, IOB, COB, último bolo, acciones rápidas y actividad.
La presentación mejora contraste, jerarquía, espacios y tamaños táctiles.
Con texto ampliado, la barra inferior se desplaza horizontalmente y muestra el
destino seleccionado para conservar los nombres completos sin partir palabras.

Se conserva Views/XML y se añade AndroidX Activity 1.13.0 para gestionar Atrás
mediante `OnBackPressedDispatcher` desde API 26 y en Android reciente. Se excluye
su dependencia transitiva opcional `profileinstaller`: no se usan perfiles de
arranque ni se incorpora su receiver de instalación. Las comprobaciones de
manifest sin receivers y sin INTERNET permanecen intactas. Referencias oficiales:
[gesto Atrás](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
y [Activity 1.13.0](https://developer.android.com/jetpack/androidx/releases/activity#1.13.0).

```text
MainActivity (composición, ciclo de vida, navegación)
  -> ReadOverview -> ReadLocalGlucoseStatus -> PendingDexcomSource
                 -> contratos UnavailableInput del núcleo KMP
  -> AppNavigation (destinos/pila, sin Android ni datos clínicos)
  -> ScreenRenderer + ScreenCatalog (presentación y enlaces internos)
```

`ReadOverview` solo reúne estados no disponibles. No es un motor ni un almacén:
perfil ausente, IOB desconocido y comida ausente son explícitos. Conserva la causa
real del puerto de glucosa. UI no contiene fórmulas ni puertos de escritura.
Las operaciones pendientes carecen de listeners y están deshabilitadas. No se
recogen datos personales mediante formularios que aún no podrían validarlos o
persistirlos. Tampoco se presentan dosis, gráficas, inventarios o avisos ficticios.

## Navegación y restauración

Una pestaña principal reinicia su recorrido; un enlace conserva el destino que
lo abrió. Atrás vuelve a ese origen. En Inicio se delega Atrás al sistema.
`Bundle` guarda únicamente pila de rutas, desplazamientos y sección de Ajustes;
una ruta desconocida restaura Inicio. Recrear la actividad conserva el recorrido.
Un nuevo arranque en frío abre Inicio; no se promete persistencia clínica.
Ajustes mantiene las secciones observadas, incluida Cálculo, separada de Mi perfil
(cuenta y modo enfermedad en Legacy).

## Límites y consecuencias

No hay login obligatorio, WebView, acceso a NAS/Render, permisos Dexcom/cámara/
Bluetooth, importación de datos, cálculo ni registro. La navegación funciona sin
capacidad de red; esto no demuestra el hito pendiente de recibir glucosa real en
modo avión. No se cambia la conectividad del teléfono para verificar esta entrega.

Persistencia, validación, reglas aprobadas, recepción e importadores siguen
pendientes. Las pantallas secundarias muestran su función y el requisito ausente;
no equivalen a paridad funcional completada. No se añaden autenticación remota,
cambio de contraseña ni cierre de sesión sin una cuenta conectada.

iOS podrá reproducir el mapa de navegación y consumir los contratos y motor KMP;
la UI Views y el gesto Atrás son adaptaciones Android. Esta entrega no añade un
cliente iOS ni duplica lógica clínica; su implementación visual sigue pendiente.

## Pruebas

Pruebas JVM de pila/restauración y estados bloqueados; instrumentación que recorre
los 25 destinos por controles visibles, las secciones de Ajustes y las tres
entradas de cálculo. Verifica aviso persistente, ausencia de listeners de
operaciones, recreación y Atrás, falta de permiso INTERNET y diseño estrecho con
texto ampliado. Las imágenes de revisión se dibujan solo desde Views de Next,
sin capturar otras apps, barra de estado ni datos del teléfono. No son fixtures
clínicos ni se incluyen en Git. Verificación por `scripts/verify.ps1 -DeviceTests`.
