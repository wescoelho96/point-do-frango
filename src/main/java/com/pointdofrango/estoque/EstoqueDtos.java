package com.pointdofrango.estoque;

import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.shared.Dinheiro;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class EstoqueDtos {

    private EstoqueDtos() {
    }

    public record InsumoRequest(
            @NotBlank @Size(max = 100) String nome,
            @NotNull UnidadeMedida unidade,
            @NotNull @DecimalMin("0") BigDecimal estoqueMinimo,
            @NotNull @DecimalMin("0") BigDecimal custoUnitario,
            /* só é usado no cadastro */
            @DecimalMin("0") BigDecimal estoqueInicial,
            /* ex.: "isca", 12 (por kg). Os dois em branco = sem unidade de uso */
            @Size(max = 30) String unidadeUsoNome,
            @DecimalMin(value = "0", inclusive = false) BigDecimal unidadeUsoPorUnidade,
            List<@Valid EmbalagemRequest> embalagens,
            @Size(max = 40) String grupo,
            @Size(max = 50) String marca) {

        /** Cadastro simples: sem unidade de uso e sem embalagens. */
        public InsumoRequest(String nome, UnidadeMedida unidade, BigDecimal estoqueMinimo, BigDecimal custoUnitario,
                             BigDecimal estoqueInicial) {
            this(nome, unidade, estoqueMinimo, custoUnitario, estoqueInicial, null, null, null, null, null);
        }

        public InsumoRequest(String nome, UnidadeMedida unidade, BigDecimal estoqueMinimo, BigDecimal custoUnitario,
                             BigDecimal estoqueInicial, String unidadeUsoNome, BigDecimal unidadeUsoPorUnidade,
                             List<EmbalagemRequest> embalagens) {
            this(nome, unidade, estoqueMinimo, custoUnitario, estoqueInicial, unidadeUsoNome, unidadeUsoPorUnidade,
                    embalagens, null, null);
        }

        public List<EmbalagemRequest> embalagensOuVazio() {
            return embalagens == null ? List.of() : embalagens;
        }
    }

    /** id preenchido = embalagem existente; id nulo = nova. */
    public record EmbalagemRequest(
            Long id,
            @NotBlank @Size(max = 50) String nome,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal conteudo) {
    }

    /** Entrada por embalagem (embalagemId + quantidadeEmbalagens) ou direto na unidade do insumo (quantidade). */
    public record EntradaRequest(
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantidade,
            Long embalagemId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantidadeEmbalagens,
            @NotNull @DecimalMin("0") BigDecimal valorTotal,
            @Size(max = 255) String observacao,
            @Valid PagamentoCompra pagamento) {

        public EntradaRequest(BigDecimal quantidade, Long embalagemId, BigDecimal quantidadeEmbalagens,
                              BigDecimal valorTotal, String observacao) {
            this(quantidade, embalagemId, quantidadeEmbalagens, valorTotal, observacao, null);
        }
    }

    /**
     * Se informado, a compra também entra nas saídas do mês. doCaixa: dinheiro da gaveta, vira saída
     * do caixa aberto; senão foi pago por fora (PIX, cartão).
     */
    public record PagamentoCompra(@NotNull FormaPagamento forma, boolean doCaixa, Long fornecedorId) {
    }

    /** Compra com vários itens e um só pagamento (cupom de mercado, compra sem nota). */
    public record CompraRequest(
            @NotEmpty @Size(max = 60) List<@Valid ItemCompra> itens,
            @Size(max = 255) String observacao,
            @Valid PagamentoCompra pagamento) {
    }

    public record ItemCompra(
            @NotNull Long insumoId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantidade,
            Long embalagemId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantidadeEmbalagens,
            @NotNull @DecimalMin("0") BigDecimal valorTotal) {
    }

    public record CompraResponse(int itens, BigDecimal valorTotal) {
    }

    public record AjusteRequest(
            @NotNull @DecimalMin("0") BigDecimal quantidadeContada,
            @NotBlank @Size(max = 255) String motivo) {
    }

    public record EmbalagemResponse(Long id, String nome, BigDecimal conteudo) {

        static EmbalagemResponse de(EmbalagemCompra e) {
            return new EmbalagemResponse(e.getId(), e.getNome(), e.getConteudo());
        }
    }

    /** Custo do que foi vendido desde a última compra: o dinheiro a guardar para repor o mesmo item. */
    public record ReposicaoResponse(Long insumoId, BigDecimal vendido, BigDecimal valor) {
    }

    public record InsumoResponse(Long id, String nome, UnidadeMedida unidade, String sigla, String subunidade,
                                 BigDecimal estoqueAtual, BigDecimal estoqueMinimo, BigDecimal custoUnitario,
                                 BigDecimal valorEmEstoque, boolean abaixoDoMinimo, boolean ativo,
                                 String unidadeUsoNome, BigDecimal unidadeUsoPorUnidade,
                                 List<EmbalagemResponse> embalagens, String grupo, String marca) {

        public static InsumoResponse de(Insumo i) {
            return new InsumoResponse(i.getId(), i.getNome(), i.getUnidade(), i.getUnidade().getSigla(),
                    i.getUnidade().getSubunidade(), i.getEstoqueAtual(), i.getEstoqueMinimo(), i.getCustoUnitario(),
                    Dinheiro.centavos(i.getEstoqueAtual().multiply(i.getCustoUnitario())),
                    i.abaixoDoMinimo(), i.isAtivo(), i.getUnidadeUsoNome(), i.getUnidadeUsoPorUnidade(),
                    i.getEmbalagens().stream().map(EmbalagemResponse::de).toList(), i.getGrupo(), i.getMarca());
        }

        /** O atendente confere o saldo, mas não vê quanto a loja paga pelos insumos. */
        public InsumoResponse semCustos() {
            return new InsumoResponse(id, nome, unidade, sigla, subunidade, estoqueAtual, estoqueMinimo, null, null,
                    abaixoDoMinimo, ativo, unidadeUsoNome, unidadeUsoPorUnidade, embalagens, grupo, marca);
        }
    }

    public record MovimentacaoResponse(Long id, TipoMovimentacao tipo, BigDecimal quantidade, BigDecimal saldoApos,
                                       BigDecimal custoUnitario, Long pedidoId, String observacao, String usuario,
                                       Instant criadoEm) {

        public static MovimentacaoResponse de(MovimentacaoEstoque m) {
            return new MovimentacaoResponse(m.getId(), m.getTipo(), m.getQuantidade(), m.getSaldoApos(),
                    m.getCustoUnitario(), m.getPedidoId(), m.getObservacao(), m.getUsuario(), m.getCriadoEm());
        }
    }
}
