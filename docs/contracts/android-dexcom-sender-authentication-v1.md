# Autenticación del emisor Dexcom local en Android, versión 1

- Fase: infraestructura preparatoria para Fase 5
- Alcance: autenticación técnica anterior al payload; no valida glucosa
- Estado: verificador implementado, sin política aprobada ni integración con un
  receiver
- Evidencia Legacy consultada:
  `DanielGTdiabetes/bolus_ai@f5417721d8019a9831126f4d843edfc4de87653d`
- Evidencia del productor: [transporte observado v1](modified-dexcom-g7-broadcast-observed-v1.md)

`DexcomSenderAuthenticator` es una frontera pura del módulo Android. Recibe una
política de confianza y evidencia que un futuro adaptador deberá obtener de
Android antes de leer extras o decodificar el payload. No accede a
`BroadcastReceiver`, `PackageManager`, red, almacenamiento o reloj y no puede
producir `LocalGlucoseSourceResult.Available`.

## Política de confianza

La política tiene dos estados exhaustivos:

- `Pending`: no existe una release reproducible aprobada y toda autenticación
  devuelve `POLICY_NOT_APPROVED`;
- `Approved`: exige un package name exacto y una lista no vacía de anclas de
  release aprobadas.

Cada ancla mantiene unidos un `versionCode` exacto y el conjunto completo de
digests SHA-256 de los firmantes. No se usan rangos ni listas independientes de
versiones y certificados: una versión de una release no puede combinarse con el
certificado de otra. Los digests se validan como 64 caracteres hexadecimales,
se canonizan a minúsculas y todas las colecciones se copian al construir la
política.

Este contrato no contiene package names, versiones ni certificados reales. Los
valores de las pruebas son sintéticos y no constituyen anclas aprobadas.

## Evidencia y orden de comprobación

`DexcomBroadcastSenderEvidence` contiene:

1. package y UID atribuidos por Android al emisor;
2. identidad del mismo package resuelta mediante `PackageManager`, con package,
   UID, `versionCode` y digests SHA-256 de firmantes.

El verificador aplica en orden:

1. existe política aprobada;
2. Android compartió package y UID válidos;
3. el package atribuido coincide exactamente con la política;
4. el package puede resolverse;
5. package y UID resueltos coinciden con la identidad atribuida;
6. existe un ancla para el `versionCode` exacto;
7. el conjunto completo de firmantes coincide con el ancla de esa release.

Solo entonces devuelve `Authenticated` con una copia de la evidencia
verificada. El resultado significa exclusivamente que la identidad técnica
cumplió esta política; no afirma que el payload sea válido, vigente, persistido
ni utilizable clínicamente.

## Fallos

Se conservan causas internas específicas:

- `POLICY_NOT_APPROVED`;
- `IDENTITY_NOT_SHARED`;
- `PACKAGE_NOT_RESOLVED`;
- `PACKAGE_MISMATCH`;
- `UID_MISMATCH`;
- `VERSION_NOT_APPROVED`;
- `CERTIFICATE_MISMATCH`.

La política pendiente cruza el límite general como
`UnavailabilityReason.POLICY_NOT_APPROVED`. Los demás rechazos de identidad se
traducen a `UnavailabilityReason.AUTHENTICATION_FAILED`; la causa específica se
mantiene en el resultado Android para diagnóstico y pruebas, sin relabeling a
`missing` o `source_unavailable`.

## Límites que permanecen cerrados

- No se ha fijado una política `Approved` real.
- El productor observado aún no usa
  `BroadcastOptions.setShareIdentityEnabled(true)`.
- No existe extracción Android de identidad, resolución de `SigningInfo`,
  receiver, permiso Dexcom, parser ni persistencia.
- No se interpreta ningún extra ni se introducen lecturas sintéticas.
- `PendingDexcomSource` continúa devolviendo
  `input.glucose.policy_not_approved` y la UI sigue bloqueada.
- Una prueba JVM del verificador no sustituye las pruebas instrumentadas
  productor/consumidor ni la prueba en dispositivo y modo avión.

Una futura integración deberá demostrar que la identidad se recoge y valida
antes de cualquier parseo y que un rechazo no persiste ningún payload. También
deberá resolver por separado todos los gates de unidad, timestamps, identidad
de lectura, orden, duplicados, vigencia y persistencia enumerados en
[`android-local-glucose-boundary-v1.md`](android-local-glucose-boundary-v1.md).
