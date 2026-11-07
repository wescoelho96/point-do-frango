package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.loja.LojaService;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mesa de amigos que paga a conta em partes: dinheiro, PIX, cartão. */
@SpringBootTest
@ActiveProfiles("test")
class ComandaDivididaIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ComandaService comandas;
    @Autowired PedidoService pedidos;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired LojaService loja;

    private int n;
    private Produto porcao; // R$ 25,00

    @BeforeEach
    void cardapio() {
        n = SEQ.incrementAndGet();
        Insumo batata = estoque.criar(new InsumoRequest("Batata D" + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("10"), bd("10")), "teste");
        porcao = produtos.criar(new ProdutoRequest("Porção D" + n, null, CategoriaProduto.PORCAO, bd("25"),
                List.of(new ItemFichaRequest(batata.getId(), bd("0.3")))));
    }

    @Test
    @DisplayName("Conta de R$ 75 sem serviço: R$ 40 no PIX e R$ 35 em dinheiro (deu 50, troco 15)")
    void contaDividida() {
        Comanda mesa = comandas.abrir("Mesa D" + n, null, "caixa");
        Pedido p1 = pedidos.lancar(naComanda(mesa, 2), "caixa");
        Pedido p2 = pedidos.lancar(naComanda(mesa, 1), "caixa");

        Comanda fechada = comandas.fechar(mesa.getId(), FormaPagamento.DIVIDIDO, false, bd("50"),
                List.of(parte(FormaPagamento.PIX, "40"), parte(FormaPagamento.DINHEIRO, "35")), "caixa");

        assertThat(fechada.getStatus()).isEqualTo(StatusComanda.FECHADA);
        assertThat(fechada.getValorTotal()).isEqualByComparingTo("75");
        assertThat(fechada.recebimentos().get(FormaPagamento.PIX)).isEqualByComparingTo("40");
        assertThat(fechada.emDinheiro()).isEqualByComparingTo("35");
        assertThat(fechada.troco()).isEqualByComparingTo("15");
        // cada pedido fica com a sua fatia, e as fatias somam as partes
        List<Pedido> doMesa = fechada.pedidosValidos();
        assertThat(doMesa).extracting(Pedido::getId).containsExactlyInAnyOrder(p1.getId(), p2.getId());
        assertThat(somaPorForma(doMesa)).containsEntry(FormaPagamento.PIX, bd("40.00")).containsEntry(FormaPagamento.DINHEIRO, bd("35.00"));
        doMesa.forEach(p -> assertThat(p.recebimentos().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(p.getValorTotal()));
    }

    @Test
    @DisplayName("Com serviço, as partes somam o total com os 10% e os pedidos ficam só com o valor dos itens")
    void contaDivididaComServico() {
        Comanda mesa = comandas.abrir("Mesa DS" + n, null, "caixa");
        pedidos.lancar(naComanda(mesa, 1), "caixa");
        pedidos.lancar(naComanda(mesa, 2), "caixa");
        BigDecimal total = bd("75").add(Comanda.servicoSobre(bd("75"), loja.obter().getPercentualServico()));
        // 3 pessoas: crédito, débito e o resto no PIX
        BigDecimal pix = total.subtract(bd("60"));
        Comanda fechada = comandas.fechar(mesa.getId(), FormaPagamento.DIVIDIDO, true, null,
                List.of(parte(FormaPagamento.CREDITO, "30"), parte(FormaPagamento.DEBITO, "30"), new PartePagamento(FormaPagamento.PIX, pix)),
                "caixa");

        Map<FormaPagamento, BigDecimal> dosPedidos = somaPorForma(fechada.pedidosValidos());
        assertThat(dosPedidos.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(fechada.getValorItens());
        assertThat(dosPedidos.keySet()).containsExactlyInAnyOrder(FormaPagamento.CREDITO, FormaPagamento.DEBITO, FormaPagamento.PIX);
        assertThat(fechada.troco()).isNull();
    }

    @Test
    @DisplayName("Partes que não fecham o total, ou partes sem conta dividida, são recusadas")
    void partesErradas() {
        Comanda mesa = comandas.abrir("Mesa DE" + n, null, "caixa");
        pedidos.lancar(naComanda(mesa, 2), "caixa");

        assertThatThrownBy(() -> comandas.fechar(mesa.getId(), FormaPagamento.DIVIDIDO, false, null,
                List.of(parte(FormaPagamento.PIX, "20"), parte(FormaPagamento.DEBITO, "20")), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("50");
        assertThatThrownBy(() -> comandas.fechar(mesa.getId(), FormaPagamento.PIX, false, null,
                List.of(parte(FormaPagamento.PIX, "25"), parte(FormaPagamento.DEBITO, "25")), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> comandas.fechar(mesa.getId(), FormaPagamento.DIVIDIDO, false, null,
                List.of(parte(FormaPagamento.PIX, "50")), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThat(comandas.buscar(mesa.getId()).getStatus()).isEqualTo(StatusComanda.ABERTA);
    }

    private static Map<FormaPagamento, BigDecimal> somaPorForma(List<Pedido> lista) {
        Map<FormaPagamento, BigDecimal> soma = new EnumMap<>(FormaPagamento.class);
        lista.forEach(p -> p.recebimentos().forEach((f, v) -> soma.merge(f, v, BigDecimal::add)));
        return soma;
    }

    private NovoPedidoRequest naComanda(Comanda c, int qtd) {
        return new NovoPedidoRequest(CanalVenda.MESA, null, null, null, null, null,
                List.of(new ItemRequest(porcao.getId(), qtd)), c.getId());
    }

    private static PartePagamento parte(FormaPagamento forma, String valor) {
        return new PartePagamento(forma, bd(valor));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
