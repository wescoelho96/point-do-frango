package com.pointdofrango.eventos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Conexões SSE abertas (uma por tela). Só funciona com uma instância; com várias, os eventos
 * precisariam passar por um broker (Redis pub/sub ou LISTEN/NOTIFY do PostgreSQL).
 */
@Component
public class TransmissorEventos {

    private static final Logger log = LoggerFactory.getLogger(TransmissorEventos.class);
    private static final Duration VALIDADE_CONEXAO = Duration.ofMinutes(30);

    private final List<SseEmitter> conexoes = new CopyOnWriteArrayList<>();

    public SseEmitter conectar() {
        SseEmitter emitter = new SseEmitter(VALIDADE_CONEXAO.toMillis());
        conexoes.add(emitter);
        emitter.onCompletion(() -> conexoes.remove(emitter));
        emitter.onTimeout(() -> conexoes.remove(emitter));
        emitter.onError(e -> conexoes.remove(emitter));
        enviar(emitter, SseEmitter.event().name("conectado").data("ok"));
        return emitter;
    }

    // Só após o commit: se a transação falhar (ex.: falta de estoque), a cozinha não vê pedido fantasma.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void transmitir(EventoTempoReal evento) {
        conexoes.forEach(c -> enviar(c, SseEmitter.event().name("atualizacao").data(evento)));
    }

    // O proxy do Render derruba conexões paradas.
    @Scheduled(fixedRate = 25_000)
    void manterVivas() {
        conexoes.forEach(c -> enviar(c, SseEmitter.event().comment("ping")));
    }

    int conexoesAbertas() {
        return conexoes.size();
    }

    private void enviar(SseEmitter emitter, SseEmitter.SseEventBuilder evento) {
        try {
            emitter.send(evento);
        } catch (IOException | IllegalStateException e) {
            // tela fechada ou sem rede; o navegador reconecta sozinho
            conexoes.remove(emitter);
            log.debug("Conexão SSE descartada: {}", e.getMessage());
        }
    }
}
