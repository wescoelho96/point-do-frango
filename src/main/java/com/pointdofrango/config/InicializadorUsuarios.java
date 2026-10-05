package com.pointdofrango.config;

import com.pointdofrango.usuario.Perfil;
import com.pointdofrango.usuario.UsuarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Cria o primeiro administrador a partir de APP_ADMIN_USERNAME e APP_ADMIN_PASSWORD.
 * Não há senha padrão em produção: sem as variáveis, ninguém loga.
 */
@Component
@Order(1)
public class InicializadorUsuarios implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InicializadorUsuarios.class);

    private final UsuarioService usuarios;
    private final AppProperties props;

    public InicializadorUsuarios(UsuarioService usuarios, AppProperties props) {
        this.usuarios = usuarios;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.seed().usuariosDemo()) {
            usuarios.garantirUsuario("Dono (demo)", "admin", "admin123", Perfil.ADMIN);
            usuarios.garantirUsuario("Caixa (demo)", "caixa", "caixa123", Perfil.ATENDENTE);
            usuarios.garantirUsuario("Cozinha (demo)", "cozinha", "cozinha123", Perfil.COZINHA);
            log.warn("Perfil DEMO: usuários admin/admin123, caixa/caixa123 e cozinha/cozinha123 ativos. Não use com dados reais.");
        }

        String username = props.admin().username();
        String senha = props.admin().password();
        if (username != null && !username.isBlank() && senha != null && !senha.isBlank()) {
            if (senha.length() < 8) {
                throw new IllegalStateException("APP_ADMIN_PASSWORD precisa de pelo menos 8 caracteres.");
            }
            usuarios.garantirUsuario("Administrador", username, senha, Perfil.ADMIN);
        } else if (!usuarios.existeAlgum()) {
            log.warn("Nenhum usuário cadastrado. Defina APP_ADMIN_USERNAME e APP_ADMIN_PASSWORD e reinicie.");
        }
    }
}
