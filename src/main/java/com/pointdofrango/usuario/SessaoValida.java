package com.pointdofrango.usuario;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * O token só vale enquanto o login continuar como estava quando ele foi emitido: usuário desativado,
 * senha trocada ou perfil alterado derrubam na hora as sessões abertas em qualquer aparelho,
 * sem esperar as 12 h de validade.
 */
@Component
public class SessaoValida implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult ENCERRADA = OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Sessão encerrada. Entre de novo.", null));

    private final UsuarioRepository usuarios;

    public SessaoValida(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Usuario u = usuarios.findByUsername(token.getSubject()).orElse(null);
        if (u == null || !u.isAtivo()) {
            return ENCERRADA;
        }
        List<String> perfis = token.getClaimAsStringList("roles");
        if (perfis == null || !perfis.equals(List.of(u.getPerfil().name()))) {
            return ENCERRADA;
        }
        Instant emitido = token.getIssuedAt();
        if (u.getSessoesValidasDesde() != null && (emitido == null || emitido.isBefore(u.getSessoesValidasDesde()))) {
            return ENCERRADA;
        }
        return OAuth2TokenValidatorResult.success();
    }
}
