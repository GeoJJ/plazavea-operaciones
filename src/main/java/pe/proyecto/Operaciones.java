package pe.proyecto;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

@Service
class Operaciones {
    private final JdbcTemplate db;

    Operaciones(JdbcTemplate db) {
        this.db = db;
    }

    Operario operario(String login) {
        return db.query("""
            SELECT u.id,u.nombre,u.rol,u.tienda_id,t.nombre sucursal
            FROM usuario_operativo u JOIN tienda t ON t.id=u.tienda_id WHERE u.usuario=? AND u.activo
            """, (r, fila) -> new Operario(r.getLong("id"), r.getString("nombre"), r.getString("rol"),
                r.getLong("tienda_id"), r.getString("sucursal")), login)
            .stream().findFirst().orElseThrow(() -> new AccessDeniedException("Cuenta operativa inactiva."));
    }

    Map<String,Object> resumen(long tienda, String modalidad) {
        return db.queryForMap("""
            SELECT count(*) FILTER (WHERE estado='Recibido') recibidos,
                count(*) FILTER (WHERE estado IN ('En picking','En packing')) preparacion,
                count(*) FILTER (WHERE estado='Listo') listos,
                count(*) FILTER (WHERE estado IN ('En reparto','Intento fallido')) reparto
            FROM pedido WHERE tienda_id=? AND (?='' OR modalidad=?)
            """, tienda, modalidad, modalidad);
    }

    List<Map<String,Object>> pedidos(long tienda, String modalidad, String buscar, String estado) {
        return db.queryForList("""
            SELECT p.*, c.nombre nombre_cliente, c.referencia referencia_cliente, t.nombre sucursal,
                to_char(p.compromiso AT TIME ZONE 'America/Lima','HH24:MI') hora,
                to_char(tr.usado AT TIME ZONE 'America/Lima','DD/MM/YYYY HH24:MI') fecha_recojo
            FROM pedido p JOIN tienda t ON t.id=p.tienda_id JOIN cliente c ON c.id=p.cliente_id
            LEFT JOIN ticket_recojo tr ON tr.pedido_id=p.id
            WHERE p.tienda_id=? AND (?='' OR p.modalidad=?) AND (?='' OR p.estado=? OR (?='DESPACHOS' AND p.estado IN ('Listo','Intento fallido')))
                AND (p.referencia ILIKE ? OR c.nombre ILIKE ? OR c.referencia ILIKE ?)
            ORDER BY CASE WHEN p.estado IN ('Entregado','Cancelado') THEN 1 ELSE 0 END,
                tr.usado DESC NULLS LAST,p.compromiso,p.id
            """, tienda, modalidad, modalidad, estado, estado, estado, "%"+buscar+"%", "%"+buscar+"%", "%"+buscar+"%");
    }

    List<Map<String,Object>> clientes() {
        return db.queryForList("SELECT id,nombre,referencia FROM cliente WHERE referencia LIKE 'CLI-%' ORDER BY nombre");
    }

    Map<String,Object> validarRecojo(String codigo, long tienda) {
        exigir(!codigo.isBlank() && codigo.length() <= 80, "Escanea o escribe un código de recojo válido.");
        Map<String,Object> pedido = db.queryForList("""
            SELECT p.id,p.referencia,p.tienda_id,p.modalidad,p.estado,tr.usado,
                c.nombre nombre_cliente,c.referencia referencia_cliente,t.nombre sucursal
            FROM ticket_recojo tr JOIN pedido p ON p.id=tr.pedido_id
            JOIN cliente c ON c.id=p.cliente_id JOIN tienda t ON t.id=p.tienda_id WHERE tr.codigo=?
            """, codigo.strip()).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No se encontró un pedido con ese código."));
        exigir(pedido.get("tienda_id").equals(tienda), "El pedido corresponde a otra sucursal.");
        exigir(pedido.get("usado") == null, "Este código ya se usó para entregar el pedido.");
        exigir("RECOJO".equals(pedido.get("modalidad")) && "Listo".equals(pedido.get("estado")),
                "El pedido no está listo para recojo.");
        return pedido;
    }

