package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.DespesaFixa;
import com.pointdofrango.financeiro.DistribuicaoFinanceira;
import com.pointdofrango.financeiro.FinanceiroDtos.DespesaRequest;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contas por dia: a sobra de cada venda paga a cota de contas dos dias trabalhados até hoje; o resto vira lucro.
 * O banco de teste é compartilhado, então o teste parte do que ainda falta guardar no mês.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContasPrimeiroIntegrationTest {

    @Autowired PedidoService pedidos;
    @Autowired PedidoRepository repositorio;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired ConfiguracaoFinanceiraService financeiro;
    @Autowired Clock clock;
    @Autowired ZoneId zona;

    private DespesaFixa conta;

    @AfterEach
    void tirarConta() {
        if (conta != null) {
            financeiro.removerDespesa(conta.getId());
        }
    }

    @Test
    @DisplayName("Primeiras vendas do dia pagam a cota das contas; a que completa divide, as seguintes são lucro")
    void enchePoteEDepoisLucra() {
        assertThat(financeiro.obter().isContasPrimeiro()).isTrue();
        // R$ 60 de conta nova garante que o pote tem espaço, seja qual for o estado do banco
        conta = financeiro.criarDespesa(new DespesaRequest("Conta teste contas primeiro", bd("60"), 10, true, false));
        Insumo batata = estoque.criar(new InsumoRequest("Batata CP", UnidadeMedida.QUILOGRAMA, bd("0"), bd("10"), bd("1000")), "teste");
        // R$ 50 no PIX, custo R$ 10: sobra R$ 40 por venda (PIX sem taxa no teste)
        Produto combo = produtos.criar(new ProdutoRequest("Combo CP", null, CategoriaProduto.COMBO, bd("50"),
                List.of(new ItemFichaRequest(batata.getId(), bd("1")))));

        int vendas = 0;
        BigDecimal falta;
        do {
            falta = falta();
            DistribuicaoFinanceira d = vender(combo).getDistribuicao();
            BigDecimal sobra = d.contasFixas().add(d.lucroLiquido());
            assertThat(d.contasFixas()).isEqualByComparingTo(falta.min(sobra));
            assertThat(d.lucroLiquido()).isEqualByComparingTo(sobra.subtract(falta.min(sobra)));
            assertThat(d.proLabore().add(d.reservaEmergencia())).isEqualByComparingTo(d.lucroLiquido().max(BigDecimal.ZERO));
            assertThat(d.valorBruto()).isEqualByComparingTo("50");
            vendas++;
        } while (falta.compareTo(bd("40")) > 0 && vendas < 900);

        // pote cheio: a próxima venda é toda lucro
        assertThat(falta()).isEqualByComparingTo("0");
        DistribuicaoFinanceira depois = vender(combo).getDistribuicao();
        assertThat(depois.contasFixas()).isEqualByComparingTo("0");
        assertThat(depois.proLabore()).isPositive();
    }

    @Test
    @DisplayName("Cancelar devolve a parte das contas daquela venda: a próxima completa o pote")
    void cancelarDevolveParaAsContas() {
        conta = financeiro.criarDespesa(new DespesaRequest("Conta teste cancelamento", bd("30"), 10, true, false));
        Insumo batata = estoque.criar(new InsumoRequest("Batata CPC", UnidadeMedida.QUILOGRAMA, bd("0"), bd("10"), bd("1000")), "teste");
        Produto combo = produtos.criar(new ProdutoRequest("Combo CPC", null, CategoriaProduto.COMBO, bd("50"),
                List.of(new ItemFichaRequest(batata.getId(), bd("1")))));
        List<Pedido> minhas = new ArrayList<>();
        while (falta().signum() > 0 && minhas.size() < 900) {
            minhas.add(vender(combo));
        }
        Pedido ultima = vender(combo); // pote já cheio: sem contas
        Pedido cheia = minhas.stream().filter(p -> p.getDistribuicao().contasFixas().signum() > 0)
                .reduce((a, b) -> b).orElseThrow();
        BigDecimal devolvido = cheia.getDistribuicao().contasFixas();

        pedidos.cancelar(cheia.getId(), "teste", "dono");

        assertThat(falta()).isEqualByComparingTo(devolvido);
        assertThat(ultima.getDistribuicao().contasFixas()).isEqualByComparingTo("0");
        assertThat(vender(combo).getDistribuicao().contasFixas()).isEqualByComparingTo(devolvido.min(bd("40")));
    }

    private BigDecimal falta() {
        YearMonth mes = YearMonth.now(clock.withZone(zona));
        Instant inicio = mes.atDay(1).atStartOfDay(zona).toInstant();
        Instant fim = mes.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant();
        // contas por dia: só a cota dos dias trabalhados até hoje
        BigDecimal devido = financeiro.obter().contasAte(financeiro.totalMensalDespesasAtivas(),
                LocalDate.now(clock.withZone(zona)));
        return devido.subtract(repositorio.contasGuardadasEntre(inicio, fim)).max(BigDecimal.ZERO);
    }

    private Pedido vender(Produto p) {
        return pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.DINHEIRO, null, null, null, null,
                List.of(new ItemRequest(p.getId(), 1))), "teste");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
