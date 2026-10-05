package com.pointdofrango.notafiscal;

import com.pointdofrango.notafiscal.NotaFiscalService.Ligacao;
import com.pointdofrango.notafiscal.NotaFiscalService.Pagamento;
import com.pointdofrango.notafiscal.NotaFiscalService.Previa;
import com.pointdofrango.notafiscal.NotaFiscalService.Resultado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/notas")
@Tag(name = "Notas fiscais de compra", description = "Importa o XML da NF-e: entrada no estoque e saída do fornecedor (somente ADMIN)")
public class NotaFiscalController {

    /** 1 MB cobre notas com centenas de itens. */
    private static final int MAX_XML = 1_000_000;

    public record XmlRequest(@NotBlank @Size(max = MAX_XML) String xml) {
    }

    public record ImportarRequest(@NotBlank @Size(max = MAX_XML) String xml, @NotNull @Size(max = 500) List<@Valid Ligacao> itens,
                                  @Valid Pagamento pagamento) {
    }

    public record ChaveResponse(LeitorNfe.Chave chave, boolean jaImportada) {
    }

    public record NotaResponse(Long id, String chave, String numero, String emitente, Instant emitidaEm, BigDecimal valorTotal,
                               Instant importadaEm, String importadaPor) {
        static NotaResponse de(NotaFiscalCompra n) {
            return new NotaResponse(n.getId(), n.getChave(), n.getNumero(), n.getEmitenteNome(), n.getEmitidaEm(),
                    n.getValorTotal(), n.getImportadaEm(), n.getImportadaPor());
        }
    }

    private final NotaFiscalService service;

    public NotaFiscalController(NotaFiscalService service) {
        this.service = service;
    }

    @GetMapping("/chave/{chave}")
    @Operation(summary = "Confere a chave de acesso da DANFE (CNPJ, número, mês) e se a nota já foi importada")
    public ChaveResponse chave(@PathVariable String chave) {
        LeitorNfe.Chave lida = LeitorNfe.lerChave(chave);
        return new ChaveResponse(lida, service.jaImportada(lida.chave()));
    }

    @PostMapping("/previa")
    @Operation(summary = "Lê o XML e mostra os itens, com o insumo já usado nas compras anteriores do fornecedor")
    public Previa previa(@Valid @RequestBody XmlRequest req) {
        return service.previa(req.xml());
    }

    @PostMapping("/importar")
    @Operation(summary = "Dá entrada dos itens ligados a insumos e lança o total como saída do fornecedor")
    public Resultado importar(@Valid @RequestBody ImportarRequest req, Authentication auth) {
        return service.importar(req.xml(), req.itens(), req.pagamento(), auth.getName());
    }

    @GetMapping
    @Operation(summary = "Últimas notas importadas")
    public List<NotaResponse> ultimas() {
        return service.ultimas().stream().map(NotaResponse::de).toList();
    }
}
