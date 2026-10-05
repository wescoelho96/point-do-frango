package com.pointdofrango.produto;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/promocoes")
@Tag(name = "Promoções", description = "Compre X, ganhe Y nos dias escolhidos (brinde sai do estoque com preço zero)")
public class PromocaoController {

    public record PromocaoRequest(@NotBlank @Size(max = 100) String nome, @NotNull Long produtoCompraId,
                                  @Min(1) @Max(20) int quantidadeCompra, @NotNull Long produtoBrindeId,
                                  @Min(1) @Max(20) int quantidadeBrinde, @NotEmpty Set<DayOfWeek> dias, Boolean ativa) {
    }

    public record PromocaoResponse(Long id, String nome, Long produtoCompraId, String produtoCompra, int quantidadeCompra,
                                   Long produtoBrindeId, String produtoBrinde, int quantidadeBrinde, Set<DayOfWeek> dias,
                                   boolean ativa) {
        static PromocaoResponse de(Promocao p) {
            return new PromocaoResponse(p.getId(), p.getNome(), p.getProdutoCompra().getId(), p.getProdutoCompra().getNome(),
                    p.getQuantidadeCompra(), p.getProdutoBrinde().getId(), p.getProdutoBrinde().getNome(),
                    p.getQuantidadeBrinde(), p.getDias(), p.isAtiva());
        }
    }

    private final PromocaoService service;

    public PromocaoController(PromocaoService service) {
        this.service = service;
    }

    @GetMapping
    public List<PromocaoResponse> listar() {
        return service.listar().stream().map(PromocaoResponse::de).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PromocaoResponse criar(@Valid @RequestBody PromocaoRequest r) {
        return PromocaoResponse.de(service.salvar(null, r.nome(), r.produtoCompraId(), r.quantidadeCompra(),
                r.produtoBrindeId(), r.quantidadeBrinde(), r.dias(), true));
    }

    @PutMapping("/{id}")
    public PromocaoResponse alterar(@PathVariable Long id, @Valid @RequestBody PromocaoRequest r) {
        return PromocaoResponse.de(service.salvar(id, r.nome(), r.produtoCompraId(), r.quantidadeCompra(),
                r.produtoBrindeId(), r.quantidadeBrinde(), r.dias(), r.ativa() == null || r.ativa()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable Long id) {
        service.remover(id);
    }
}
