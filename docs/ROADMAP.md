# Roadmap de Bolus AI Next

Este documento es el resumen de hitos. La secuencia, puertas de seguridad,
criterios de aceptación y comandos de entrega canónicos están en
[`EXECUTION_PLAN.md`](EXECUTION_PLAN.md). Si ambos documentos discrepan, se aplica
el plan ejecutable.

## Hitos

1. **Gobierno y toolchain:** aislar Legacy, fijar el stack compartido y ejecutar
   la misma verificación esencial localmente y en CI.
2. **Auditoría completa de Legacy:** inventariar funciones, contratos,
   divergencias y candidatos a retirada sin modificar producción.
3. **Contratos y seguridad:** definir estados explícitos, bloqueos, procedencia,
   unidades, versiones y huellas deterministas.
4. **Motor compartido:** implementar solo reglas trazadas y aprobadas, con
   vectores saneados y comportamiento fail-closed.
5. **Persistencia y Dexcom local en Android:** recibir, validar, persistir y
   mostrar glucosa en modo avión. Este es el primer hito funcional.
6. **Perfil e IOB local:** versionar el perfil y calcular IOB solo con historial y
   reglas cuya integridad pueda demostrarse.
7. **Flujo de bolo local no autoritativo:** cálculo, revisión, confirmación y
   registro local durable, todavía en modo sombra.
8. **Importaciones y sincronización opcionales:** nutrición, outbox e
   integraciones sin situar la red en el camino crítico.
9. **Sombra, beta y promoción:** demostrar paridad, mantener un solo escritor y
   cambiar autoridad de forma gradual y reversible.
10. **Portabilidad y retirada reversible:** consumir el mismo motor desde iOS si
    se aprueba y congelar servicios auxiliares solo con backup y rollback.

## Cobertura funcional y retirada

Ninguna función de Legacy se considera migrada por semejanza ni se omite en
silencio. La auditoría mantiene una matriz con estas salidas explícitas:

- migrar con paridad aprobada;
- corregir deliberadamente con decisión y prueba;
- bloquear porque falta evidencia o aprobación;
- retirar por decisión aprobada, dependencias conocidas y rollback documentado.

Legacy permanece sin cambios mientras sea producción. “Obsoleto” no equivale a
“seguro de borrar”: primero se demuestra que no conserva autoridad, datos,
dependencias o capacidad de recuperación necesaria.
