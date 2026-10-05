package com.pointdofrango.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String zona, Jwt jwt, Admin admin, Integracao integracao, Seed seed, Android android,
                            Lgpd lgpd) {

    public record Jwt(String secret, int expiracaoHoras) {
    }

    public record Admin(String username, String password) {
    }

    public record Integracao(String apiKey) {
    }

    public record Seed(boolean usuariosDemo) {
    }

    /** Depois de quantos dias nome, telefone e endereço saem dos pedidos e clientes parados são apagados. */
    public record Lgpd(int retencaoDias) {
    }

    /** App da Play Store (TWA): nome do pacote e SHA-256 da chave de assinatura (vírgula separa várias). */
    public record Android(String pacote, String sha256) {
    }
}
