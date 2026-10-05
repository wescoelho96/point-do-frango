package com.pointdofrango.usuario;

import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import com.pointdofrango.usuario.UsuarioDtos.LoginRequest;
import com.pointdofrango.usuario.UsuarioDtos.LoginResponse;
import com.pointdofrango.usuario.UsuarioDtos.NovoUsuarioRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final TentativasLogin tentativas;
    private final Clock clock;
    /** Alvo do BCrypt quando o usuário não existe (ver login). */
    private final String hashFalso;

    public UsuarioService(UsuarioRepository usuarios, PasswordEncoder encoder, TokenService tokens,
                          TentativasLogin tentativas, Clock clock) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.tokens = tokens;
        this.tentativas = tentativas;
        this.clock = clock;
        this.hashFalso = encoder.encode("senha-que-ninguem-usa");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        String username = req.username().trim().toLowerCase();
        if (tentativas.bloqueado(username)) {
            throw new LockedException("Muitas tentativas. Aguarde 15 minutos.");
        }
        Usuario usuario = usuarios.findByUsername(username).orElse(null);
        // Sempre roda o BCrypt, mesmo sem usuário: o tempo de resposta não revela se o login existe.
        boolean senhaOk = encoder.matches(req.senha(), usuario != null ? usuario.getSenhaHash() : hashFalso);
        if (usuario == null || !senhaOk || !usuario.isAtivo()) {
            tentativas.falhou(username);
            throw new BadCredentialsException("Usuário ou senha inválidos.");
        }
        tentativas.sucesso(username);
        TokenService.TokenEmitido token = tokens.emitir(usuario);
        return new LoginResponse(token.token(), token.expiraEm(), usuario.getUsername(), usuario.getNome(),
                usuario.getPerfil());
    }

    @Transactional(readOnly = true)
    public List<Usuario> listar() {
        return usuarios.findAllByOrderByNomeAsc();
    }

    @Transactional(readOnly = true)
    public Usuario buscarPorUsername(String username) {
        return usuarios.findByUsername(username).orElseThrow(() -> new RecursoNaoEncontradoException("Usuário", username));
    }

    @Transactional
    public Usuario criar(NovoUsuarioRequest req) {
        String username = req.username().trim().toLowerCase();
        if (usuarios.existsByUsername(username)) {
            throw new RegraDeNegocioException("Usuário já existe: " + username);
        }
        return usuarios.save(new Usuario(req.nome(), username, encoder.encode(req.senha()), req.perfil(),
                Instant.now(clock)));
    }

    @Transactional
    public void alterarAtivo(Long id, boolean ativo, String quemPediu) {
        Usuario usuario = usuarios.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Usuário", id));
        if (!ativo && usuario.getUsername().equals(quemPediu)) {
            throw new RegraDeNegocioException("Você não pode desativar o próprio usuário.");
        }
        usuario.alterarAtivo(ativo);
    }

    @Transactional
    public void trocarSenha(String username, String senhaAtual, String novaSenha) {
        Usuario usuario = buscarPorUsername(username);
        if (!encoder.matches(senhaAtual, usuario.getSenhaHash())) {
            throw new RegraDeNegocioException("Senha atual incorreta.");
        }
        usuario.trocarSenha(encoder.encode(novaSenha));
    }

    /** Usado na inicialização. */
    @Transactional
    public void garantirUsuario(String nome, String username, String senha, Perfil perfil) {
        if (!usuarios.existsByUsername(username.toLowerCase())) {
            usuarios.save(new Usuario(nome, username, encoder.encode(senha), perfil, Instant.now(clock)));
        }
    }

    @Transactional(readOnly = true)
    public boolean existeAlgum() {
        return usuarios.count() > 0;
    }
}
