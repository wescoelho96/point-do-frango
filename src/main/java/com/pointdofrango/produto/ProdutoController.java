package com.pointdofrango.produto;

import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/produtos")
@Tag(name = "Cardápio", description = "Produtos, ficha técnica, custo e margem")
public class ProdutoController {

    private final ProdutoService service;

    public ProdutoController(ProdutoService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista o cardápio com custo, margem e quantas unidades o estoque permite fazer")
    public List<ProdutoResponse> listar(@RequestParam(defaultValue = "false") boolean apenasAtivos,
                                        Authentication auth) {
        boolean admin = admin(auth);
        return service.listar(apenasAtivos).stream().map(ProdutoResponse::de)
                .map(p -> admin ? p : p.semCustos()).toList();
    }

    @GetMapping("/rendimento")
    @Operation(summary = "Quanto rende um insumo: porções por embalagem em cada produto que o usa")
    public ProdutoDtos.RendimentoInsumo rendimento(@RequestParam Long insumoId) {
        return service.rendimento(insumoId);
    }

    @GetMapping("/{id}")
    public ProdutoResponse buscar(@PathVariable Long id, Authentication auth) {
        ProdutoResponse p = ProdutoResponse.de(service.buscar(id));
        return admin(auth) ? p : p.semCustos();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProdutoResponse criar(@Valid @RequestBody ProdutoRequest req) {
        return ProdutoResponse.de(service.criar(req));
    }

    @PutMapping("/{id}")
    public ProdutoResponse atualizar(@PathVariable Long id, @Valid @RequestBody ProdutoRequest req) {
        return ProdutoResponse.de(service.atualizar(id, req));
    }

    public record PrecoRequest(@NotNull @DecimalMin("0.01") BigDecimal precoVenda) {
    }

    @PatchMapping("/{id}/preco")
    @Operation(summary = "Altera só o preço de venda")
    public ProdutoResponse alterarPreco(@PathVariable Long id, @Valid @RequestBody PrecoRequest req) {
        return ProdutoResponse.de(service.alterarPreco(id, req.precoVenda()));
    }

    @PatchMapping("/{id}/ativo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void alterarAtivo(@PathVariable Long id, @RequestParam boolean valor) {
        service.alterarAtivo(id, valor);
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
