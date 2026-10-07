package com.pointdofrango.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pointdofrango.usuario.TokenService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Três cadeias, nesta ordem: integrações (X-Api-Key, automação do WhatsApp), console H2 (só no
 * perfil dev) e o resto da API (JWT com regras por perfil). Sem sessão nem cookie, então CSRF
 * não se aplica: o token vai no header.
 */
@Configuration
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String ATENDENTE = "ATENDENTE";
    private static final String COZINHA = "COZINHA";

    @Bean
    @Order(1)
    SecurityFilterChain integracoes(HttpSecurity http, AppProperties props) throws Exception {
        return http
                .securityMatcher("/api/v1/integracoes/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyFilter(props.integracao().apiKey()), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole(ApiKeyFilter.PERFIL))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    @Order(2)
    @Profile("dev")
    SecurityFilterChain consoleH2(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/h2-console/**")
                .csrf(AbstractHttpConfigurer::disable)
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; object-src 'none'; base-uri 'self'; "
                                        + "frame-ancestors 'none'; form-action 'self'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        // SSE: ao terminar, a conexão faz um "dispatch" ASYNC interno que não traz o token de novo
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // Público: front (PWA), login, documentação, health check, Play Store
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/img/**", "/error").permitAll()
                        .requestMatchers("/manifest.webmanifest", "/sw.js", "/icons/**", "/privacidade.html",
                                "/offline.html", "/.well-known/assetlinks.json").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()

                        // Qualquer usuário logado
                        .requestMatchers("/api/v1/usuarios/me", "/api/v1/usuarios/me/**", "/api/v1/eventos").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/loja").authenticated()

                        // Cozinha (e quem atende): ver a fila e mudar o status
                        .requestMatchers(HttpMethod.GET, "/api/v1/pedidos/cozinha").hasAnyRole(ADMIN, ATENDENTE, COZINHA)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/pedidos/*/status").hasAnyRole(ADMIN, ATENDENTE, COZINHA)

                        // Atendente procura o cliente pelo telefone exato, mas não lista a base inteira
                        .requestMatchers(HttpMethod.GET, "/api/v1/clientes/busca").hasAnyRole(ADMIN, ATENDENTE)

                        // Só o dono
                        .requestMatchers("/api/v1/painel/**", "/api/v1/financeiro/**", "/api/v1/usuarios/**").hasRole(ADMIN)
                        .requestMatchers("/api/v1/clientes/**", "/api/v1/saidas/**", "/api/v1/contador/**", "/api/v1/notas/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/promocoes/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/promocoes/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/promocoes/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/fornecedores/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/fornecedores/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/caixa/historico", "/api/v1/pedidos/cortesias").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/caixa/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/pedidos/*/cancelar", "/api/v1/comandas/*/cancelar").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/loja", "/api/v1/loja/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/bairros/**", "/api/v1/colaboradores/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/bairros/**", "/api/v1/colaboradores/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/insumos/reposicao").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/insumos/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/insumos/**", "/api/v1/produtos/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/insumos/**", "/api/v1/produtos/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/insumos/**", "/api/v1/produtos/**").hasRole(ADMIN)

                        // Dono e atendente: PDV, cozinha, consulta de cardápio e estoque
                        .requestMatchers("/api/**").hasAnyRole(ADMIN, ATENDENTE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conversorDePerfis())))
                .build();
    }

    /** Claim "roles" do token vira ROLE_ADMIN, ROLE_ATENDENTE etc. */
    private static JwtAuthenticationConverter conversorDePerfis() {
        JwtGrantedAuthoritiesConverter perfis = new JwtGrantedAuthoritiesConverter();
        perfis.setAuthoritiesClaimName("roles");
        perfis.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(perfis);
        return conversor;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecretKey chaveJwt(AppProperties props) {
        String segredo = props.jwt().secret();
        if (segredo == null || segredo.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET ausente ou curto: defina um valor aleatório com pelo menos 32 caracteres.");
        }
        return new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey chave) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey chave) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.EMISSOR));
        return decoder;
    }
}
