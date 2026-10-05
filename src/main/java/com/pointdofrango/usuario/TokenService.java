package com.pointdofrango.usuario;

import com.pointdofrango.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Emite o JWT do login, assinado com HS256 (APP_JWT_SECRET). O perfil vai na claim "roles".
 */
@Service
public class TokenService {

    public static final String EMISSOR = "point-do-frango";

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration validade;

    public TokenService(JwtEncoder encoder, Clock clock, AppProperties props) {
        this.encoder = encoder;
        this.clock = clock;
        this.validade = Duration.ofHours(props.jwt().expiracaoHoras());
    }

    public record TokenEmitido(String token, Instant expiraEm) {
    }

    public TokenEmitido emitir(Usuario usuario) {
        Instant agora = Instant.now(clock);
        Instant expira = agora.plus(validade);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(EMISSOR)
                .subject(usuario.getUsername())
                .issuedAt(agora)
                .expiresAt(expira)
                .claim("nome", usuario.getNome())
                .claim("roles", List.of(usuario.getPerfil().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenEmitido(token, expira);
    }
}
