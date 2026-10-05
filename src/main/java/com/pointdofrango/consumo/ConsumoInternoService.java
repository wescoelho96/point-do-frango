package com.pointdofrango.consumo;

import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.MovimentacaoEstoque;
import com.pointdofrango.produto.ConsumoFicha;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consumo da equipe, por produto (baixa pela ficha técnica) ou direto por insumo.
 * Não gera pedido nem faturamento; o custo aparece nas saídas do mês.
 */
@Service
public class ConsumoInternoService {

    private static final Logger log = LoggerFactory.getLogger(ConsumoInternoService.class);

    private final EstoqueService estoque;
    private final ProdutoService produtos;
    private final Clock clock;

    public ConsumoInternoService(EstoqueService estoque, ProdutoService produtos, Clock clock) {
        this.estoque = estoque;
        this.produtos = produtos;
        this.clock = clock;
    }

    @Transactional
    public List<MovimentacaoEstoque> registrar(String motivo, Map<Long, Integer> porProduto,
                                               Map<Long, BigDecimal> porInsumo, String usuario) {
        if (porProduto.isEmpty() && porInsumo.isEmpty()) {
            throw new RegraDeNegocioException("Informe pelo menos um produto ou insumo consumido.");
        }
        Map<Long, BigDecimal> consumo = new TreeMap<>(porInsumo);
        if (!porProduto.isEmpty()) {
            produtos.buscarVarios(porProduto.keySet());
            for (ConsumoFicha linha : produtos.consumoDosProdutos(porProduto.keySet())) {
                BigDecimal usado = linha.quantidade().multiply(BigDecimal.valueOf(porProduto.get(linha.produtoId())));
                consumo.merge(linha.insumoId(), usado, BigDecimal::add);
            }
        }
        consumo.replaceAll((id, qtd) -> qtd.setScale(6, RoundingMode.HALF_UP));

        Map<Long, Insumo> travados = estoque.travarEConferir(consumo);
        List<MovimentacaoEstoque> baixas = estoque.baixarConsumoInterno(travados, consumo, motivo.strip(), usuario);
        log.info("Consumo interno por {}: {} insumo(s) ({})", usuario, baixas.size(), motivo);
        return baixas;
    }

    @Transactional(readOnly = true)
    public List<MovimentacaoEstoque> ultimos(int dias) {
        Instant agora = Instant.now(clock);
        return estoque.consumoInternoEntre(agora.minus(dias, ChronoUnit.DAYS), agora.plusSeconds(1));
    }

    /** Pelo custo médio gravado na movimentação, não o atual. */
    public static BigDecimal custo(MovimentacaoEstoque m) {
        return m.getQuantidade().abs().multiply(m.getCustoUnitario());
    }

    static Map<Long, Integer> somarProdutos(List<ConsumoInternoController.ItemProduto> itens) {
        Map<Long, Integer> mapa = new LinkedHashMap<>();
        itens.forEach(i -> mapa.merge(i.produtoId(), i.quantidade(), Integer::sum));
        return mapa;
    }

    static Map<Long, BigDecimal> somarInsumos(List<ConsumoInternoController.ItemInsumo> itens) {
        Map<Long, BigDecimal> mapa = new LinkedHashMap<>();
        itens.forEach(i -> mapa.merge(i.insumoId(), i.quantidade(), BigDecimal::add));
        return mapa;
    }
}
