package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.ComandaDtos.ComandaResponse;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mesa abre comanda, pede em vários momentos, a cozinha recebe cada pedido, e a conta fecha com ou sem os 10%. */
@SpringBootTest
@ActiveProfiles("test")
class ComandaIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ComandaService comandas;
    @Autowired PedidoService pedidos;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;

    private int n;
    private Insumo batata;
    private Produto porcao; // R$ 25,00, custo R$ 3,00 (0,3 kg a R$ 10)

    @BeforeEach
    void cardapio() {
        n = SEQ.incrementAndGet();
        batata = estoque.criar(new InsumoRequest("Batata C" + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("10"), bd("10")), "teste");
        porcao = produtos.criar(new ProdutoRequest("Porção C" + n, null, CategoriaProduto.PORCAO, bd("25"),
                List.of(new ItemFichaRequest(batata.getId(), bd("0.3")))));
    }

    @Test
    @DisplayName("Pedidos da comanda baixam o estoque na hora e vão para a cozinha; pagamento fica para o fim")
    void lancarNaComanda() {
        Comanda mesa = comandas.abrir("Mesa " + n, null, "caixa");

        Pedido p1 = pedidos.lancar(naComanda(mesa, 2), "caixa");
        Pedido p2 = pedidos.lancar(naComanda(mesa, 1), "caixa");

        assertThat(p1.getCanal()).isEqualTo(CanalVenda.MESA);
        assertThat(p1.getFormaPagamento()).isNull();
        assertThat(p1.getClienteNome()).isEqualTo("Mesa " + n);
        assertThat(estoque.buscar(batata.getId()).getEstoqueAtual()).isEqualByComparingTo("9.1");
        assertThat(pedidos.filaDaCozinha()).extracting(Pedido::getId).contains(p1.getId(), p2.getId());

        ComandaResponse conta = ComandaResponse.de(comandas.buscar(mesa.getId()), bd("10"));
        assertThat(conta.consumo()).isEqualByComparingTo("75.00");
        assertThat(conta.valorServico()).isEqualByComparingTo("7.50");
        assertThat(conta.totalComServico()).isEqualByComparingTo("82.50");
        assertThat(conta.itens()).singleElement().satisfies(i -> assertThat(i.quantidade()).isEqualTo(3));
    }

    @Test
    @DisplayName("Fechar COM os 10%: total = consumo + serviço; a taxa de serviço fica fora dos potes")
    void fecharComServico() {
        Comanda mesa = comandas.abrir("Mesa " + n, null, "caixa");
        Pedido p = pedidos.lancar(naComanda(mesa, 2), "caixa");

        Comanda fechada = comandas.fechar(mesa.getId(), FormaPagamento.CREDITO, true, "caixa");

        assertThat(fechada.getStatus()).isEqualTo(StatusComanda.FECHADA);
        assertThat(fechada.getValorItens()).isEqualByComparingTo("50.00");
        assertThat(fechada.getValorServico()).isEqualByComparingTo("5.00");
        assertThat(fechada.getValorTotal()).isEqualByComparingTo("55.00");

        Pedido depois = pedidos.buscar(p.getId());
        assertThat(depois.getFormaPagamento()).isEqualTo(FormaPagamento.CREDITO);
        // taxa da maquininha agora entra no pote (4,98% de 50 = 2,49)
        assertThat(depois.getDistribuicao().taxaPagamento()).isEqualByComparingTo("2.49");
        assertThat(depois.getDistribuicao().valorBruto()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Fechar SEM os 10% (cliente recusou): total = consumo")
    void fecharSemServico() {
        Comanda mesa = comandas.abrir("Mesa " + n, null, "caixa");
        pedidos.lancar(naComanda(mesa, 1), "caixa");

        Comanda fechada = comandas.fechar(mesa.getId(), FormaPagamento.PIX, false, "caixa");

        assertThat(fechada.getValorServico()).isEqualByComparingTo("0");
        assertThat(fechada.getValorTotal()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Pagamento em dinheiro: guarda o valor recebido e calcula o troco")
    void trocoNoDinheiro() {
        Comanda mesa = comandas.abrir("Mesa " + n, null, "caixa");
        pedidos.lancar(naComanda(mesa, 1), "caixa");
        assertThatThrownBy(() -> comandas.fechar(mesa.getId(), FormaPagamento.DINHEIRO, true, bd("20"), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("menor que o total");

        Comanda fechada = comandas.fechar(mesa.getId(), FormaPagamento.DINHEIRO, true, bd("50"), "caixa");

        assertThat(fechada.getValorTotal()).isEqualByComparingTo("27.50");
        assertThat(fechada.getValorRecebido()).isEqualByComparingTo("50.00");
        assertThat(fechada.troco()).isEqualByComparingTo("22.50");
    }

    @Test
    @DisplayName("Pedido cancelado não entra na conta")
    void canceladoForaDaConta() {
        Comanda mesa = comandas.abrir("Mesa " + n, null, "caixa");
        pedidos.lancar(naComanda(mesa, 1), "caixa");
        Pedido errado = pedidos.lancar(naComanda(mesa, 2), "caixa");

        pedidos.cancelar(errado.getId(), "lançado errado", "dono");

        assertThat(ComandaResponse.de(comandas.buscar(mesa.getId()), bd("10")).consumo()).isEqualByComparingTo("25.00");
        assertThat(estoque.buscar(batata.getId()).getEstoqueAtual()).isEqualByComparingTo("9.7");
    }

    @Test
    @DisplayName("Regras: mesa duplicada, comanda fechada não recebe pedido, venda direta exige pagamento")
    void regras() {
        Comanda mesa = comandas.abrir("Mesa " + n, null, "caixa");
        assertThatThrownBy(() -> comandas.abrir("mesa " + n, null, "caixa")).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> comandas.fechar(mesa.getId(), FormaPagamento.PIX, true, "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("não tem consumo");

        pedidos.lancar(naComanda(mesa, 1), "caixa");
        comandas.fechar(mesa.getId(), FormaPagamento.PIX, true, "caixa");

        assertThatThrownBy(() -> pedidos.lancar(naComanda(mesa, 1), "caixa")).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, null, null, null, null, null,
                List.of(new ItemRequest(porcao.getId(), 1))), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("forma de pagamento");
        // a mesma mesa pode abrir uma nova comanda depois de fechar a anterior
        assertThat(comandas.abrir("Mesa " + n, null, "caixa").getStatus()).isEqualTo(StatusComanda.ABERTA);
    }

    @Test
    @DisplayName("Cancelar a comanda devolve ao estoque tudo o que foi pedido")
    void cancelarComanda() {
        Comanda mesa = comandas.abrir("Mesa " + n, null, "caixa");
        pedidos.lancar(naComanda(mesa, 2), "caixa");
        pedidos.lancar(naComanda(mesa, 1), "caixa");

        Comanda cancelada = comandas.cancelar(mesa.getId(), "cliente foi embora", "dono");

        assertThat(cancelada.getStatus()).isEqualTo(StatusComanda.CANCELADA);
        assertThat(cancelada.pedidosValidos()).isEmpty();
        assertThat(estoque.buscar(batata.getId()).getEstoqueAtual()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Histórico por período: fechadas e canceladas entram; período inválido é recusado")
    void historicoPorPeriodo() {
        Comanda fechada = comandas.abrir("Mesa H" + n, null, "caixa");
        pedidos.lancar(naComanda(fechada, 1), "caixa");
        comandas.fechar(fechada.getId(), FormaPagamento.PIX, true, "caixa");
        Comanda aberta = comandas.abrir("Mesa A" + n, null, "caixa");

        java.time.LocalDate hoje = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
        List<Comanda> semana = comandas.encerradasNoPeriodo(hoje.minusDays(6), hoje);

        assertThat(semana).extracting(Comanda::getId).contains(fechada.getId()).doesNotContain(aberta.getId());
        assertThat(comandas.encerradasNoPeriodo(hoje.minusDays(10), hoje.minusDays(8)))
                .extracting(Comanda::getId).doesNotContain(fechada.getId());
        assertThatThrownBy(() -> comandas.encerradasNoPeriodo(hoje, hoje.minusDays(1)))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    private NovoPedidoRequest naComanda(Comanda c, int qtd) {
        return new NovoPedidoRequest(CanalVenda.MESA, null, null, null, null, null,
                List.of(new ItemRequest(porcao.getId(), qtd)), c.getId());
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
