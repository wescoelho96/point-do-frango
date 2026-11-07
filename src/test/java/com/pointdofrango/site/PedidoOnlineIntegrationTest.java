package com.pointdofrango.site;

import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.entrega.Bairro;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.CanalVenda;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoService;
import com.pointdofrango.pedido.StatusPedido;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.RegraDeNegocioException;
import com.pointdofrango.site.PedidoOnlineDtos.Item;
import com.pointdofrango.site.PedidoOnlineDtos.PedidoCriado;
import com.pointdofrango.site.PedidoOnlineDtos.PedidoRequest;
import org.junit.jupiter.api.AfterEach;
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

/** Cliente pede pelo link do WhatsApp: escolhe o combo, o bairro, e o pedido cai na cozinha. */
@SpringBootTest
@ActiveProfiles("test")
class PedidoOnlineIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired PedidoOnlineService site;
    @Autowired PedidoService pedidos;
    @Autowired CaixaService caixa;
    @Autowired EntregaCadastroService cadastros;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;

    private int n;
    private Produto combo; // R$ 55
    private Bairro bairro; // taxa R$ 6
    private boolean abriCaixa;

    @BeforeEach
    void cardapio() {
        n = SEQ.incrementAndGet();
        Insumo frango = estoque.criar(new InsumoRequest("Frango site " + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("20"), bd("10")), "teste");
        combo = produtos.criar(new ProdutoRequest("Combo site " + n, "8 iscas + batata", CategoriaProduto.COMBO, bd("55"),
                List.of(new ItemFichaRequest(frango.getId(), bd("0.5")))));
        bairro = cadastros.criarBairro("Bairro site " + n, bd("6"));
    }

    @AfterEach
    void fecharCaixa() {
        if (abriCaixa) {
            caixa.fechar(caixa.totais(caixa.atual().orElseThrow()).dinheiroEsperado(), "teste do site", "teste");
            abriCaixa = false;
        }
    }

    @Test
    @DisplayName("Com o caixa aberto, o pedido entra como Site, vai para a cozinha e cobra a taxa do bairro")
    void pedidoComEntrega() {
        abrirCaixa();
        PedidoCriado criado = site.pedir(pedido("1199990" + String.format("%04d", n), bairro.getId(), FormaPagamento.DINHEIRO,
                bd("150"), null), "10.0.0." + n);

        assertThat(criado.itens()).isEqualByComparingTo("110");
        assertThat(criado.taxaEntrega()).isEqualByComparingTo("6");
        assertThat(criado.total()).isEqualByComparingTo("116");
        assertThat(criado.troco()).isEqualByComparingTo("34");
        Pedido p = pedidos.buscar(criado.numero());
        assertThat(p.getCanal()).isEqualTo(CanalVenda.SITE);
        assertThat(p.getCriadoPor()).isEqualTo("site");
        assertThat(p.getStatus()).isEqualTo(StatusPedido.EM_PREPARO);
        assertThat(pedidos.filaDaCozinha()).extracting(Pedido::getId).contains(p.getId());
        assertThat(p.getBairroEntrega()).isEqualTo(bairro.getNome());
    }

    @Test
    @DisplayName("Loja fechada (caixa fechado), campo anti-robô preenchido e telefone sem DDD são recusados")
    void recusas() {
        if (caixa.atual().isEmpty()) {
            assertThatThrownBy(() -> site.pedir(pedido("11988887777", null, FormaPagamento.PIX, null, null), "10.1.0." + n))
                    .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("fechada");
        }
        abrirCaixa();
        assertThatThrownBy(() -> site.pedir(pedido("11988887777", null, FormaPagamento.PIX, null, "http://spam"), "10.1.0." + n))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> site.pedir(pedido("98887777", null, FormaPagamento.PIX, null, null), "10.1.0." + n))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("DDD");
        assertThatThrownBy(() -> site.pedir(pedido("11988887777", null, FormaPagamento.CORTESIA, null, null), "10.1.0." + n))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Retirada não cobra entrega; o mesmo telefone só faz 3 pedidos seguidos")
    void retiradaELimite() {
        abrirCaixa();
        String telefone = "2199991" + String.format("%04d", n);
        for (int i = 0; i < 3; i++) {
            PedidoCriado c = site.pedir(pedido(telefone, null, FormaPagamento.PIX, null, null), "10.2." + n + "." + i);
            assertThat(c.entrega()).isFalse();
            assertThat(c.total()).isEqualByComparingTo("110");
            assertThat(pedidos.buscar(c.numero()).getObservacao()).contains("Retirada");
        }
        assertThatThrownBy(() -> site.pedir(pedido(telefone, null, FormaPagamento.PIX, null, null), "10.2." + n + ".9"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("vários pedidos");
    }

    private void abrirCaixa() {
        if (caixa.atual().isEmpty()) {
            caixa.abrir(bd("50"), "teste");
            abriCaixa = true;
        }
    }

    private PedidoRequest pedido(String telefone, Long bairroId, FormaPagamento forma, BigDecimal troco, String robo) {
        return new PedidoRequest("Cliente " + n, telefone, bairroId, bairroId != null ? "Rua A, 10" : null, null, forma, troco,
                null, false, List.of(new Item(combo.getId(), 2)), robo);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
