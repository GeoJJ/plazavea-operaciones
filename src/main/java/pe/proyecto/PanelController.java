package pe.proyecto;

import java.security.Principal;
import java.util.List;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class PanelController {
    private static final String RECOJO_VALIDADO = "recojoValidado";
    private final Operaciones operaciones;
    @Value("${panel.demo.enabled:true}")
    private boolean demo;

    PanelController(Operaciones operaciones) {
        this.operaciones = operaciones;
    }

    @GetMapping("/login")
    String login(Model model) {
        model.addAttribute("demo",demo);
        return "login";
    }

    @GetMapping("/correo")
    String correo(@RequestParam(required=false) Long id, Principal sesion, Model model) {
        Operario usuario = operaciones.operario(sesion.getName());
        if (!usuario.rol().equals("SUPERVISOR")) throw new AccessDeniedException("Vista no autorizada.");
        var correos = operaciones.correosRecojo(usuario.tienda());
        var actual = id == null ? correos.stream().findFirst().orElse(null)
                : correos.stream().filter(correo -> id.equals(correo.get("id"))).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("correos",correos);
        model.addAttribute("correo",actual);
        if (actual != null) {
            model.addAttribute("productos",operaciones.detalle((Long) actual.get("id")));
            model.addAttribute("vigente",actual.get("usado") == null && "Listo".equals(actual.get("estado")));
            model.addAttribute("qr",CodigoQr.imagen((String) actual.get("codigo")));
        }
        return "correo";
    }


    @GetMapping("/")
    String panel(@RequestParam(defaultValue="") String modalidad, @RequestParam(defaultValue="") String q,
                 @RequestParam(required=false) Long id, @RequestParam(defaultValue="") String vista,
                 Principal sesion, HttpSession sesionWeb, Model model) {
        Operario usuario = operaciones.operario(sesion.getName());
        boolean picking = usuario.rol().equals("PICKING");
        boolean atencion = usuario.rol().equals("ATENCION");
        boolean almacen = usuario.rol().equals("ALMACEN");
        boolean empaque = usuario.rol().equals("EMPAQUETADOR");
        boolean supervisor = usuario.rol().equals("SUPERVISOR");
        if (vista.isEmpty()) vista = picking ? "picking" : empaque ? "empaque" : atencion ? "atender" : "pedidos";
        boolean autorizada = switch (vista) {
            case "pedidos" -> true;
            case "despachos", "despachos-reparto" -> almacen;
            case "atender", "recojos-listos", "recojos-historial" -> atencion;
            case "inventario" -> atencion || almacen || supervisor;
            case "simulacion" -> atencion || supervisor;
            case "picking" -> picking;
            case "empaque", "empaquetados" -> empaque;
            default -> false;
        };
        if (!autorizada) throw new AccessDeniedException("Vista no autorizada.");

        model.addAttribute("modalidad", modalidad);
        model.addAttribute("q", q);
        model.addAttribute("vista", vista);
        model.addAttribute("usuario", usuario);
        model.addAttribute("picking", picking);
        model.addAttribute("atencion", atencion);
        model.addAttribute("almacen", almacen);
        model.addAttribute("empaque", empaque);
        model.addAttribute("supervisor", supervisor);
        switch (vista) {
            case "picking" -> {
                model.addAttribute("pendientes", operaciones.colaPicking(usuario,true));
                model.addAttribute("asignados", operaciones.colaPicking(usuario,false));
            }
            case "empaque", "empaquetados" -> {
                model.addAttribute("articulos", operaciones.colaEmpaque(usuario.tienda(),vista.equals("empaquetados")));
                model.addAttribute("listosEmpaque", operaciones.listosEmpaque(usuario.tienda()));
            }
            case "inventario", "simulacion" -> {
                model.addAttribute("inventario", operaciones.inventario(usuario.tienda()));
                model.addAttribute("motivos", Operaciones.MOTIVOS_INGRESO);
                if (supervisor && vista.equals("simulacion")) model.addAttribute("clientes", operaciones.clientes());
            }
            case "pedidos", "recojos-listos", "recojos-historial", "despachos", "despachos-reparto" -> {
                String estado = vista.equals("recojos-listos") ? "Listo"
                        : vista.equals("recojos-historial") ? "Entregado" : vista.equals("despachos") ? "DESPACHOS" : vista.equals("despachos-reparto") ? "En reparto" : "";
                cargarPedidos(usuario,vista.startsWith("despachos") ? "DELIVERY" : modalidad,q,id,estado,model,atencion,almacen,supervisor);
            }
            case "atender" -> cargarRecojo(usuario,sesionWeb,model);
            default -> throw new IllegalStateException("Vista inesperada");
        }
        return "panel";
    }

    private void cargarPedidos(Operario usuario, String modalidad, String q, Long id, String estado, Model model,
                               boolean atencion, boolean almacen, boolean supervisor) {
        String filtro = atencion ? "RECOJO" : modalidad;
        var pedidos = operaciones.pedidos(usuario.tienda(), filtro, q, estado);
        var seleccionado = pedidos.stream().filter(p -> p.get("id").equals(id)).findFirst()
                .orElse(pedidos.isEmpty() ? null : pedidos.get(0));
        model.addAttribute("pedidos", pedidos);
        model.addAttribute("resumen", operaciones.resumen(usuario.tienda(), filtro));
        model.addAttribute("pedido", seleccionado);
        if (seleccionado == null) return;
        long pedidoId = (Long) seleccionado.get("id");
        model.addAttribute("lineas", operaciones.detalle(pedidoId));
        model.addAttribute("historial", operaciones.historial(pedidoId));
        model.addAttribute("incidencias", operaciones.incidencias(pedidoId));
        model.addAttribute("entregas", (almacen || supervisor) ? operaciones.entrega(pedidoId) : List.of());
        if (almacen && "DELIVERY".equals(seleccionado.get("modalidad"))) {
            var intentos = operaciones.intentosDespacho(pedidoId);
            model.addAttribute("intentos",intentos);
            model.addAttribute("asignado",!intentos.isEmpty() && intentos.get(0).get("resultado")==null);
            model.addAttribute("repartidores",operaciones.repartidores());
            int minimo = operaciones.transporteMinimo(pedidoId);
            model.addAttribute("transporteSugerido",Operaciones.TRANSPORTES.get(minimo));
            model.addAttribute("transportes",Operaciones.TRANSPORTES.subList(minimo,Operaciones.TRANSPORTES.size()));
        }
        String codigo = supervisor && "Listo".equals(seleccionado.get("estado"))
                ? operaciones.codigo(pedidoId) : "";
        model.addAttribute("codigo", codigo);
        model.addAttribute("qr", codigo.isEmpty() ? "" : CodigoQr.imagen(codigo));
    }

    private void cargarRecojo(Operario usuario, HttpSession sesionWeb, Model model) {
        if (!(sesionWeb.getAttribute(RECOJO_VALIDADO) instanceof String codigo)) return;
        try {
            var pedido = operaciones.validarRecojo(codigo,usuario.tienda());
            model.addAttribute("recojo",pedido);
            model.addAttribute("productosRecojo",operaciones.detalle((Long) pedido.get("id")));
        } catch (IllegalStateException error) {
            sesionWeb.removeAttribute(RECOJO_VALIDADO);
            model.addAttribute("error",error.getMessage());
        }
    }

    @PostMapping("/atencion/validar")
    String validarRecojo(@RequestParam String codigo, Principal sesion, HttpSession sesionWeb,
                         RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"recojo");
        sesionWeb.removeAttribute(RECOJO_VALIDADO);
        try {
            operaciones.validarRecojo(codigo,usuario.tienda());
            sesionWeb.setAttribute(RECOJO_VALIDADO,codigo.strip());
        } catch (IllegalStateException error) {
            avisos.addFlashAttribute("error",error.getMessage());
        }
        return "redirect:/?vista=atender";
    }

    @PostMapping("/atencion/confirmar")
    String confirmarRecojo(Principal sesion, HttpSession sesionWeb, RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"recojo");
        if (!(sesionWeb.getAttribute(RECOJO_VALIDADO) instanceof String codigo)) {
            avisos.addFlashAttribute("error","Valida el código antes de confirmar la entrega.");
            return "redirect:/?vista=atender";
        }
        sesionWeb.removeAttribute(RECOJO_VALIDADO);
        try {
            var pedido = operaciones.validarRecojo(codigo,usuario.tienda());
            operaciones.avanzar((Long) pedido.get("id"),"recojo",codigo,usuario);
            avisos.addFlashAttribute("exito","Entrega confirmada para " + pedido.get("referencia") + ".");
        } catch (IllegalStateException error) {
            avisos.addFlashAttribute("error",error.getMessage());
        }
        return "redirect:/?vista=atender";
    }

    @PostMapping("/atencion/limpiar")
    String limpiarRecojo(Principal sesion, HttpSession sesionWeb) {
        autorizar(sesion,"recojo");
        sesionWeb.removeAttribute(RECOJO_VALIDADO);
        return "redirect:/?vista=atender";
    }

    @PostMapping("/simular")
    String simular(@RequestParam long producto, @RequestParam String modalidad, @RequestParam long cliente,
                   Principal sesion, RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"simular");
        try {
            return "redirect:/?vista=pedidos&id=" + operaciones.simular(modalidad, usuario, producto, cliente);
        } catch (IllegalArgumentException | IllegalStateException error) {
            avisos.addFlashAttribute("error", error.getMessage());
            return "redirect:/?vista=simulacion";
        }
    }

    @PostMapping("/venta")
    String venta(@RequestParam long producto, Principal sesion, RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"venta");
        try {
            operaciones.venta(usuario, producto);
            avisos.addFlashAttribute("exito", "Se registró una venta simulada de Atención.");
        } catch (IllegalArgumentException | IllegalStateException error) {
            avisos.addFlashAttribute("error", error.getMessage());
        }
        return "redirect:/?vista=simulacion";
    }

    @PostMapping("/inventario/ingreso")
    String ingreso(@RequestParam long producto, @RequestParam int cantidad, @RequestParam String motivo,
                   Principal sesion, RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"ingreso");
        try {
            operaciones.ingreso(usuario, producto, cantidad, motivo);
            avisos.addFlashAttribute("exito", "Se registró el ingreso de existencias.");
        } catch (IllegalArgumentException | IllegalStateException error) {
            avisos.addFlashAttribute("error", error.getMessage());
        }
        return "redirect:/?vista=inventario";
    }

    @PostMapping("/despachos/{id}/asignar")
    String asignarDespacho(@PathVariable long id,@RequestParam long repartidor,@RequestParam String transporte,
                          Principal sesion,RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"asignar");
        try {
            operaciones.asignarDespacho(id,repartidor,transporte,usuario);
            avisos.addFlashAttribute("exito","Repartidor asignado. Confirma la salida al entregar el paquete.");
        } catch (IllegalStateException error) {
            avisos.addFlashAttribute("error",error.getMessage());
        }
        return "redirect:/?vista=despachos&id="+id;
    }

    @PostMapping("/pedido/{id}")
    String pedido(@PathVariable long id, @RequestParam String accion, @RequestParam(defaultValue="") String valor,
                  Principal sesion, RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,accion);
        if (!List.of("iniciar","incidencia","listo","despacho","entrega","fallo","cancelar").contains(accion))
            throw new AccessDeniedException("Acción no autorizada.");
        try {
            if (accion.equals("incidencia")) operaciones.incidencia(id, valor, usuario);
            else operaciones.avanzar(id, accion, valor, usuario);
        } catch (IllegalArgumentException | IllegalStateException error) {
            avisos.addFlashAttribute("error", error.getMessage());
        }
        if (accion.equals("iniciar")) return "redirect:/?vista=picking";
        if (accion.equals("listo")) return "redirect:/?vista=empaque";
        if (List.of("despacho","entrega","fallo").contains(accion)) return "redirect:/?vista=" + (accion.equals("despacho") ? "despachos-reparto" : "despachos") + "&id=" + id;
        return "redirect:/?vista=pedidos&id=" + id;
    }

    @PostMapping("/pedido/{id}/producto/{producto}/picking")
    String encontrar(@PathVariable long id, @PathVariable long producto,
                     @RequestParam(defaultValue="") String codigo, @RequestParam(defaultValue="false") boolean manual,
                     Principal sesion, RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"encontrar");
        try {
            operaciones.encontrar(id,producto,codigo,manual,usuario);
            avisos.addFlashAttribute("exito","Producto encontrado y enviado a Empaquetado.");
        } catch (IllegalArgumentException | IllegalStateException error) {
            avisos.addFlashAttribute("error",error.getMessage());
        }
        return "redirect:/?vista=picking";
    }

    @PostMapping("/pedido/{id}/producto/{producto}/empaque")
    String empaquetar(@PathVariable long id, @PathVariable long producto,
                      Principal sesion, RedirectAttributes avisos) {
        Operario usuario = autorizar(sesion,"empaquetar");
        try {
            operaciones.empaquetar(id,producto,usuario);
            avisos.addFlashAttribute("exito","Producto empaquetado.");
        } catch (IllegalArgumentException | IllegalStateException error) {
            avisos.addFlashAttribute("error",error.getMessage());
        }
        return "redirect:/?vista=empaque";
    }

    private Operario autorizar(Principal sesion, String accion) {
        Operario usuario = operaciones.operario(sesion.getName());
        if (!usuario.permite(accion)) throw new AccessDeniedException("Acción no autorizada.");
        return usuario;
    }
}






