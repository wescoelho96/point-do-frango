package com.pointdofrango.caixa;

import com.pointdofrango.caixa.CaixaDtos.AberturaRequest;
import com.pointdofrango.caixa.CaixaDtos.AcertoRequest;
import com.pointdofrango.caixa.CaixaDtos.CaixaResponse;
import com.pointdofrango.caixa.CaixaDtos.DiariaRequest;
import com.pointdofrango.caixa.CaixaDtos.FechamentoRequest;
import com.pointdofrango.caixa.CaixaDtos.PendenciaResponse;
import com.pointdofrango.caixa.CaixaDtos.SaidaRequest;
import com.pointdofrango.caixa.CaixaDtos.SuprimentoRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/caixa")
@Tag(name = "Caixa", description = "Abertura, saídas (diárias, motoboy), suprimento e fechamento do caixa do dia")
public class CaixaController {

    private final CaixaService service;

    public CaixaController(CaixaService service) {
        this.service = service;
    }

    @GetMapping("/atual")
    @Operation(summary = "Caixa aberto agora (204 se estiver fechado)")
    public ResponseEntity<CaixaResponse> atual(Authentication auth) {
        return service.atual().map(c -> ResponseEntity.ok(resposta(c, auth)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/abrir")
    @ResponseStatus(HttpStatus.CREATED)
    public CaixaResponse abrir(@Valid @RequestBody AberturaRequest req, Authentication auth) {
        return resposta(service.abrir(req.valorAbertura(), auth.getName()), auth);
    }

    @PostMapping("/saidas")
    public CaixaResponse saida(@Valid @RequestBody SaidaRequest req, Authentication auth) {
        return resposta(service.registrarSaida(req.categoria(), req.descricao(), req.valor(), req.forma(),
                req.colaboradorId(), req.fornecedorId(), auth.getName()), auth);
    }

    @PostMapping("/suprimentos")
    public CaixaResponse suprimento(@Valid @RequestBody SuprimentoRequest req, Authentication auth) {
        return resposta(service.registrarSuprimento(req.valor(), req.descricao(), auth.getName()), auth);
    }

    @PostMapping("/diarias")
    @Operation(summary = "Paga a diária de um funcionário (valor em branco = valor do cadastro)")
    public CaixaResponse diaria(@Valid @RequestBody DiariaRequest req, Authentication auth) {
        return resposta(service.pagarDiaria(req.colaboradorId(), req.valor(), req.forma(), auth.getName()), auth);
    }

    @GetMapping("/motoboys")
    @Operation(summary = "Quanto cada motoboy tem a receber: diária + taxas das entregas ainda não pagas")
    public List<PendenciaResponse> pendencias() {
        return service.pendenciasMotoboys().stream().map(PendenciaResponse::de).toList();
    }

    @PostMapping("/motoboys/{id}/acerto")
    @Operation(summary = "Paga o motoboy (diária + taxas) e marca as entregas como pagas")
    public CaixaResponse acerto(@PathVariable Long id, @Valid @RequestBody AcertoRequest req, Authentication auth) {
        return resposta(service.acertarMotoboy(id, req.incluirDiaria(), req.forma(), auth.getName()), auth);
    }

    @DeleteMapping("/movimentos/{id}")
    @Operation(summary = "Remove um lançamento errado do caixa aberto (somente ADMIN)")
    public CaixaResponse removerMovimento(@PathVariable Long id, Authentication auth) {
        return resposta(service.removerMovimento(id, auth.getName()), auth);
    }

    @PostMapping("/fechar")
    public CaixaResponse fechar(@Valid @RequestBody FechamentoRequest req, Authentication auth) {
        return resposta(service.fechar(req.dinheiroContado(), req.observacao(), auth.getName()), auth);
    }

    @GetMapping("/historico")
    @Operation(summary = "Últimos 30 caixas fechados (somente ADMIN)")
    public List<CaixaResponse> historico(Authentication auth) {
        return service.historico().stream().map(c -> resposta(c, auth)).toList();
    }

    private CaixaResponse resposta(Caixa caixa, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return CaixaResponse.de(caixa, service.totais(caixa), admin);
    }
}
