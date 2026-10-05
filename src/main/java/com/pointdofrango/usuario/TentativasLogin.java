package com.pointdofrango.usuario;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proteção simples contra força bruta: 5 senhas erradas seguidas bloqueiam o usuário por 15 minutos.
 * Fica em memória (zera no restart), suficiente para uma instância única no Render.
 */
@Component
public class TentativasLogin {

    static final int MAX_FALHAS = 5;
    static final Duration BLOQUEIO = Duration.ofMinutes(15);

    private record Registro(int falhas, Instant bloqueadoAte) {
    }

    private final Map<String, Registro> registros = new ConcurrentHashMap<>();
    private final Clock clock;

    public TentativasLogin(Clock clock) {
        this.clock = clock;
    }

    public boolean bloqueado(String username) {
        Registro r = registros.get(username);
        return r != null && r.bloqueadoAte() != null && Instant.now(clock).isBefore(r.bloqueadoAte());
    }

    public void falhou(String username) {
        registros.compute(username, (k, atual) -> {
            int falhas = (atual == null || atual.bloqueadoAte() != null ? 0 : atual.falhas()) + 1;
            Instant bloqueio = falhas >= MAX_FALHAS ? Instant.now(clock).plus(BLOQUEIO) : null;
            return new Registro(falhas, bloqueio);
        });
    }

    public void sucesso(String username) {
        registros.remove(username);
    }
}
