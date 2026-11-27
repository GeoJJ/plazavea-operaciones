package pe.proyecto;

import org.springframework.boot.ApplicationRunner;
import java.util.Map;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Acceso {
    @Bean
    SecurityFilterChain reglas(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(reglas -> reglas
                .requestMatchers("/login", "/panel.css", "/panel.js", "/error").permitAll()
                .requestMatchers("/simular", "/correo").hasRole("SUPERVISOR")
                .requestMatchers("/venta", "/atencion/**").hasRole("ATENCION")
                .requestMatchers("/inventario/ingreso").hasRole("ALMACEN")
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/", true))
            .logout(salida -> salida.logoutSuccessUrl("/login?logout"))
            .build();
    }

    @Bean
    PasswordEncoder contrasenas() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService usuarios(JdbcTemplate db) {
        return login -> db.query("""
            SELECT usuario, clave_hash, rol FROM usuario_operativo WHERE usuario=? AND activo
            """, (r, fila) -> User.withUsername(r.getString("usuario"))
                .password(r.getString("clave_hash")).roles(r.getString("rol")).build(), login.toLowerCase(Locale.ROOT))
            .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
    }

    @Bean
    ApplicationRunner cuentasAcademicas(JdbcTemplate db, PasswordEncoder encoder, Operaciones operaciones,
            @Value("${panel.demo.password:PlazaVea-Demo27}") String clave,
            @Value("${panel.demo.enabled:true}") boolean habilitado) {
        return args -> {
            if (!habilitado) return;
            String hash = encoder.encode(clave);
            crearCuenta(db, "picking", "Preparación de pedidos", "PICKING", hash);
            crearCuenta(db, "atencion", "Atención", "ATENCION", hash);
            crearCuenta(db, "empaquetador", "Empaquetado", "EMPAQUETADOR", hash);
            crearCuenta(db, "almacen", "Almacén", "ALMACEN", hash);
            crearCuenta(db, "supervisor", "Supervisión", "SUPERVISOR", hash);
            Operario supervisor = operaciones.operario("supervisor");
            pedidoInicial(operaciones, supervisor, "PV-DEMO-101", "RECOJO", "CLI-001", Map.of("AU-410",1,"HG-210",2));
            pedidoInicial(operaciones, supervisor, "PV-DEMO-102", "DELIVERY", "CLI-002", Map.of("EL-430",1,"TE-110",1));
            pedidoInicial(operaciones, supervisor, "PV-DEMO-103", "RECOJO", "CLI-003", Map.of("HG-240",1));
            pedidoInicial(operaciones, supervisor, "PV-DEMO-104", "DELIVERY", "CLI-004", Map.of("TE-140",1,"HG-280",1));
            try {
                operaciones.prepararEjemploCorreo(supervisor,operaciones.operario("picking"),
                        operaciones.operario("empaquetador"));
            } catch (IllegalStateException error) {
                System.getLogger(Acceso.class.getName()).log(System.Logger.Level.INFO,
                        "Ejemplo de correo omitido: " + error.getMessage());
            }
        };
    }

    private void pedidoInicial(Operaciones operaciones, Operario supervisor, String referencia, String modalidad,
                               String referenciaCliente, Map<String,Integer> productos) {
        try {
            operaciones.pedidoDemostracion(supervisor,referencia,modalidad,referenciaCliente,productos);
        } catch (IllegalStateException error) {
            System.getLogger(Acceso.class.getName()).log(System.Logger.Level.INFO,
                    "Pedido de muestra omitido: " + referencia + ". " + error.getMessage());
        }
    }

    private void crearCuenta(JdbcTemplate db, String login, String nombre, String rol, String hash) {
        db.update("""
            INSERT INTO usuario_operativo (usuario,nombre,clave_hash,rol,tienda_id)
            SELECT ?,?,?,?,id FROM tienda WHERE nombre='Plaza Vea · Av. La Marina, Arequipa'
            ON CONFLICT (usuario) DO NOTHING
            """, login, nombre, hash, rol);
    }
}







