package com.pointdofrango.pedido;

import com.pointdofrango.loja.LojaService;
import com.pointdofrango.pedido.ComandaDtos.AbrirRequest;
import com.pointdofrango.pedido.ComandaDtos.CancelarRequest;
import com.pointdofrango.pedido.ComandaDtos.ComandaResponse;
import com.pointdofrango.pedido.ComandaDtos.FecharRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/v1/comandas")
@Tag(name = "Comandas", description = "Conta de mesa/cliente: vários pedidos, pagamento no fechamento, taxa de serviço opcional")
public class ComandaController {

    private final ComandaService service;
    private final LojaService loja;
    private final Clock clock;
    private final ZoneId zona;

    public ComandaController(ComandaService service, LojaService loja, Clock clock, ZoneId zona) {
        this.service = service;
        this.loja = loja;
        this.clock = clock;
        this.zona = zona;
    }

    @GetMapping
    @Operation(summary = "Comandas abertas (com a prévia da conta)")
    public List<ComandaResponse> abertas() {
        BigDecimal servico = percentual();
        return service.abertas().stream().map(c -> ComandaResponse.de(c, servico)).toList();
    }

    @GetMapping("/encerradas")
    @Operation(summary = "Comandas fechadas/canceladas de um período (padrão: hoje), mais recentes primeiro")
    public List<ComandaResponse> encerradas(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        BigDecimal servico = percentual();
        LocalDate ate = fim != null ? fim : LocalDate.now(clock.withZone(zona));
        LocalDate de = inicio != null ? inicio : ate;
        return service.encerradasNoPeriodo(de, ate).stream().map(c -> ComandaResponse.de(c, servico)).toList();
    }

    @GetMapping("/{id}")
    public ComandaResponse buscar(@PathVariable Long id) {
        return ComandaResponse.de(service.buscar(id), percentual());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Abre uma comanda (ex.: \"Mesa 4\"). Depois lance pedidos com comandaId")
    public ComandaResponse abrir(@Valid @RequestBody AbrirRequest req, Authentication auth) {
        return ComandaResponse.de(service.abrir(req.identificacao(), req.observacao(), auth.getName()), percentual());
    }

    @PostMapping("/{id}/fechar")
    @Operation(summary = "Fecha a conta: forma de pagamento e se cobra (ou não) a taxa de serviço")
    public ComandaResponse fechar(@PathVariable Long id, @Valid @RequestBody FecharRequest req, Authentication auth) {
        service.fechar(id, req.formaPagamento(), req.cobrarServico(), req.valorRecebido(), auth.getName());
        return ComandaResponse.de(service.buscar(id), percentual());
    }

    @PostMapping("/{id}/cancelar")
    @Operation(summary = "Cancela a comanda e todos os pedidos dela (somente ADMIN)")
    public ComandaResponse cancelar(@PathVariable Long id, @Valid @RequestBody CancelarRequest req, Authentication auth) {
        service.cancelar(id, req.motivo(), auth.getName());
        return ComandaResponse.de(service.buscar(id), percentual());
    }

    private BigDecimal percentual() {
        return loja.obter().getPercentualServico();
    }
}
