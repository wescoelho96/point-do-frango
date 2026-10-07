package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.painel.PainelDtos.Agrupamento;
import com.pointdofrango.painel.PainelService;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoDtos.PedidoPlataformaRequest;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pedidos de iFood e 99Food lançados no fim do dia com as taxas do extrato. */
@SpringBootTest
@ActiveProfiles("test")
class PedidoPlataformaIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired PedidoService pedidos;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired PainelService painel;
    @Autowired ZoneId zona;

    private int n;
    private Insumo isca;
    private Produto porcao; // cardápio R$ 38,00, custo R$ 12,50 (0,5 kg a R$ 25)

    @BeforeEach
    void cardapio() {
        n = SEQ.incrementAndGet();
        isca = estoque.criar(new InsumoRequest("Isca P" + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("25"), bd("10")), "teste");
        porcao = produtos.criar(new ProdutoRequest("Porção P" + n, null, CategoriaProduto.PORCAO, bd("38"),
                List.of(new ItemFichaRequest(isca.getId(), bd("0.5")))));
    }

    @Test
    @DisplayName("iFood: R$ 45 no app, R$ 6,84 de taxas → potes sobre R$ 45, taxa exata do app, estoque baixado")
    void pedidoIfood() {
        Pedido p = pedidos.lancarDePlataforma(ifood("IF-" + n, "45.00", null, "6.84"), "dono");

        assertThat(p.getCanal()).isEqualTo(CanalVenda.IFOOD);
        assertThat(p.getFormaPagamento()).isEqualTo(FormaPagamento.PAGO_NO_APP);
        assertThat(p.getValorTotal()).isEqualByComparingTo("45.00");            // preço do app, não do cardápio
        assertThat(p.valorCardapio()).isEqualByComparingTo("38.00");
        assertThat(p.getDistribuicao().taxaPagamento()).isEqualByComparingTo("6.84");
        assertThat(p.getDistribuicao().reposicaoEstoque()).isEqualByComparingTo("12.50");
        assertThat(p.getDistribuicao().contasFixas()).isEqualByComparingTo("9.00");  // 20% de 45
        assertThat(p.getDistribuicao().valorBruto()).isEqualByComparingTo("45.00");
        assertThat(estoque.buscar(isca.getId()).getEstoqueAtual()).isEqualByComparingTo("9.5");
    }

    @Test
    @DisplayName("Promoção bancada pela loja reduz o vendido: R$ 45 − R$ 5 de cupom = R$ 40")
    void promocaoDaLoja() {
        Pedido p = pedidos.lancarDePlataforma(ifood("IF-PROMO-" + n, "45.00", "5.00", "6.08"), "dono");

        assertThat(p.getValorTotal()).isEqualByComparingTo("40.00");
        assertThat(p.getDescontoLoja()).isEqualByComparingTo("5.00");
        assertThat(p.getDistribuicao().valorBruto()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("Lançamento no fim do dia: já nasce entregue, com a hora real, e não aparece na cozinha")
    void lancamentoPosterior() {
        LocalDateTime horaReal = LocalDateTime.now(zona).minusMinutes(30).withSecond(0).withNano(0);
        var req = new PedidoPlataformaRequest(CanalVenda.NOVENTA_NOVE_FOOD, "99-" + n, horaReal, "Ana", null,
                List.of(new ItemRequest(porcao.getId(), 1)), bd("40.00"), null, bd("0"), false);

        Pedido p = pedidos.lancarDePlataforma(req, "dono");

        assertThat(p.getStatus()).isEqualTo(StatusPedido.ENTREGUE);
        assertThat(p.getCriadoEm()).isEqualTo(horaReal.atZone(zona).toInstant());
        assertThat(pedidos.filaDaCozinha()).extracting(Pedido::getId).doesNotContain(p.getId());
    }

    @Test
    @DisplayName("Com 'enviar para a cozinha', entra na fila como qualquer pedido")
    void enviaParaCozinha() {
        var req = new PedidoPlataformaRequest(CanalVenda.IFOOD, "IF-COZ-" + n, null, null, null,
                List.of(new ItemRequest(porcao.getId(), 1)), bd("45"), null, bd("6.84"), true);

        Pedido p = pedidos.lancarDePlataforma(req, "caixa");

        assertThat(p.getStatus()).isEqualTo(StatusPedido.EM_PREPARO);
        assertThat(pedidos.filaDaCozinha()).extracting(Pedido::getId).contains(p.getId());
    }

    @Test
    @DisplayName("Regras: não lança o mesmo nº de pedido duas vezes, nem data futura, nem taxa maior que o pedido")
    void regras() {
        pedidos.lancarDePlataforma(ifood("IF-DUP-" + n, "45", null, "6.84"), "dono");

        assertThatThrownBy(() -> pedidos.lancarDePlataforma(ifood("IF-DUP-" + n, "45", null, "6.84"), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("já foi lançado");
        assertThatThrownBy(() -> pedidos.lancarDePlataforma(new PedidoPlataformaRequest(CanalVenda.IFOOD, null,
                LocalDateTime.now(zona).plusDays(1), null, null, List.of(new ItemRequest(porcao.getId(), 1)),
                bd("45"), null, bd("1"), false), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("futuro");
        assertThatThrownBy(() -> pedidos.lancarDePlataforma(ifood(null, "45", null, "50"), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("taxas");
        assertThatThrownBy(() -> pedidos.lancarDePlataforma(new PedidoPlataformaRequest(CanalVenda.BALCAO, null, null,
                null, null, List.of(new ItemRequest(porcao.getId(), 1)), bd("45"), null, bd("1"), false), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("plataforma");
        // "Pago no app" não vale para venda de balcão, e iFood não entra pelo PDV comum (sem as taxas)
        assertThatThrownBy(() -> pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PAGO_NO_APP,
                null, null, null, null, List.of(new ItemRequest(porcao.getId(), 1))), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> pedidos.lancar(new NovoPedidoRequest(CanalVenda.IFOOD, FormaPagamento.PIX,
                null, null, null, null, List.of(new ItemRequest(porcao.getId(), 1))), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("taxas do app");
    }

    @Test
    @DisplayName("Painel: Balcão+Mesa e WhatsApp+Telefone agrupados; iFood e 99Food aparecem com as taxas")
    void painelAgrupaCanais() {
        pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(porcao.getId(), 1))), "caixa");
        pedidos.lancarDePlataforma(ifood("IF-PAINEL-" + n, "45", null, "6.84"), "dono");

        List<Agrupamento> canais = painel.resumo(LocalDate.now(zona), LocalDate.now(zona)).porCanal();

        assertThat(canais).extracting(Agrupamento::rotulo)
                .containsExactly("Balcão / Mesa", "WhatsApp / Telefone", "iFood", "99Food");
        Agrupamento ifood = canais.get(2);
        assertThat(ifood.pedidos()).isPositive();
        assertThat(ifood.taxas()).isGreaterThanOrEqualTo(bd("6.84"));
    }

    private PedidoPlataformaRequest ifood(String codigo, String valor, String desconto, String taxas) {
        return new PedidoPlataformaRequest(CanalVenda.IFOOD, codigo, null, null, null,
                List.of(new ItemRequest(porcao.getId(), 1)), bd(valor), desconto == null ? null : bd(desconto),
                bd(taxas), false);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
