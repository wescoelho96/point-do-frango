package com.pointdofrango.caixa;

import com.pointdofrango.caixa.CaixaService.PendenciaMotoboy;
import com.pointdofrango.caixa.CaixaService.Recebimento;
import com.pointdofrango.caixa.CaixaService.Totais;
import com.pointdofrango.caixa.CaixaService.Venda;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CaixaDtos {

    private CaixaDtos() {
    }

    public record AberturaRequest(@NotNull @DecimalMin("0") @DecimalMax("99999") BigDecimal valorAbertura) {
    }

    public record SaidaRequest(@NotNull CategoriaSaida categoria, @NotBlank @Size(max = 150) String descricao,
                               @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99999") BigDecimal valor,
                               @NotNull FormaPagamento forma, Long colaboradorId, Long fornecedorId) {
    }

    public record SuprimentoRequest(@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99999") BigDecimal valor,
                                    @Size(max = 150) String descricao) {
    }

    public record DiariaRequest(@NotNull Long colaboradorId, @DecimalMin(value = "0", inclusive = false)
                                @DecimalMax("9999") BigDecimal valor, @NotNull FormaPagamento forma) {
    }

    public record AcertoRequest(boolean incluirDiaria, @NotNull FormaPagamento forma) {
    }

    public record FechamentoRequest(@NotNull @DecimalMin("0") @DecimalMax("999999") BigDecimal dinheiroContado,
                                    @Size(max = 255) String observacao) {
    }

    public record MovimentoResponse(Long id, MovimentoCaixa.Tipo tipo, CategoriaSaida categoria, String descricao,
                                    BigDecimal valor, FormaPagamento forma, Long colaboradorId, Long fornecedorId,
                                    Instant criadoEm, String criadoPor) {
        static MovimentoResponse de(MovimentoCaixa m) {
            return new MovimentoResponse(m.getId(), m.getTipo(), m.getCategoria(), m.getDescricao(), m.getValor(),
                    m.getForma(), m.getColaborador() != null ? m.getColaborador().getId() : null, m.getFornecedorId(),
                    m.getCriadoEm(), m.getCriadoPor());
        }
    }

    /**
     * Fechamento cego: o atendente informa o dinheiro contado sem ver o valor esperado;
     * a diferença só aparece para o dono.
     */
    public record CaixaResponse(Long id, Caixa.Status status, Instant abertoEm, String abertoPor, Instant vendasDesde,
                                BigDecimal valorAbertura, Instant fechadoEm, String fechadoPor, List<Venda> vendas,
                                List<Recebimento> recebimentos, BigDecimal totalRecebido, BigDecimal taxasEntrega,
                                BigDecimal vendasApps, BigDecimal suprimentos, BigDecimal saidasDinheiro,
                                BigDecimal saidasOutras, BigDecimal dinheiroEsperado, BigDecimal dinheiroContado,
                                BigDecimal diferenca, String observacao, List<MovimentoResponse> movimentos) {

        static CaixaResponse de(Caixa c, Totais t, boolean admin) {
            List<MovimentoResponse> movimentos = c.getMovimentos().stream().map(MovimentoResponse::de).toList();
            if (!admin) {
                return new CaixaResponse(c.getId(), c.getStatus(), c.getAbertoEm(), c.getAbertoPor(), c.getVendasDesde(),
                        c.getValorAbertura(), c.getFechadoEm(), c.getFechadoPor(), null, null, null, null, null,
                        t.suprimentos(), t.saidasDinheiro(), t.saidasOutras(), null, c.getDinheiroContado(), null,
                        c.getObservacao(), movimentos);
            }
            BigDecimal esperado = c.aberto() ? t.dinheiroEsperado() : c.getDinheiroEsperado();
            return new CaixaResponse(c.getId(), c.getStatus(), c.getAbertoEm(), c.getAbertoPor(), c.getVendasDesde(),
                    c.getValorAbertura(), c.getFechadoEm(), c.getFechadoPor(), t.vendas(), t.recebimentos(), t.totalRecebido(), t.taxasEntrega(),
                    t.vendasApps(), t.suprimentos(), t.saidasDinheiro(), t.saidasOutras(), esperado,
                    c.getDinheiroContado(), c.diferenca(), c.getObservacao(), movimentos);
        }
    }

    public record EntregaPendente(Long pedidoId, Instant criadoEm, String cliente, String bairro, BigDecimal taxa) {
    }

    public record PendenciaResponse(Long motoboyId, String motoboy, BigDecimal valorDiaria, boolean diariaPaga,
                                    int quantidadeEntregas, BigDecimal totalTaxas, BigDecimal totalAPagar,
                                    List<EntregaPendente> entregas) {
        static PendenciaResponse de(PendenciaMotoboy p) {
            return new PendenciaResponse(p.motoboy().getId(), p.motoboy().getNome(), p.motoboy().getValorDiaria(),
                    p.diariaPaga(), p.entregas().size(), p.taxas(), p.totalComDiaria(),
                    p.entregas().stream().map(e -> new EntregaPendente(e.getId(), e.getCriadoEm(), e.getClienteNome(),
                            e.getBairroEntrega(), e.getTaxaEntrega())).toList());
        }
    }
}