    List<Map<String,Object>> inventario(long tienda) {
        return db.queryForList("""
            SELECT i.*,p.sku,p.ean,p.imagen_url,p.marca,p.nombre,t.nombre sucursal FROM inventario_tienda i
            JOIN producto p ON p.id=i.producto_id JOIN tienda t ON t.id=i.tienda_id
            WHERE i.tienda_id=? ORDER BY p.sku
            """, tienda);
    }

    List<Map<String,Object>> detalle(long id) {
        return db.queryForList("""
            SELECT p.nombre,p.sku,p.ean,p.imagen_url,p.marca,d.*,i.pasillo,i.stock_actual,i.stock_disponible
            FROM detalle_pedido d JOIN producto p ON p.id=d.producto_id
            JOIN pedido o ON o.id=d.pedido_id
            JOIN inventario_tienda i ON i.tienda_id=o.tienda_id AND i.producto_id=p.id
            WHERE d.pedido_id=? ORDER BY i.pasillo,p.sku
            """, id);
    }

    List<Map<String,Object>> historial(long id) {
        return db.queryForList("""
            SELECT h.estado,to_char(h.fecha AT TIME ZONE 'America/Lima','HH24:MI') hora,
                coalesce(u.nombre,'Registro anterior') operario
            FROM historial_estado_pedido h LEFT JOIN usuario_operativo u ON u.id=h.operario_id
            WHERE h.pedido_id=? ORDER BY h.id DESC
            """, id);
    }

    List<Map<String,Object>> incidencias(long id) {
        return db.queryForList("SELECT nota FROM incidencia_stock WHERE pedido_id=? ORDER BY id DESC", id);
    }

    List<Map<String,Object>> entrega(long id) {
        return db.queryForList("SELECT direccion,repartidor FROM entrega WHERE pedido_id=?", id);
    }

    List<Map<String,Object>> correosRecojo(long tienda) {
        return db.queryForList("""
            SELECT p.id,p.referencia,p.estado,c.nombre nombre_cliente,c.referencia referencia_cliente,
                t.nombre sucursal,tr.codigo,tr.usado,
                to_char(h.fecha AT TIME ZONE 'America/Lima','DD/MM/YYYY') fecha_recojo,
                to_char(h.fecha AT TIME ZONE 'America/Lima','HH24:MI') hora_recojo
            FROM pedido p JOIN ticket_recojo tr ON tr.pedido_id=p.id
            JOIN cliente c ON c.id=p.cliente_id JOIN tienda t ON t.id=p.tienda_id
            JOIN LATERAL (
                SELECT fecha FROM historial_estado_pedido
                WHERE pedido_id=p.id AND estado='Listo' ORDER BY id DESC LIMIT 1
            ) h ON true
            WHERE p.tienda_id=? AND p.modalidad='RECOJO'
            ORDER BY h.fecha DESC,p.id DESC
            """, tienda);
    }

    @Transactional
    void prepararEjemploCorreo(Operario supervisor, Operario picking, Operario empaquetador) {
        long producto = db.queryForObject("SELECT id FROM producto WHERE sku='HG-250'",Long.class);
        long cliente = db.queryForObject("SELECT id FROM cliente WHERE referencia='CLI-001'",Long.class);
        var nuevo = crearPedido("PV-DEMO-105","RECOJO",supervisor,Map.of(producto,2),cliente);
        if (nuevo.isEmpty()) return;
        long id = nuevo.getAsLong();
        avanzar(id,"iniciar","",picking);
        encontrar(id,producto,"",true,picking);
        empaquetar(id,producto,empaquetador);
        avanzar(id,"listo","",empaquetador);
    }


    static final List<String> TRANSPORTES = List.of("Moto","Auto","Furgoneta");

    List<Map<String,Object>> repartidores() {
        return db.queryForList("SELECT id,nombre FROM repartidor WHERE activo ORDER BY nombre");
    }

    List<Map<String,Object>> intentosDespacho(long id) {
        return db.queryForList("""
            SELECT d.*,r.nombre repartidor,u.nombre asignador
            FROM intento_despacho d JOIN repartidor r ON r.id=d.repartidor_id
            JOIN usuario_operativo u ON u.id=d.asignado_por WHERE pedido_id=? ORDER BY d.id DESC
            """,id);
    }

