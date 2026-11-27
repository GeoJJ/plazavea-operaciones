package pe.proyecto;

import java.util.Set;

record Operario(long id, String nombre, String rol, long tienda, String sucursal) {
    private static final Set<String> PREPARACION = Set.of("iniciar", "incidencia", "encontrar");
    private static final Set<String> DESPACHO = Set.of("asignar", "despacho", "entrega", "fallo");

    boolean permite(String accion) {
        return switch (rol) {
            case "PICKING" -> PREPARACION.contains(accion);
            case "EMPAQUETADOR" -> accion.equals("empaquetar") || accion.equals("listo");
            case "ATENCION" -> accion.equals("recojo") || accion.equals("venta");
            case "ALMACEN" -> DESPACHO.contains(accion) || accion.equals("ingreso");
            case "SUPERVISOR" -> accion.equals("cancelar") || accion.equals("simular");
            default -> false;
        };
    }
}


