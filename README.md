# Panel interno Plaza Vea

Prototipo académico con Spring MVC, Thymeleaf, JdbcTemplate y PostgreSQL. La aplicación usa la base local `plazavea_omnicanal` y escucha en [localhost:8090](http://127.0.0.1:8090/). Los pedidos web, las ventas de Atención y el despacho se simulan.

## Conexión a PostgreSQL local

1. Instala Java 17, Maven y PostgreSQL. Comprueba que `java` y `mvn` estén disponibles en el `PATH`.
2. En pgAdmin, conéctate a tu servidor PostgreSQL local y crea una base vacía llamada `plazavea_omnicanal`. Usa una cuenta de PostgreSQL con permiso para crear tablas en esa base.
3. Desde la carpeta del proyecto ejecuta `mvn package`.
4. En VS Code, ejecuta **Iniciar panel Plaza Vea** e introduce el usuario y la contraseña de tu PostgreSQL. En Windows también puedes usar `./Ejecutar.ps1` desde PowerShell.
5. Abre [el panel local](http://127.0.0.1:8090/). Spring Boot ejecuta `src/main/resources/schema.sql` al iniciar, y la configuración de demostración carga las cuentas y los pedidos de ejemplo. Cada integrante trabaja con su propia base.

La conexión predeterminada es `jdbc:postgresql://127.0.0.1:5432/plazavea_omnicanal`. Si tu servidor usa otro puerto o nombre de base, define `DB_URL` antes de iniciar el panel. Usa las credenciales de PostgreSQL para la conexión y las cuentas indicadas abajo para entrar al panel. Mantén las credenciales de PostgreSQL fuera del repositorio.

## Acceso de demostración

Las cinco cuentas locales usan inicialmente la contraseña `PlazaVea-Demo27`.

| Usuario | Funciones |
| --- | --- |
| `supervisor` | Genera pedidos simulados vinculados a un cliente de prueba, cancela pedidos elegibles y consulta inventario, estados e historial. |
| `picking` | Acepta pedidos, confirma productos individualmente mediante SKU, EAN o confirmación manual y registra incidencias. |
| `empaquetador` | Recibe los productos encontrados, los empaqueta, cierra pedidos completos y consulta su historial. |
| `atencion` | Valida el código en Entregar pedido, confirma la entrega y consulta pedidos listos e historial de recojos. También registra ventas simuladas. |
| `almacen` | Registra ingresos con un motivo de catálogo y procesa despachos, entregas y reintentos de Delivery. |

Para personalizar la contraseña inicial al crear las cuentas, establece `PANEL_DEMO_PASSWORD`. Para omitir el alta automática, usa `PANEL_DEMO_ENABLED=false`.

## Recorrido de demostración

1. En **Picking**, elige un producto de **Por asignar** y acepta el pedido completo. Abre un producto en **Mi picking** y confirma su SKU/EAN o selecciona **Confirmar manualmente**. El campo de código admite un lector que escriba como teclado. La cámara queda fuera del prototipo.
2. En **Por empaquetar**, marca los productos recibidos como empaquetados. Cuando estén todos listos, finaliza el pedido desde **Pedidos listos para cerrar**.
3. Para Recojo, abre **Ver QR de demostración** como `supervisor`. Después entra como `atencion`, escanea o escribe el código en **Entregar pedido**, verifica el cliente y los productos y confirma la entrega. Para Delivery, entra como `almacen` y registra el despacho y su resultado.



## Correo de recojo

Entra como **supervisor** y abre **Correo de recojo** para ver la bandeja simulada. El pedido académico `PV-DEMO-105` se prepara automáticamente al inicializar la demo. Su aviso muestra el cliente, la sucursal, la fecha y hora de disponibilidad y el mismo QR que Atención puede validar.
