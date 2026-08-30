# Clasificación de datos, fixtures, logs y secretos

Esta política aplica a todo Bolus AI Next y a cualquier material derivado de la
auditoría de Legacy.

## Clasificación

| Clase | Ejemplos | Repositorio y CI |
|---|---|---|
| Pública técnica | código sin secretos, ADRs, contratos vacíos | permitida tras revisión |
| Clínica sintética | fixtures inventados que no representan a una persona | permitida con unidades, timestamps y propósito explícitos |
| Clínica saneada aprobada | vector derivado de Legacy sin identidad y con procedencia | permitida solo tras revisión y registro de origen/SHA |
| Clínica personal o identificable | glucosa, comidas, dosis, perfiles o historial reales | prohibida |
| Secreto | tokens, cookies, claves, credenciales, endpoints privados | prohibido |

## Reglas

- No copiar bases, logs, capturas, payloads o historiales reales al repositorio.
- No imprimir secretos ni datos clínicos identificables en builds, tests o CI.
- Los vectores que representen Legacy deben registrar SHA, ruta/símbolo,
  procedimiento de saneado y estado de aprobación; no se fabrican esperados para
  hacer pasar una prueba.
- Los tests usan datos sintéticos o saneados aprobados, siempre con unidad y
  timestamp completos cuando corresponda.
- Una credencial solo se configura fuera del repositorio mediante el mecanismo
  aprobado de la plataforma. El motor compartido no recibe credenciales.
- Si aparece material prohibido, se detiene el trabajo, se evita reproducirlo y
  se coordina su rotación/eliminación segura sin incluir su valor en incidencias.

## Logs

Por defecto, los logs técnicos pueden contener identificadores de operación
aleatorios, versión de software y códigos estables de error. No pueden contener
valores clínicos, perfiles completos, tokens, cuerpos de proveedor ni identidad
personal salvo que exista una decisión de minimización y retención aprobada.

## Retención y retirada

No se elimina historial clínico ni evidencia de auditoría por considerarlos
“antiguos”. La retirada exige clasificación, propietario, retención, backup y
rollback documentados. Los artefactos de build reproducibles sí se excluyen del
control de versiones y pueden regenerarse.
