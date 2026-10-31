package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.ModoQuantidade;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.ConfiguracaoFinanceira;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoDtos.ParteRequest;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Combo com desconto + batata, R$ 75 dividido entre 4 amigos em formas diferentes. */
@SpringBootTest
@ActiveProfiles("test")
class PagamentoDivididoIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired PedidoService pedidos;
    @Autowired ConfiguracaoFinanceiraService financeiro;

    private Produto combo;
    private Produto batata;

    @BeforeEach
    void cadastro() {
        int n = SEQ.incrementAndGet();
        Insumo frango = estoque.criar(new InsumoRequest("Divid frango " + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("20"),
                bd("50")), "teste");
        combo = produto("Divid combo 1 " + n, "45", frango, "0.5");
        batata = produto("Divid batata G " + n, "35", frango, "0.1");
    }

    @Test
    @DisplayName("Desconto de R$ 5 nos R$ 80 e conta de R$ 75 em 4 partes: cada forma com sua taxa")
    void contaDivididaComDesconto() {
        Pedido p = lancar(FormaPagamento.DIVIDIDO, "5", List.of(
                parte(FormaPagamento.PIX, "18.75"), parte(FormaPagamento.PIX, "18.75"),
                parte(FormaPagamento.CREDITO, "18.75"), parte(FormaPagamento.DINHEIRO, "18.75")), "20");

        assertThat(p.getValorTotal()).isEqualByComparingTo("75");
        assertThat(p.getDesconto()).isEqualByComparingTo("5");
        assertThat(p.valorCardapio()).isEqualByComparingTo("80");
        Map<FormaPagamento, BigDecimal> recebido = p.recebimentos();
        assertThat(recebido.get(FormaPagamento.PIX)).isEqualByComparingTo("37.50");
        assertThat(recebido.get(FormaPagamento.CREDITO)).isEqualByComparingTo("18.75");
        assertThat(recebido.get(FormaPagamento.DINHEIRO)).isEqualByComparingTo("18.75");
        assertThat(recebido).doesNotContainKey(FormaPagamento.DIVIDIDO);
        assertThat(p.troco()).isEqualByComparingTo("1.25");

        ConfiguracaoFinanceira c = financeiro.obter();
        BigDecimal esperada = Dinheiro.centavos(bd("37.50").multiply(c.getTaxaPix())
                .add(bd("18.75").multiply(c.getTaxaCredito())).divide(bd("100")));
        assertThat(p.getDistribuicao().taxaPagamento()).isEqualByComparingTo(esperada);
    }

    @Test
    @DisplayName("As partes precisam fechar o total")
    void partesNaoFecham() {
        assertThatThrownBy(() -> lancar(FormaPagamento.DIVIDIDO, null, List.of(
                parte(FormaPagamento.PIX, "40"), parte(FormaPagamento.DEBITO, "30")), null))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("somam R$ 70.00");
    }

    @Test
    @DisplayName("Conta dividida precisa de pelo menos duas partes e não aceita cortesia como parte")
    void partesInvalidas() {
        assertThatThrownBy(() -> lancar(FormaPagamento.DIVIDIDO, null, List.of(parte(FormaPagamento.PIX, "80")), null))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> lancar(FormaPagamento.DIVIDIDO, null, List.of(
                parte(FormaPagamento.PIX, "40"), parte(FormaPagamento.CORTESIA, "40")), null))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Desconto numa forma só: total menor e a taxa calculada sobre o valor com desconto")
    void descontoPagamentoUnico() {
        Pedido p = lancar(FormaPagamento.CREDITO, "10", null, null);

        assertThat(p.getValorTotal()).isEqualByComparingTo("70");
        assertThat(p.recebimentos()).containsOnlyKeys(FormaPagamento.CREDITO);
        assertThat(p.getDistribuicao().taxaPagamento())
                .isEqualByComparingTo(Dinheiro.percentual(bd("70"), financeiro.obter().getTaxaCredito()));
    }

    @Test
    @DisplayName("Desconto do valor todo é recusado (isso é cortesia)")
    void descontoDoValorTodo() {
        assertThatThrownBy(() -> lancar(FormaPagamento.PIX, "80", null, null))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("Cortesia");
    }

    // ---------- apoio ----------

    private Pedido lancar(FormaPagamento forma, String desconto, List<ParteRequest> partes, String recebido) {
        return pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, forma, null, null, null, null,
                List.of(new ItemRequest(combo.getId(), 1), new ItemRequest(batata.getId(), 1)), null, null, true,
                recebido == null ? null : bd(recebido), false, null, null, desconto == null ? null : bd(desconto), partes), "teste");
    }

    private static ParteRequest parte(FormaPagamento forma, String valor) {
        return new ParteRequest(forma, bd(valor));
    }

    private Produto produto(String nome, String preco, Insumo insumo, String quantidade) {
        return produtos.criar(new ProdutoRequest(nome, null, CategoriaProduto.COMBO, bd(preco), List.of(
                new ItemFichaRequest(insumo.getId(), ModoQuantidade.UNIDADE_BASE, bd(quantidade), null))));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
