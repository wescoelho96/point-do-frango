package com.pointdofrango.estoque;

import com.pointdofrango.estoque.EstoqueDtos.AjusteRequest;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoResponse;
import com.pointdofrango.estoque.EstoqueDtos.MovimentacaoResponse;
import com.pointdofrango.estoque.EstoqueDtos.ReposicaoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/insumos")
@Tag(name = "Estoque", description = "Insumos, entradas de compra, ajustes de inventário e extrato")
public class InsumoController {

    private final EstoqueService service;

    public InsumoController(EstoqueService service) {
        this.service = service;
    }

    @GetMapping
    public List<InsumoResponse> listar(Authentication auth) {
        boolean admin = admin(auth);
        return service.listar().stream().map(InsumoResponse::de).map(i -> admin ? i : i.semCustos()).toList();
    }

    @GetMapping("/reposicao")
    @Operation(summary = "Custo do que foi vendido de cada insumo desde a última compra (valor a separar para repor)")
    public List<ReposicaoResponse> reposicao() {
        return service.reposicao();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Exclui insumo sem vendas nem consumo; com histórico, use a desativação")
    public void excluir(@PathVariable Long id) {
        service.excluir(id);
    }

    @GetMapping("/{id}")
    public InsumoResponse buscar(@PathVariable Long id, Authentication auth) {
        InsumoResponse i = InsumoResponse.de(service.buscar(id));
        return admin(auth) ? i : i.semCustos();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InsumoResponse criar(@Valid @RequestBody InsumoRequest req, Authentication auth) {
        return InsumoResponse.de(service.criar(req, auth.getName()));
    }

    @PutMapping("/{id}")
    public InsumoResponse atualizar(@PathVariable Long id, @Valid @RequestBody InsumoRequest req) {
        return InsumoResponse.de(service.atualizar(id, req));
    }

    @PatchMapping("/{id}/ativo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void alterarAtivo(@PathVariable Long id, @RequestParam boolean valor) {
        service.alterarAtivo(id, valor);
    }

    @PostMapping("/{id}/entradas")
    @Operation(summary = "Registra uma compra (recalcula o custo médio ponderado)")
    public InsumoResponse entrada(@PathVariable Long id, @Valid @RequestBody EntradaRequest req, Authentication auth) {
        return InsumoResponse.de(service.registrarEntrada(id, req, auth.getName()));
    }

    @PostMapping("/{id}/ajustes")
    @Operation(summary = "Ajusta o saldo para a quantidade contada fisicamente (perdas, quebras)")
    public InsumoResponse ajuste(@PathVariable Long id, @Valid @RequestBody AjusteRequest req, Authentication auth) {
        return InsumoResponse.de(service.registrarAjuste(id, req, auth.getName()));
    }

    @GetMapping("/{id}/movimentacoes")
    @Operation(summary = "Extrato das últimas 100 movimentações do insumo")
    public List<MovimentacaoResponse> extrato(@PathVariable Long id) {
        return service.extrato(id).stream().map(MovimentacaoResponse::de).toList();
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
