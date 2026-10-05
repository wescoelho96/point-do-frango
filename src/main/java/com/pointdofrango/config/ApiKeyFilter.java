package com.pointdofrango.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Autentica automações pelo header X-Api-Key. Sem chave configurada, a integração fica desligada.
 * Comparação em tempo constante (MessageDigest.isEqual) para evitar timing attack.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String PERFIL = "INTEGRACAO";

    private final byte[] chaveEsperada;

    public ApiKeyFilter(String chave) {
        this.chaveEsperada = chave == null || chave.isBlank() ? null : chave.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recebida = request.getHeader(HEADER);
        if (chaveEsperada != null && recebida != null
                && MessageDigest.isEqual(chaveEsperada, recebida.getBytes(StandardCharsets.UTF_8))) {
            var autenticacao = new UsernamePasswordAuthenticationToken("integracao", null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + PERFIL)));
            SecurityContextHolder.getContext().setAuthentication(autenticacao);
        }
        chain.doFilter(request, response);
    }
}
