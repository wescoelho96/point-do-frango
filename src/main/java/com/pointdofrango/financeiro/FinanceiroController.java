package com.pointdofrango.financeiro;

import com.pointdofrango.financeiro.FinanceiroDtos.ConfiguracaoRequest;
import com.pointdofrango.financeiro.FinanceiroDtos.ConfiguracaoResponse;
import com.pointdofrango.financeiro.FinanceiroDtos.DespesaRequest;
import com.pointdofrango.financeiro.FinanceiroDtos.DespesaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/financeiro")
@Tag(name = "Financeiro", description = "Percentuais do rateio, taxas de pagamento e contas fixas (somente ADMIN)")
public class FinanceiroController {

    private final ConfiguracaoFinanceiraService service;

    public FinanceiroController(ConfiguracaoFinanceiraService service) {
        this.service = service;
    }

    @GetMapping("/configuracao")
    public ConfiguracaoResponse configuracao() {
        return ConfiguracaoResponse.de(service.obter());
    }

    @PutMapping("/configuracao")
    @Operation(summary = "Altera os percentuais (vale só para os próximos pedidos)")
    public ConfiguracaoResponse atualizar(@Valid @RequestBody ConfiguracaoRequest req) {
        return ConfiguracaoResponse.de(service.atualizar(req));
    }

    @PutMapping("/regime")
    @Operation(summary = "Regime tributário (MEI/ME) e teto de faturamento anual")
    public ConfiguracaoResponse regime(@Valid @RequestBody FinanceiroDtos.RegimeRequest req) {
        return ConfiguracaoResponse.de(service.definirRegime(req.regime(), req.limiteFaturamentoAnual()));
    }

    @GetMapping("/despesas")
    public List<DespesaResponse> despesas() {
        return service.listarDespesas().stream().map(DespesaResponse::de).toList();
    }

    @PostMapping("/despesas")
    @ResponseStatus(HttpStatus.CREATED)
    public DespesaResponse criarDespesa(@Valid @RequestBody DespesaRequest req) {
        return DespesaResponse.de(service.criarDespesa(req));
    }

    @PutMapping("/despesas/{id}")
    public DespesaResponse atualizarDespesa(@PathVariable Long id, @Valid @RequestBody DespesaRequest req) {
        return DespesaResponse.de(service.atualizarDespesa(id, req));
    }

    @DeleteMapping("/despesas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removerDespesa(@PathVariable Long id) {
        service.removerDespesa(id);
    }
}
