package com.pointdofrango.produto;

import com.pointdofrango.estoque.Insumo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Transforma as linhas da ficha em consumo por insumo. Uma linha com alternativas (1 caixa 30x30
 * ou 2 caixas Assaí) usa a primeira opção com saldo e passa para a seguinte quando ela acaba;
 * num pedido de 3 combos com só 1 caixa 30x30, sai 1 caixa 30x30 e 4 Assaí.
 */
public final class MontagemDoConsumo {

    /**
     * porInsumo: total a baixar, já com 6 casas.
     * porProduto: o que cada produto consumiu, para o custo do item refletir a opção usada.
     */
    public record Resultado(Map<Long, BigDecimal> porInsumo, Map<Long, Map<Long, BigDecimal>> porProduto) {

        /** Custo de uma unidade do produto pelo custo médio dos insumos travados. */
        public BigDecimal custoUnitario(Long produtoId, int unidades, Map<Long, Insumo> insumos) {
            BigDecimal total = porProduto.getOrDefault(produtoId, Map.of()).entrySet().stream()
                    .map(e -> e.getValue().multiply(insumos.get(e.getKey()).getCustoUnitario()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return total.divide(BigDecimal.valueOf(unidades), Insumo.ESCALA_FICHA, RoundingMode.HALF_UP);
        }
    }

    private MontagemDoConsumo() {
    }

    /**
     * @param unidades     quanto sai de cada produto
     * @param semViagem    na mesa ou consumo da equipe: caixa e sacola não saem
     * @param insumos      já travados; o saldo decide qual alternativa usar
     */
    public static Resultado montar(List<ConsumoFicha> linhas, Map<Long, Integer> unidades, boolean semViagem,
                                   Map<Long, Insumo> insumos) {
        Map<Long, BigDecimal> porInsumo = new TreeMap<>();
        Map<Long, Map<Long, BigDecimal>> porProduto = new LinkedHashMap<>();
        List<ConsumoFicha> valem = linhas.stream().filter(l -> !(semViagem && l.soParaViagem())).toList();

        // Primeiro as linhas sem alternativa: a alternativa só usa o que sobrar delas
        for (ConsumoFicha linha : valem) {
            if (linha.alternativaDe() == null && opcoes(valem, linha).size() == 1) {
                somar(porInsumo, porProduto, linha, unidades.get(linha.produtoId()));
            }
        }
        for (ConsumoFicha linha : valem) {
            List<ConsumoFicha> opcoes = opcoes(valem, linha);
            if (linha.alternativaDe() != null || opcoes.size() == 1) {
                continue;
            }
            int faltam = unidades.get(linha.produtoId());
            for (ConsumoFicha opcao : opcoes) {
                int usar = (int) Math.min(faltam, cabe(insumos.get(opcao.insumoId()), opcao.quantidade(),
                        porInsumo.getOrDefault(opcao.insumoId(), BigDecimal.ZERO)));
                if (usar > 0) {
                    somar(porInsumo, porProduto, opcao, usar);
                    faltam -= usar;
                }
            }
            if (faltam > 0) {
                somar(porInsumo, porProduto, linha, faltam); // nenhuma opção cobre: a falta aparece na principal
            }
        }
        // Ficha tem 10 casas e o estoque 6. Arredondar só o total evita acumular erro
        // (12 iscas de 0,0833333333 kg dão exatamente 1 kg).
        porInsumo.replaceAll((insumoId, qtd) -> qtd.setScale(6, RoundingMode.HALF_UP));
        return new Resultado(porInsumo, porProduto);
    }

    private static List<ConsumoFicha> opcoes(List<ConsumoFicha> linhas, ConsumoFicha principal) {
        return linhas.stream()
                .filter(l -> l.produtoId().equals(principal.produtoId()) && (l == principal
                        || (principal.alternativaDe() == null && principal.insumoId().equals(l.alternativaDe()))))
                .toList();
    }

    private static long cabe(Insumo insumo, BigDecimal porUnidade, BigDecimal jaReservado) {
        if (!insumo.isTravaVenda()) {
            return Long.MAX_VALUE;
        }
        BigDecimal livre = insumo.getEstoqueAtual().subtract(jaReservado).add(Insumo.TOLERANCIA);
        return livre.signum() <= 0 ? 0 : livre.divide(porUnidade, 0, RoundingMode.FLOOR).longValue();
    }

    private static void somar(Map<Long, BigDecimal> porInsumo, Map<Long, Map<Long, BigDecimal>> porProduto,
                              ConsumoFicha linha, int unidades) {
        BigDecimal usado = linha.quantidade().multiply(BigDecimal.valueOf(unidades));
        porInsumo.merge(linha.insumoId(), usado, BigDecimal::add);
        porProduto.computeIfAbsent(linha.produtoId(), k -> new TreeMap<>()).merge(linha.insumoId(), usado, BigDecimal::add);
    }
}
