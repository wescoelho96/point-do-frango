package com.pointdofrango.produto;

import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.ModoQuantidade;
import com.pointdofrango.shared.Dinheiro;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class ProdutoDtos {

    private ProdutoDtos() {
    }

    /** modo nulo = UNIDADE_BASE. RENDIMENTO_EMBALAGEM exige embalagemId. */
    public record ItemFichaRequest(
            @NotNull Long insumoId,
            ModoQuantidade modo,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantidade,
            Long embalagemId) {

        public ItemFichaRequest(Long insumoId, BigDecimal quantidade) {
            this(insumoId, ModoQuantidade.UNIDADE_BASE, quantidade, null);
        }
    }

    public record ProdutoRequest(
            @NotBlank @Size(max = 100) String nome,
            @Size(max = 255) String descricao,
            @NotNull CategoriaProduto categoria,
            @NotNull @DecimalMin(value = "0.01") BigDecimal precoVenda,
            @NotEmpty(message = "Informe a ficha técnica") List<@Valid ItemFichaRequest> fichaTecnica,
            /* nulo = padrão da categoria (bebida não vai para a cozinha) */
            Boolean vaiParaCozinha) {

        public ProdutoRequest(String nome, String descricao, CategoriaProduto categoria, BigDecimal precoVenda,
                              List<ItemFichaRequest> fichaTecnica) {
            this(nome, descricao, categoria, precoVenda, fichaTecnica, null);
        }

        public boolean preparoNaCozinha() {
            return vaiParaCozinha != null ? vaiParaCozinha : categoria != CategoriaProduto.BEBIDA;
        }
    }

    /** Ex.: 1 "Saco 2 kg" rende 6,67 unidades do produto. */
    public record Rendimento(String embalagem, BigDecimal conteudo, BigDecimal rende) {
    }

    public record ItemFichaResponse(Long insumoId, String insumo, String sigla, ModoQuantidade modo,
                                    BigDecimal quantidadeInformada, Long embalagemId, String descricao,
                                    BigDecimal quantidade, BigDecimal custo, List<Rendimento> rendimentos) {

        static ItemFichaResponse de(ItemFichaTecnica item) {
            Insumo i = item.getInsumo();
            return new ItemFichaResponse(i.getId(), i.getNome(), i.getUnidade().getSigla(), item.getModo(),
                    item.getQuantidadeInformada(), item.getEmbalagem() != null ? item.getEmbalagem().getId() : null,
                    item.descricao(), item.getQuantidade().setScale(6, RoundingMode.HALF_UP),
                    Dinheiro.centavos(item.custo()), calcularRendimentos(i, item.getQuantidade()));
        }
    }

    static List<Rendimento> calcularRendimentos(Insumo insumo, BigDecimal porUnidadeDeProduto) {
        return insumo.getEmbalagens().stream()
                .map(e -> new Rendimento(e.getNome(), e.getConteudo(),
                        e.getConteudo().divide(porUnidadeDeProduto, 2, RoundingMode.HALF_UP)))
                .toList();
    }

    public record RendimentoInsumo(Long insumoId, String insumo, String sigla, BigDecimal estoqueAtual,
                                   String unidadeUsoNome, BigDecimal estoqueEmUnidadesDeUso,
                                   List<UsoEmProduto> usos) {

        public record UsoEmProduto(Long produtoId, String produto, boolean ativo, String descricao,
                                   BigDecimal quantidade, int unidadesComEstoqueAtual, List<Rendimento> rendimentos) {
        }

        static RendimentoInsumo de(Insumo insumo, List<ItemFichaTecnica> fichas) {
            BigDecimal emUso = insumo.getUnidadeUsoPorUnidade() == null ? null
                    : insumo.getEstoqueAtual().multiply(insumo.getUnidadeUsoPorUnidade()).setScale(1, RoundingMode.HALF_UP);
            return new RendimentoInsumo(insumo.getId(), insumo.getNome(), insumo.getUnidade().getSigla(),
                    insumo.getEstoqueAtual(), insumo.getUnidadeUsoNome(), emUso,
                    fichas.stream().map(f -> new UsoEmProduto(f.getProduto().getId(), f.getProduto().getNome(),
                            f.getProduto().isAtivo(), f.descricao(), f.getQuantidade().setScale(6, RoundingMode.HALF_UP),
                            insumo.getEstoqueAtual().add(Insumo.TOLERANCIA).divide(f.getQuantidade(), 0, RoundingMode.FLOOR).intValue(),
                            calcularRendimentos(insumo, f.getQuantidade()))).toList());
        }
    }

    /** margemPercentual é a margem de contribuição (100 - cmvPercentual). */
    public record ProdutoResponse(Long id, String nome, String descricao, CategoriaProduto categoria,
                                  String categoriaRotulo, BigDecimal precoVenda, boolean ativo,
                                  BigDecimal custoAtual, BigDecimal margemValor, BigDecimal margemPercentual,
                                  BigDecimal cmvPercentual, int unidadesDisponiveis, List<ItemFichaResponse> fichaTecnica,
                                  boolean vaiParaCozinha) {

        public static ProdutoResponse de(Produto p) {
            BigDecimal custo = Dinheiro.centavos(p.custoAtual());
            BigDecimal margem = p.getPrecoVenda().subtract(custo);
            BigDecimal cmvPct = custo.multiply(new BigDecimal("100")).divide(p.getPrecoVenda(), 1, RoundingMode.HALF_UP);
            BigDecimal margemPct = new BigDecimal("100").subtract(cmvPct);
            return new ProdutoResponse(p.getId(), p.getNome(), p.getDescricao(), p.getCategoria(),
                    p.getCategoria().getRotulo(), p.getPrecoVenda(), p.isAtivo(), custo, margem, margemPct, cmvPct,
                    p.unidadesDisponiveis(), p.getFichaTecnica().stream().map(ItemFichaResponse::de).toList(),
                    p.isVaiParaCozinha());
        }

        /** Para o atendente: preço e disponibilidade, sem custo nem margem. */
        public ProdutoResponse semCustos() {
            return new ProdutoResponse(id, nome, descricao, categoria, categoriaRotulo, precoVenda, ativo, null, null,
                    null, null, unidadesDisponiveis, List.of(), vaiParaCozinha);
        }
    }
}
