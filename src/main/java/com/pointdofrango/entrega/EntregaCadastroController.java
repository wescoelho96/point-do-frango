package com.pointdofrango.entrega;

import com.pointdofrango.entrega.Colaborador.Funcao;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Entregas", description = "Bairros com taxa de entrega, motoboys e funcionários")
public class EntregaCadastroController {

    public record BairroRequest(@NotBlank @Size(max = 80) String nome,
                                @NotNull @DecimalMin("0") @DecimalMax("999") BigDecimal taxaEntrega,
                                Boolean ativo) {
    }

    public record BairroResponse(Long id, String nome, BigDecimal taxaEntrega, boolean ativo) {
        static BairroResponse de(Bairro b) {
            return new BairroResponse(b.getId(), b.getNome(), b.getTaxaEntrega(), b.isAtivo());
        }
    }

    public record ColaboradorRequest(@NotBlank @Size(max = 100) String nome, @NotNull Funcao funcao,
                                     @NotNull @DecimalMin("0") @DecimalMax("9999") BigDecimal valorDiaria,
                                     Boolean ativo) {
    }

    public record ColaboradorResponse(Long id, String nome, Funcao funcao, BigDecimal valorDiaria, boolean ativo) {
        static ColaboradorResponse de(Colaborador c) {
            return new ColaboradorResponse(c.getId(), c.getNome(), c.getFuncao(), c.getValorDiaria(), c.isAtivo());
        }
    }

    private final EntregaCadastroService service;

    public EntregaCadastroController(EntregaCadastroService service) {
        this.service = service;
    }

    @GetMapping("/bairros")
    public List<BairroResponse> bairros(@RequestParam(defaultValue = "false") boolean apenasAtivos) {
        return service.listarBairros(apenasAtivos).stream().map(BairroResponse::de).toList();
    }

    @PostMapping("/bairros")
    @ResponseStatus(HttpStatus.CREATED)
    public BairroResponse criarBairro(@Valid @RequestBody BairroRequest req) {
        return BairroResponse.de(service.criarBairro(req.nome(), req.taxaEntrega()));
    }

    @PutMapping("/bairros/{id}")
    public BairroResponse alterarBairro(@PathVariable Long id, @Valid @RequestBody BairroRequest req) {
        return BairroResponse.de(service.alterarBairro(id, req.nome(), req.taxaEntrega(), req.ativo() == null || req.ativo()));
    }

    @GetMapping("/colaboradores")
    public List<ColaboradorResponse> colaboradores() {
        return service.listarColaboradores().stream().map(ColaboradorResponse::de).toList();
    }

    @PostMapping("/colaboradores")
    @ResponseStatus(HttpStatus.CREATED)
    public ColaboradorResponse criarColaborador(@Valid @RequestBody ColaboradorRequest req) {
        return ColaboradorResponse.de(service.criarColaborador(req.nome(), req.funcao(), req.valorDiaria()));
    }

    @PutMapping("/colaboradores/{id}")
    public ColaboradorResponse alterarColaborador(@PathVariable Long id, @Valid @RequestBody ColaboradorRequest req) {
        return ColaboradorResponse.de(service.alterarColaborador(id, req.nome(), req.funcao(), req.valorDiaria(),
                req.ativo() == null || req.ativo()));
    }
}
