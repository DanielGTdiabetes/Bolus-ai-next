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
