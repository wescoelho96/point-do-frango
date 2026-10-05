package com.pointdofrango.loja;

import com.pointdofrango.loja.LojaService.DadosLoja;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/loja")
@Tag(name = "Loja", description = "Dados do cupom e taxa de serviço")
public class LojaController {

    public record LojaRequest(
            @NotBlank @Size(max = 100) String nome,
            @Size(max = 30) String documento,
            @Size(max = 200) String endereco,
            @Size(max = 30) String telefone,
            @Size(max = 200) String mensagemRodape,
            @NotNull @DecimalMin("0") @DecimalMax("20") BigDecimal percentualServico,
            boolean servicoMarcado) {
    }

    private final LojaService service;

    public LojaController(LojaService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Dados da loja (qualquer usuário logado: o cupom precisa deles)")
    public DadosLoja obter() {
        return service.dados(service.obter());
    }

    @PutMapping
    @Operation(summary = "Altera os dados da loja e a taxa de serviço (somente ADMIN)")
    public DadosLoja atualizar(@Valid @RequestBody LojaRequest r) {
        return service.dados(service.atualizar(new DadosLoja(r.nome(), r.documento(), r.endereco(), r.telefone(),
                r.mensagemRodape(), r.percentualServico(), r.servicoMarcado(), null, null)));
    }

    public record EntregaGratisRequest(@NotNull Set<DayOfWeek> dias) {
    }

    @PutMapping("/entrega-gratis")
    @Operation(summary = "Dias da semana em que a entrega é grátis (somente ADMIN)")
    public DadosLoja entregaGratis(@Valid @RequestBody EntregaGratisRequest r) {
        return service.dados(service.definirDiasEntregaGratis(r.dias()));
    }

    @PutMapping("/menus")
    @Operation(summary = "Renomeia os itens do menu lateral (somente ADMIN). Nome vazio volta ao padrão")
    public DadosLoja renomearMenus(@RequestBody Map<String, String> nomes) {
        return service.dados(service.renomearMenus(nomes));
    }
}
