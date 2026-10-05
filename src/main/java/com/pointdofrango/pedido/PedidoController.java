package com.pointdofrango.pedido;

import com.pointdofrango.pedido.PedidoDtos.CancelamentoRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoDtos.PedidoResponse;
import com.pointdofrango.pedido.PedidoDtos.StatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import com.pointdofrango.financeiro.FormaPagamento;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/v1/pedidos")
@Tag(name = "Pedidos", description = "Lançamento (balcão/WhatsApp), fila da cozinha e cancelamento")
public class PedidoController {

    private final PedidoService service;
    private final Clock clock;
    private final ZoneId zona;

    public PedidoController(PedidoService service, Clock clock, ZoneId zona) {
        this.service = service;
        this.clock = clock;
        this.zona = zona;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Lança um pedido: baixa o estoque pela ficha técnica e calcula os potes financeiros")
    public PedidoResponse lancar(@Valid @RequestBody NovoPedidoRequest req, Authentication auth) {
        if (req.formaPagamento() == FormaPagamento.CORTESIA && !admin(auth)) {
            throw new AccessDeniedException("Só o dono pode lançar cortesia.");
        }
        return visivelPara(auth, service.lancar(req, auth.getName()));
    }

    @GetMapping("/cortesias")
    @Operation(summary = "Pedidos dados de graça nos últimos dias (somente ADMIN)")
    public List<PedidoResponse> cortesias(@RequestParam(defaultValue = "60") int dias) {
        return service.cortesias(Math.min(Math.max(dias, 1), 366)).stream().map(PedidoResponse::de).toList();
    }

    @PostMapping("/plataforma")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Lança um pedido do iFood/99Food com o valor e as taxas do app (baixa estoque e entra no rateio)")
    public PedidoResponse lancarDePlataforma(@Valid @RequestBody PedidoDtos.PedidoPlataformaRequest req, Authentication auth) {
        return visivelPara(auth, service.lancarDePlataforma(req, auth.getName()));
    }

    @GetMapping
    @Operation(summary = "Pedidos de um dia (padrão: hoje, no fuso da loja)")
    public List<PedidoResponse> doDia(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                      LocalDate data, Authentication auth) {
        LocalDate dia = data != null ? data : LocalDate.now(clock.withZone(zona));
        return service.doDia(dia).stream().map(p -> visivelPara(auth, p)).toList();
    }

    @GetMapping("/cozinha")
    @Operation(summary = "Fila da cozinha (em preparo e prontos), do mais antigo para o mais novo")
    public List<PedidoResponse> cozinha() {
        return service.filaDaCozinha().stream().map(p -> PedidoResponse.de(p).paraCozinha()).toList();
    }

    @GetMapping("/{id}")
    public PedidoResponse buscar(@PathVariable Long id, Authentication auth) {
        return visivelPara(auth, service.buscar(id));
    }

    @PatchMapping("/{id}/status")
    public PedidoResponse mudarStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req, Authentication auth) {
        return visivelPara(auth, service.mudarStatus(id, req.status()));
    }

    @PatchMapping("/{id}/entregador")
    @Operation(summary = "Define (ou tira) o motoboy de um pedido de entrega")
    public PedidoResponse atribuirEntregador(@PathVariable Long id, @RequestBody PedidoDtos.EntregadorRequest req,
                                             Authentication auth) {
        return visivelPara(auth, service.atribuirEntregador(id, req.entregadorId(), req.dono()));
    }

    @PostMapping("/{id}/cancelar")
    @Operation(summary = "Cancela (somente ADMIN): devolve os insumos ao estoque e tira a venda do financeiro")
    public PedidoResponse cancelar(@PathVariable Long id, @Valid @RequestBody CancelamentoRequest req,
                                   Authentication auth) {
        return visivelPara(auth, service.cancelar(id, req.motivo(), auth.getName()));
    }

    /** Atendente vê o pedido, mas não os custos e o lucro. */
    private static PedidoResponse visivelPara(Authentication auth, Pedido pedido) {
        PedidoResponse resposta = PedidoResponse.de(pedido);
        return admin(auth) ? resposta : resposta.semFinanceiro();
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