    int transporteMinimo(long id) {
        return db.queryForObject("""
            SELECT greatest(coalesce(max(p.tamano_empaque),0),
                CASE WHEN sum(d.cantidad)>8 THEN 2 WHEN sum(d.cantidad)>3 THEN 1 ELSE 0 END)
            FROM detalle_pedido d JOIN producto p ON p.id=d.producto_id WHERE d.pedido_id=?
            """,Integer.class,id);
    }

    @Transactional
    void asignarDespacho(long id,long repartidor,String transporte,Operario operario) {
        Map<String,Object> pedido=bloquear(id,operario.tienda());
        exigir("DELIVERY".equals(pedido.get("modalidad"))
                && List.of("Listo","Intento fallido").contains(pedido.get("estado")),
                "El pedido no admite una asignación en este estado.");
        exigir(TRANSPORTES.indexOf(transporte)>=transporteMinimo(id),
                "El transporte seleccionado no tiene capacidad suficiente.");
        exigir(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM repartidor WHERE id=? AND activo)",
                Boolean.class,repartidor)), "Selecciona un repartidor disponible.");
        registrarResultadoDespacho(id,"Reasignado");
        db.update("""
            INSERT INTO intento_despacho (pedido_id,repartidor_id,transporte,asignado_por) VALUES (?,?,?,?)
            """,id,repartidor,transporte,operario.id());
        db.update("UPDATE entrega SET repartidor=(SELECT nombre FROM repartidor WHERE id=?) WHERE pedido_id=?",
                repartidor,id);
    }

    private void registrarResultadoDespacho(long id,String resultado) {
        db.update("""
            UPDATE intento_despacho SET resultado=?,resultado_en=now()
            WHERE pedido_id=? AND resultado IS NULL
            """,resultado,id);
    }


    String codigo(long id) {
        return db.queryForList("SELECT codigo FROM ticket_recojo WHERE pedido_id=? AND usado IS NULL", String.class,id)
                .stream().findFirst().orElse("");
    }

    List<Map<String,Object>> colaPicking(Operario operario, boolean pendientes) {
        return db.queryForList("""
            SELECT o.id,o.referencia,o.modalidad,p.nombre,p.sku,p.ean,p.imagen_url,p.marca,d.cantidad,d.producto_id,d.preparado,
                i.pasillo,i.stock_actual,i.stock_disponible
            FROM pedido o JOIN orden_picking ord ON ord.pedido_id=o.id
            JOIN detalle_pedido d ON d.pedido_id=o.id JOIN producto p ON p.id=d.producto_id
            JOIN inventario_tienda i ON i.tienda_id=o.tienda_id AND i.producto_id=p.id
            WHERE o.tienda_id=? AND (CASE WHEN ? THEN o.estado='Recibido' AND ord.operario_id IS NULL
                ELSE o.estado='En picking' AND ord.operario_id=? END)
            ORDER BY o.compromiso,o.id,i.pasillo,p.sku
            """, operario.tienda(),pendientes,operario.id());
    }

    List<Map<String,Object>> colaEmpaque(long tienda, boolean historial) {
        return db.queryForList("""
            SELECT o.id,o.referencia,o.modalidad,o.estado,p.nombre,p.sku,p.ean,p.imagen_url,p.marca,d.cantidad,d.producto_id,
                u.nombre empaquetador
            FROM pedido o JOIN detalle_pedido d ON d.pedido_id=o.id
            JOIN producto p ON p.id=d.producto_id
            LEFT JOIN usuario_operativo u ON u.id=d.empacado_por
            WHERE o.tienda_id=? AND d.preparado AND
                (CASE WHEN ? THEN d.empacado_en IS NOT NULL
                 ELSE d.empacado_en IS NULL AND o.estado IN ('En picking','En packing') END)
            ORDER BY o.compromiso,o.id,p.sku
            """, tienda,historial);
    }

    List<Map<String,Object>> listosEmpaque(long tienda) {
        return db.queryForList("""
            SELECT o.id,o.referencia,o.modalidad FROM pedido o
            WHERE o.tienda_id=? AND o.estado='En packing'
              AND EXISTS (SELECT 1 FROM detalle_pedido d WHERE d.pedido_id=o.id)
              AND NOT EXISTS (SELECT 1 FROM detalle_pedido d WHERE d.pedido_id=o.id AND d.empacado_en IS NULL)
            ORDER BY o.compromiso,o.id
            """, tienda);
    }

    @Transactional
    long simular(String modalidad, Operario operario, long producto, long cliente) {
        if (!List.of("RECOJO","DELIVERY").contains(modalidad)) throw new IllegalArgumentException("Modalidad inválida.");
        String referencia = "PV-" + UUID.randomUUID().toString().substring(0,8).toUpperCase();
        return crearPedido(referencia, modalidad, operario, Map.of(producto,1), cliente).orElseThrow();
    }

    @Transactional
    void pedidoDemostracion(Operario operario, String referencia, String modalidad,
                           String referenciaCliente, Map<String,Integer> articulos) {
        Map<Long,Integer> lineas = new java.util.LinkedHashMap<>();
        for (var articulo : articulos.entrySet()) {
            Long id = db.queryForObject("SELECT id FROM producto WHERE sku=?",Long.class,articulo.getKey());
            lineas.put(id,articulo.getValue());
        }
        long cliente = db.queryForObject("SELECT id FROM cliente WHERE referencia=?",Long.class,referenciaCliente);
        crearPedido(referencia, modalidad, operario, lineas, cliente);
    }

    private java.util.OptionalLong crearPedido(String referencia, String modalidad, Operario operario,
                                              Map<Long,Integer> lineas, long cliente) {
        long tienda = operario.tienda();
        String nombre = db.queryForList("SELECT nombre FROM cliente WHERE id=? AND referencia LIKE 'CLI-%'",
                String.class,cliente).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selecciona un cliente de la lista."));
        var ids = db.queryForList("""
            INSERT INTO pedido (referencia,tienda_id,cliente,modalidad,cliente_id)
            VALUES (?,?,?,?,?) ON CONFLICT (referencia) DO NOTHING RETURNING id
            """, Long.class, referencia, tienda, nombre, modalidad, cliente);
        if (ids.isEmpty()) return java.util.OptionalLong.empty();
        long id = ids.get(0);
        for (long producto : lineas.keySet().stream().sorted().toList()) {
            int cantidad = lineas.get(producto);
            exigir(db.update("""
                UPDATE inventario_tienda SET stock_reservado=stock_reservado+?
                WHERE tienda_id=? AND producto_id=? AND stock_disponible>=?
                """,cantidad,tienda,producto,cantidad)==1, "La sucursal no tiene unidades disponibles.");
            db.update("INSERT INTO detalle_pedido (pedido_id,producto_id,cantidad) VALUES (?,?,?)",id,producto,cantidad);
            movimiento(tienda,producto,id,"Reserva",cantidad,operario.id(),null);
        }
        db.update("INSERT INTO orden_picking (pedido_id) VALUES (?)",id);
        if (modalidad.equals("DELIVERY")) {
            db.update("INSERT INTO entrega (pedido_id,direccion) VALUES (?,'Dirección de prueba · Arequipa')",id);
        }
        historial(id,"Recibido",operario.id());
        return java.util.OptionalLong.of(id);
    }

    @Transactional
    void venta(Operario operario, long producto) {
        long tienda = operario.tienda();
        if (db.update("""
                UPDATE inventario_tienda SET stock_actual=stock_actual-1
                WHERE tienda_id=? AND producto_id=? AND stock_disponible>=1
                """,tienda,producto) != 1) throw new IllegalStateException("No hay stock libre para la venta de Atención.");
        movimiento(tienda,producto,null,"Venta simulada de Atención",1,operario.id(),null);
    }

    static final List<String> MOTIVOS_INGRESO = List.of("Traslado desde otra sede",
            "Ingreso desde almacén central", "Recepción internacional", "Recepción de proveedor nacional");

    @Transactional
    void ingreso(Operario operario, long producto, int cantidad, String motivo) {
        exigir(cantidad > 0 && cantidad <= 10000, "Indica una cantidad entre 1 y 10 000.");
        exigir(MOTIVOS_INGRESO.contains(motivo), "Selecciona un motivo de ingreso válido.");
        exigir(db.update("""
            UPDATE inventario_tienda SET stock_actual=stock_actual+?
            WHERE tienda_id=? AND producto_id=?
            """, cantidad, operario.tienda(), producto)==1, "El producto no pertenece a la sucursal.");
        movimiento(operario.tienda(),producto,null,"Ingreso de almacén",cantidad,operario.id(),motivo.strip());
    }

    @Transactional
    void incidencia(long id, String nota, Operario operario) {
        Map<String,Object> pedido = bloquear(id, operario.tienda());
        exigirPreparador(pedido, operario);
        exigir(pedido.get("estado").equals("En picking"),"Las incidencias se registran durante el picking.");
        exigir(!nota.isBlank() && nota.length() <= 240,"Escribe una incidencia de hasta 240 caracteres.");
        db.update("INSERT INTO incidencia_stock (pedido_id,nota,operario_id) VALUES (?,?,?)",id,nota.strip(),operario.id());
    }

    @Transactional
    void avanzar(long id, String accion, String valor, Operario operario) {
        Map<String,Object> pedido = bloquear(id, operario.tienda());
        if (operario.rol().equals("PICKING")) exigirPreparador(pedido, operario);
        String estado = (String) pedido.get("estado");
        boolean recojo = pedido.get("modalidad").equals("RECOJO");
        String siguiente = switch (accion) {
            case "iniciar" -> {
                exigir(estado.equals("Recibido"),"El pedido ya ha sido tomado.");
                db.update("UPDATE orden_picking SET inicio=now(), operario_id=? WHERE pedido_id=?",operario.id(),id);
                yield "En picking";
            }

            case "listo" -> {
                exigir(estado.equals("En packing"),"Completa el picking primero.");
                exigir(!faltanLineas(id,true),"Completa el empaquetado de todos los productos.");
                if (recojo) db.update("INSERT INTO ticket_recojo (pedido_id,codigo) VALUES (?,?)",id,UUID.randomUUID().toString());
                yield "Listo";
            }
            case "recojo" -> {
                exigir(recojo && estado.equals("Listo"),"El pedido no está listo para recojo.");
                exigir(db.update("UPDATE ticket_recojo SET usado=now() WHERE pedido_id=? AND codigo=? AND usado IS NULL",
                    id,valor.strip()) == 1,"Código de recojo incorrecto.");
                liquidar(pedido,id,true,operario.id());
                yield "Entregado";
            }
            case "despacho" -> {
                exigir(!recojo && (estado.equals("Listo") || estado.equals("Intento fallido")),"El pedido no está listo para despacho.");
                exigir(db.update("""
                    UPDATE intento_despacho SET salida_en=now()
                    WHERE pedido_id=? AND resultado IS NULL AND salida_en IS NULL
                    """,id)==1,"Asigna un repartidor y transporte antes de confirmar la salida.");

                yield "En reparto";
            }
            case "entrega" -> {
                registrarResultadoDespacho(id,"Entregado");
                exigir(estado.equals("En reparto"),"El pedido debe estar en reparto.");
                liquidar(pedido,id,true,operario.id());
                yield "Entregado";
            }
            case "fallo" -> {
                exigir(estado.equals("En reparto"),"El pedido debe estar en reparto.");
                registrarResultadoDespacho(id,"Intento fallido");
                yield "Intento fallido";
            }
            case "cancelar" -> {
                exigir(List.of("Recibido","En picking","En packing","Listo").contains(estado),
                    "El pedido ya no puede cancelarse desde este estado.");
                liquidar(pedido,id,false,operario.id());
                registrarResultadoDespacho(id,"Cancelado");
                yield "Cancelado";
            }
            default -> throw new IllegalArgumentException("Acción inválida.");
        };
        cambiarEstado(id,siguiente,operario.id());
    }

    @Transactional
    void encontrar(long id, long producto, String codigo, boolean manual, Operario operario) {
        Map<String,Object> pedido = bloquear(id, operario.tienda());
        exigirPreparador(pedido, operario);
        exigir("En picking".equals(pedido.get("estado")), "El pedido debe estar en picking.");
        exigir(db.update("""
            UPDATE detalle_pedido d SET preparado=true FROM producto p
            WHERE p.id=d.producto_id AND d.pedido_id=? AND d.producto_id=? AND NOT d.preparado
              AND (? OR p.ean=? OR upper(p.sku)=upper(?))
            """,id,producto,manual,codigo.strip(),codigo.strip())==1,
            "El código no corresponde al producto o el producto ya fue confirmado.");
        if (!faltanLineas(id,false)) {
            db.update("UPDATE orden_picking SET fin=now() WHERE pedido_id=?",id);
            cambiarEstado(id,"En packing",operario.id());
        }
    }

    @Transactional
    void empaquetar(long id, long producto, Operario operario) {
        Map<String,Object> pedido = bloquear(id, operario.tienda());
        exigir(List.of("En picking","En packing").contains(pedido.get("estado")),
            "El pedido ya no admite empaquetado.");
        exigir(db.update("""
            UPDATE detalle_pedido SET empacado_en=now(), empacado_por=?
            WHERE pedido_id=? AND producto_id=? AND preparado AND empacado_en IS NULL
            """,operario.id(),id,producto)==1, "El producto debe estar encontrado y pendiente de empaquetar.");
    }

    private boolean faltanLineas(long id, boolean empaquetado) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM detalle_pedido WHERE pedido_id=?
              AND CASE WHEN ? THEN empacado_en IS NULL ELSE NOT preparado END)
            """,Boolean.class,id,empaquetado));
    }

    private void cambiarEstado(long id,String estado,long operario) {
        db.update("UPDATE pedido SET estado=? WHERE id=?",estado,id);
        historial(id,estado,operario);
    }

    private void liquidar(Map<String,Object> pedido, long id, boolean salida, long operario) {
        long tienda = (Long) pedido.get("tienda_id");
        for (Map<String,Object> linea : db.queryForList("SELECT * FROM detalle_pedido WHERE pedido_id=? ORDER BY producto_id",id)) {
            long producto = (Long) linea.get("producto_id");
            int cantidad = (Integer) linea.get("cantidad");
            db.update("""
                UPDATE inventario_tienda SET stock_reservado=stock_reservado-?, stock_actual=stock_actual-?
                WHERE tienda_id=? AND producto_id=?
                """,cantidad,salida?cantidad:0,tienda,producto);
            movimiento(tienda,producto,id,salida?"Salida por entrega":"Liberación",cantidad,operario,null);
        }
    }

    private Map<String,Object> bloquear(long id, long tienda) {
        return db.queryForList("SELECT * FROM pedido WHERE id=? AND tienda_id=? FOR UPDATE",id,tienda).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Pedido fuera de la sucursal o inexistente."));
    }

    private void exigirPreparador(Map<String,Object> pedido, Operario operario) {
        long id = (Long) pedido.get("id");
        Long encargado = db.queryForObject("SELECT operario_id FROM orden_picking WHERE pedido_id=?",Long.class,id);
        if (encargado != null && encargado != operario.id()) {
            throw new AccessDeniedException("El pedido ya se encuentra asignado a otro operario.");
        }
    }

    private void historial(long id, String estado, long operario) {
        db.update("INSERT INTO historial_estado_pedido (pedido_id,estado,operario_id) VALUES (?,?,?)",id,estado,operario);
    }

    private void movimiento(long tienda,long producto,Long pedido,String tipo,int cantidad,long operario,String motivo) {
        db.update("""
            INSERT INTO movimiento_inventario (tienda_id,producto_id,pedido_id,tipo,cantidad,operario_id,motivo)
            VALUES (?,?,?,?,?,?,?)
            """, tienda,producto,pedido,tipo,cantidad,operario,motivo);
    }

    private void exigir(boolean condicion,String mensaje) {
        if (!condicion) throw new IllegalStateException(mensaje);
    }
}









