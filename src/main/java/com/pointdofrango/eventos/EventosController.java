package com.pointdofrango.eventos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/eventos")
@Tag(name = "Tempo real", description = "Server-Sent Events: avisa as telas quando pedidos e comandas mudam")
public class EventosController {

    private final TransmissorEventos transmissor;

    public EventosController(TransmissorEventos transmissor) {
        this.transmissor = transmissor;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Conexão contínua: recebe 'atualizacao' com {tipo, id} a cada mudança")
    public SseEmitter conectar() {
        return transmissor.conectar();
    }
}
