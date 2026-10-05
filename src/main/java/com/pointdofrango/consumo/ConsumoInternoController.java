package com.pointdofrango.consumo;

import com.pointdofrango.estoque.MovimentacaoEstoque;
import com.pointdofrango.shared.Dinheiro;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/consumo-interno")
@Tag(name = "Consumo interno", description = "Janta e bebida da equipe: baixa o estoque sem venda")
public class ConsumoInternoController {

    public record ItemProduto(@NotNull Long produtoId, @Min(1) @Max(99) int quantidade) {
    }

    public record ItemInsumo(@NotNull Long insumoId,
                             @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("9999") BigDecimal quantidade) {
    }

    public record ConsumoRequest(@NotBlank @Size(max = 150) String motivo,
                                 @Size(max = 30) List<@Valid ItemProduto> produtos,
                                 @Size(max = 30) List<@Valid ItemInsumo> insumos) {
    }

    /** custo só para ADMIN. */
    public record BaixaResponse(Instant quando, String insumo, BigDecimal quantidade, String sigla, BigDecimal custo,
                                String motivo, String usuario) {

        static BaixaResponse de(MovimentacaoEstoque m, boolean admin) {
            return new BaixaResponse(m.getCriadoEm(), m.getInsumo().getNome(), m.getQuantidade().abs(),
                    m.getInsumo().getUnidade().getSigla(), admin ? Dinheiro.centavos(ConsumoInternoService.custo(m)) : null,
                    m.getObservacao(), m.getUsuario());
        }
    }

    private final ConsumoInternoService service;

    public ConsumoInternoController(ConsumoInternoService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registra o consumo da equipe (por produto, pela ficha técnica, ou por insumo)")
    public List<BaixaResponse> registrar(@Valid @RequestBody ConsumoRequest req, Authentication auth) {
        var baixas = service.registrar(req.motivo(),
                ConsumoInternoService.somarProdutos(req.produtos() == null ? List.of() : req.produtos()),
                ConsumoInternoService.somarInsumos(req.insumos() == null ? List.of() : req.insumos()), auth.getName());
        return baixas.stream().map(m -> BaixaResponse.de(m, admin(auth))).toList();
    }

    @GetMapping
    @Operation(summary = "Consumo da equipe nos últimos dias (padrão: 30)")
    public List<BaixaResponse> ultimos(@RequestParam(defaultValue = "30") int dias, Authentication auth) {
        return service.ultimos(Math.min(Math.max(dias, 1), 366)).stream().map(m -> BaixaResponse.de(m, admin(auth))).toList();
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
