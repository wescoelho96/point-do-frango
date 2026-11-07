package com.pointdofrango.site;

import com.pointdofrango.site.PedidoOnlineDtos.Cardapio;
import com.pointdofrango.site.PedidoOnlineDtos.PedidoCriado;
import com.pointdofrango.site.PedidoOnlineDtos.PedidoRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cardápio online: público, sem login. O link vai na mensagem automática do WhatsApp. */
@RestController
@RequestMapping("/api/v1/publico")
@Tag(name = "Cardápio online", description = "Público: o cliente monta o pedido e ele vai direto para a cozinha")
public class PedidoOnlineController {

    private final PedidoOnlineService service;

    public PedidoOnlineController(PedidoOnlineService service) {
        this.service = service;
    }

    @GetMapping("/cardapio")
    @Operation(summary = "Produtos ativos com preço, bairros com a taxa de entrega e se a loja está aberta")
    public Cardapio cardapio() {
        return service.cardapio();
    }

    @PostMapping("/pedidos")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cliente faz o pedido: entra na cozinha e no histórico como canal SITE")
    public PedidoCriado pedir(@Valid @RequestBody PedidoRequest req, HttpServletRequest http) {
        // IP já resolvido pelo Tomcat a partir dos proxies confiáveis (forward-headers-strategy: native)
        return service.pedir(req, http.getRemoteAddr());
    }
}
