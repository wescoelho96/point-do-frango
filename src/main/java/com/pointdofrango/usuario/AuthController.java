package com.pointdofrango.usuario;

import com.pointdofrango.usuario.UsuarioDtos.LoginRequest;
import com.pointdofrango.usuario.UsuarioDtos.LoginResponse;
import com.pointdofrango.usuario.UsuarioDtos.NovoUsuarioRequest;
import com.pointdofrango.usuario.UsuarioDtos.TrocaSenhaRequest;
import com.pointdofrango.usuario.UsuarioDtos.UsuarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Autenticação e usuários")
public class AuthController {

    private final UsuarioService service;

    public AuthController(UsuarioService service) {
        this.service = service;
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Troca usuário/senha por um token JWT (válido por 12 h)")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return service.login(req);
    }

    @GetMapping("/usuarios/me")
    public UsuarioResponse eu(Authentication auth) {
        return UsuarioResponse.de(service.buscarPorUsername(auth.getName()));
    }

    @PutMapping("/usuarios/me/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trocarSenha(@Valid @RequestBody TrocaSenhaRequest req, Authentication auth) {
        service.trocarSenha(auth.getName(), req.senhaAtual(), req.novaSenha());
    }

    @GetMapping("/usuarios")
    public List<UsuarioResponse> listar() {
        return service.listar().stream().map(UsuarioResponse::de).toList();
    }

    @PostMapping("/usuarios")
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioResponse criar(@Valid @RequestBody NovoUsuarioRequest req) {
        return UsuarioResponse.de(service.criar(req));
    }

    @PatchMapping("/usuarios/{id}/ativo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void alterarAtivo(@PathVariable Long id, @RequestParam boolean valor, Authentication auth) {
        service.alterarAtivo(id, valor, auth.getName());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail credenciaisInvalidas(BadCredentialsException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        p.setTitle("Login inválido");
        return p;
    }

    @ExceptionHandler(LockedException.class)
    ProblemDetail bloqueado(LockedException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        p.setTitle("Login bloqueado temporariamente");
        return p;
    }
}
