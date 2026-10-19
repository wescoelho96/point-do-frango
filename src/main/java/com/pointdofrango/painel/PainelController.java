package com.pointdofrango.painel;

import com.pointdofrango.painel.PainelDtos.MargemProduto;
import com.pointdofrango.painel.PainelDtos.Resumo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/v1/painel")
@Tag(name = "Painel financeiro", description = "Para onde foi cada real vendido (somente ADMIN)")
public class PainelController {

    private final PainelService service;
    private final PainelVendasService vendas;
    private final ConferenciaService conferencia;
    private final Clock clock;
    private final ZoneId zona;

    public PainelController(PainelService service, PainelVendasService vendas, ConferenciaService conferencia, Clock clock,
                            ZoneId zona) {
        this.vendas = vendas;
        this.conferencia = conferencia;
        this.service = service;
        this.clock = clock;
        this.zona = zona;
    }

    @GetMapping("/conferencia")
    @Operation(summary = "Conferência pelo dinheiro desde a primeira compra: comprado, baixado, em estoque e o que sumiu")
    public ConferenciaService.Conferencia conferencia() {
        return conferencia.conferir();
    }

    @GetMapping("/vendas")
    @Operation(summary = "Vendas de hoje contra a média dos dias anteriores (metas), por hora, por dia da semana e por produto")
    public PainelVendasService.PainelVendas vendas(@RequestParam(defaultValue = "30") int dias) {
        return vendas.painel(dias);
    }

    @GetMapping("/margens")
    @Operation(summary = "Lucro e margem de cada produto vendido no período (padrão: últimos 30 dias)")
    public List<MargemProduto> margens(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        LocalDate ate = fim != null ? fim : LocalDate.now(clock.withZone(zona));
        return service.margens(inicio != null ? inicio : ate.minusDays(29), ate);
    }

    @GetMapping("/resumo")
    @Operation(summary = "Potes do período (padrão: hoje): reposição, contas, taxas, pró-labore e reserva")
    public Resumo resumo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        LocalDate hoje = LocalDate.now(clock.withZone(zona));
        LocalDate ate = fim != null ? fim : hoje;
        LocalDate de = inicio != null ? inicio : ate;
        return service.resumo(de, ate);
    }
}
