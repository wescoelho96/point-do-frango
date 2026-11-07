package com.pointdofrango.usuario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class UsuarioDtos {

    private UsuarioDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 100) String senha) {
    }

    public record LoginResponse(String token, Instant expiraEm, String username, String nome, Perfil perfil,
                                List<Tela> telas) {
    }

    /** Perfil e telas que o login vê; só o dono altera. */
    public record AcessoRequest(@NotNull Perfil perfil, @NotNull @Size(max = 20) List<@NotNull Tela> telas) {
    }

    public record NovoUsuarioRequest(
            @NotBlank @Size(max = 100) String nome,
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "use letras, números, . _ -")
            String username,
            @NotBlank @Size(min = 8, max = 100, message = "mínimo de 8 caracteres") String senha,
            @NotNull Perfil perfil) {
    }

    public record TrocaSenhaRequest(
            @NotBlank String senhaAtual,
            @NotBlank @Size(min = 8, max = 100, message = "mínimo de 8 caracteres") String novaSenha) {
    }

    /** telasDoPerfil: o que o dono pode marcar para este login. */
    public record UsuarioResponse(Long id, String nome, String username, Perfil perfil, boolean ativo,
                                  Instant criadoEm, List<Tela> telas) {

        public static UsuarioResponse de(Usuario u) {
            return new UsuarioResponse(u.getId(), u.getNome(), u.getUsername(), u.getPerfil(), u.isAtivo(),
                    u.getCriadoEm(), u.telas());
        }
    }
}
