# Cliente Android

Este módulo es una base de desarrollo no autoritativa. Abre una pantalla que
muestra la política de glucosa local como pendiente y el cálculo como bloqueado.
El límite Android solo admite causas no disponibles: todavía no recibe Dexcom,
no persiste datos y no calcula ni registra tratamientos.

## Compilar

Con JDK 21 y Android SDK Platform/Build Tools 36 instalados:

```powershell
.\scripts\verify.ps1
```

El APK de depuración queda en:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Puede instalarse en un dispositivo de desarrollo conectado mediante:

```powershell
adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

La instalación y apertura deben probarse en el dispositivo Android objetivo
antes de considerar cumplido este incremento. El APK de depuración no es una
versión de tratamiento y no debe distribuirse como tal.

## Límites actuales

- No declara el permiso `INTERNET` ni permisos Dexcom.
- No interpreta broadcasts, notificaciones ni datos de otros proveedores.
- No contiene lecturas de ejemplo ni valores clínicos sustitutivos.
- Incluye un verificador puro y todavía no conectado para futura identidad del
  emisor. Su política real permanece pendiente y no contiene anclas de Dexcom.
- El adaptador Android obtiene identidad y firmantes del sistema para el
  verificador. Solo se conecta durante las pruebas sintéticas; véase el
  [ADR 0005](../docs/adr/0005-android-sender-evidence-adapter.md).
- Cada causa no disponible tiene un estado visible distinto, pero solo un futuro
  adaptador autorizado podrá producir la causa observada.
- El puerto actual devuelve `policy_not_approved` y no expone una variante de
  lectura hasta aprobar contrato, autenticidad, unidades, timestamps, identidad
  y políticas de validación.
- La [investigación del productor y del dispositivo](../docs/validation/dexcom-g7-android-local-reception-2026-09-06.md)
  identifica la aplicación Dexcom modificada como fuente local primaria. Su
  transporte está observado, pero faltan release reproducible, autenticación y
  políticas clínicas. La Web API queda solo como contingencia con retraso.
- `minSdk 26` y `applicationId` son provisionales según el ADR 0002.

## Pruebas instrumentadas de identidad

Con un único dispositivo USB autorizado API 34 o superior:

```powershell
.\scripts\verify.ps1 -DeviceTests
```

El arnés instala/actualiza Next y dos APKs desechables: instrumentación y emisor
sintético. Al terminar retira las dos APKs de prueba; Next permanece instalada.
No sobrescribe fixtures preexistentes. La acción sintética tiene destino fijo
Next y ningún dato clínico. No utiliza Dexcom real, Legacy, servidores, logcat
ni cambios de conectividad. La prueba real Dexcom en modo avión queda pendiente.
La ejecución normal de `verify.ps1` y CI compilan las pruebas sin usar un teléfono.

El arnés usa además `DexcomAuthenticatedIngress` para diferir el acceso a un
marcador sintético hasta autenticar cada mensaje. Comprueba cero accesos cuando
se rechaza y uno cuando se autentica. No interpreta campos de glucosa ni persiste
mensajes; véase el [ADR 0006](../docs/adr/0006-authenticate-before-payload.md).
