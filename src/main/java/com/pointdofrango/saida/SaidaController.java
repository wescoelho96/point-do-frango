package com.pointdofrango.saida;

import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.saida.RelatorioSaidasService.Relatorio;
import com.pointdofrango.saida.SaidaService.NovaSaida;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Saídas", description = "Pagamentos do mês (equipe, fornecedores, mercado, impostos, pró-labore) e fornecedores")
public class SaidaController {

    public record SaidaRequest(LocalDate data, @NotNull CategoriaSaida categoria,
                               @NotBlank @Size(max = 150) String descricao,
                               @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("999999") BigDecimal valor,
                               @NotNull FormaPagamento forma, Long fornecedorId, Long colaboradorId) {
    }

    public record PagarContaRequest(@DecimalMin(value = "0", inclusive = false) @DecimalMax("999999") BigDecimal valor,
                                    @NotNull FormaPagamento forma, LocalDate data, @Size(max = 60) String codigoBarras) {
    }

    public record FornecedorRequest(@NotBlank @Size(max = 100) String nome, @Size(max = 100) String fornece,
                                    @Size(max = 20) String telefone, @Size(max = 20) String documento,
                                    @Size(max = 255) String observacao, Boolean ativo) {
    }

    public record FornecedorResponse(Long id, String nome, String fornece, String telefone, String documento,
                                     String observacao, boolean ativo) {
        static FornecedorResponse de(Fornecedor f) {
            return new FornecedorResponse(f.getId(), f.getNome(), f.getFornece(), f.getTelefone(), f.getDocumento(),
                    f.getObservacao(), f.isAtivo());
        }
    }

    private final SaidaService service;
    private final RelatorioSaidasService relatorio;
    private final Clock clock;
    private final ZoneId zona;

    public SaidaController(SaidaService service, RelatorioSaidasService relatorio, Clock clock, ZoneId zona) {
        this.service = service;
        this.relatorio = relatorio;
        this.clock = clock;
        this.zona = zona;
    }

    @GetMapping("/saidas/mes")
    @Operation(summary = "Tudo o que saiu no mês (caixa + pagamentos por fora), por categoria, pessoa e fornecedor")
    public Relatorio doMes(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return relatorio.doMes(mes != null ? mes : YearMonth.now(clock.withZone(zona)));
    }

    @PostMapping("/saidas")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Lança um pagamento feito por fora do caixa (PIX, boleto, cartão)")
    public void lancar(@Valid @RequestBody SaidaRequest r, Authentication auth) {
        service.lancar(new NovaSaida(r.data(), r.categoria(), r.descricao(), r.valor(), r.forma(), r.fornecedorId(),
                r.colaboradorId(), null), auth.getName());
    }

    @PostMapping("/saidas/contas-fixas/{id}/pagar")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Marca a conta fixa como paga no mês (valor em branco = valor cadastrado)")
    public void pagarConta(@PathVariable Long id, @Valid @RequestBody PagarContaRequest r, Authentication auth) {
        service.pagarContaFixa(id, r.valor(), r.forma(), r.data(), r.codigoBarras(), auth.getName());
    }

    @GetMapping("/saidas/boleto")
    @Operation(summary = "Lê valor (e vencimento, no boleto bancário) pelo código de barras ou linha digitável")
    public CodigoDeBarras.Leitura lerBoleto(@RequestParam String codigo) {
        return CodigoDeBarras.ler(codigo);
    }

    @DeleteMapping("/saidas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable Long id, Authentication auth) {
        service.remover(id, auth.getName());
    }

    @GetMapping("/fornecedores")
    public List<FornecedorResponse> fornecedores() {
        return service.fornecedores().stream().map(FornecedorResponse::de).toList();
    }

    @PostMapping("/fornecedores")
    @ResponseStatus(HttpStatus.CREATED)
    public FornecedorResponse criarFornecedor(@Valid @RequestBody FornecedorRequest r) {
        return FornecedorResponse.de(service.salvarFornecedor(null, r.nome(), r.fornece(), r.telefone(), r.documento(),
                r.observacao(), true));
    }

    @PutMapping("/fornecedores/{id}")
    public FornecedorResponse alterarFornecedor(@PathVariable Long id, @Valid @RequestBody FornecedorRequest r) {
        return FornecedorResponse.de(service.salvarFornecedor(id, r.nome(), r.fornece(), r.telefone(), r.documento(),
                r.observacao(), r.ativo() == null || r.ativo()));
    }
}
