package com.pointdofrango.pedido;

import com.pointdofrango.pedido.PedidoDtos.IntegracaoPedidoRequest;
import com.pointdofrango.pedido.PedidoDtos.PedidoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recebe pedidos já estruturados por automações de WhatsApp (n8n, Evolution API, Z-API) e os lança
 * pelo fluxo normal. Autenticação por X-Api-Key (ver ApiKeyFilter).
 */
@RestController
@RequestMapping("/api/v1/integracoes/whatsapp")
@Tag(name = "Integração WhatsApp", description = "Recebe pedidos de automações externas (header X-Api-Key)")
public class IntegracaoWhatsappController {

    private final PedidoService service;

    public IntegracaoWhatsappController(PedidoService service) {
        this.service = service;
    }

    @PostMapping("/pedidos")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Lança um pedido vindo do WhatsApp (entra no mesmo fluxo do balcão)")
    public PedidoResponse receber(@Valid @RequestBody IntegracaoPedidoRequest req) {
        return PedidoResponse.de(service.lancar(req.comoPedido(CanalVenda.WHATSAPP), "integracao-whatsapp"))
                .semFinanceiro();
    }
}
