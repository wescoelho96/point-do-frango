package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.TipoMovimentacao;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.EstoqueInsuficienteException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fluxo completo com H2 e Flyway: pedido, baixa de estoque, potes e cancelamento. */
@SpringBootTest
@ActiveProfiles("test")
class PedidoFluxoIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired PedidoService pedidos;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;

    private Insumo frango;
    private Insumo embalagem;
    private Produto isca;

    @BeforeEach
    void cardapio() {
        int n = SEQ.incrementAndGet();
        // 3 kg de frango a R$ 20/kg e 10 embalagens a R$ 1
        frango = estoque.criar(new InsumoRequest("Frango " + n, UnidadeMedida.QUILOGRAMA, bd("0.5"), bd("20"), bd("3")), "teste");
        embalagem = estoque.criar(new InsumoRequest("Embalagem " + n, UnidadeMedida.UNIDADE, bd("2"), bd("1"), bd("10")), "teste");
        // Isca 300 g: 0,3 kg de frango + 1 embalagem = custo R$ 7,00; vende a R$ 30
        isca = produtos.criar(new ProdutoRequest("Isca " + n, null, CategoriaProduto.PORCAO, bd("30"), List.of(
                new ItemFichaRequest(frango.getId(), bd("0.300")),
                new ItemFichaRequest(embalagem.getId(), bd("1")))));
    }

    @Test
    @DisplayName("Lançar pedido baixa os insumos pela ficha técnica e grava os potes")
    void lancarPedido() {
        Pedido p = pedidos.lancar(pedido(FormaPagamento.PIX, 2), "caixa");

        assertThat(p.getStatus()).isEqualTo(StatusPedido.EM_PREPARO);
        assertThat(p.getValorTotal()).isEqualByComparingTo("60.00");
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("2.400");
        assertThat(estoque.buscar(embalagem.getId()).getEstoqueAtual()).isEqualByComparingTo("8");

        var d = p.getDistribuicao();
        assertThat(d.reposicaoEstoque()).isEqualByComparingTo("14.00"); // 2 × R$ 7
        assertThat(d.contasFixas()).isEqualByComparingTo("12.00");      // 20% de 60
        assertThat(d.lucroLiquido()).isEqualByComparingTo("34.00");
        assertThat(d.proLabore()).isEqualByComparingTo("27.20");
        assertThat(d.reservaEmergencia()).isEqualByComparingTo("6.80");
        assertThat(d.valorBruto()).isEqualByComparingTo(p.getValorTotal());

        assertThat(estoque.extrato(frango.getId()))
                .anyMatch(m -> m.getTipo() == TipoMovimentacao.SAIDA_VENDA && p.getId().equals(m.getPedidoId()));
    }

    @Test
    @DisplayName("Itens repetidos do mesmo produto são somados")
    void itensRepetidos() {
        var req = new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.DINHEIRO, null, null, null, null,
                List.of(new ItemRequest(isca.getId(), 1), new ItemRequest(isca.getId(), 2)));

        Pedido p = pedidos.lancar(req, "caixa");

        assertThat(p.getItens()).hasSize(1);
        assertThat(p.getItens().get(0).getQuantidade()).isEqualTo(3);
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("2.100");
    }

    @Test
    @DisplayName("Sem estoque: pedido é recusado e NADA é baixado (atomicidade)")
    void estoqueInsuficiente() {
        // 11 iscas precisam de 11 embalagens (há 10) e 3,3 kg de frango (há 3)
        assertThatThrownBy(() -> pedidos.lancar(pedido(FormaPagamento.PIX, 11), "caixa"))
                .isInstanceOf(EstoqueInsuficienteException.class)
                .satisfies(e -> assertThat(((EstoqueInsuficienteException) e).getFaltas()).hasSize(2));

        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("3");
        assertThat(estoque.buscar(embalagem.getId()).getEstoqueAtual()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Cancelar devolve ao estoque exatamente o que saiu")
    void cancelarEstorna() {
        Pedido p = pedidos.lancar(pedido(FormaPagamento.CREDITO, 3), "caixa");
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("2.100");

        Pedido cancelado = pedidos.cancelar(p.getId(), "cliente desistiu", "dono");

        assertThat(cancelado.getStatus()).isEqualTo(StatusPedido.CANCELADO);
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("3");
        assertThat(estoque.buscar(embalagem.getId()).getEstoqueAtual()).isEqualByComparingTo("10");
        assertThatThrownBy(() -> pedidos.cancelar(p.getId(), "de novo", "dono")).isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Mudar o preço depois não altera o valor de pedidos já lançados (snapshot)")
    void snapshotDePreco() {
        Pedido p = pedidos.lancar(pedido(FormaPagamento.PIX, 1), "caixa");

        produtos.atualizar(isca.getId(), new ProdutoRequest(isca.getNome(), null, CategoriaProduto.PORCAO, bd("99"),
                List.of(new ItemFichaRequest(frango.getId(), bd("0.300")), new ItemFichaRequest(embalagem.getId(), bd("1")))));

        assertThat(pedidos.buscar(p.getId()).getValorTotal()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("Produto fora do cardápio não pode ser vendido")
    void produtoInativo() {
        produtos.alterarAtivo(isca.getId(), false);

        assertThatThrownBy(() -> pedidos.lancar(pedido(FormaPagamento.PIX, 1), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Concorrência: balcão e WhatsApp disputando o último estoque não vendem a mais")
    void concorrencia() throws Exception {
        // Há frango para 10 iscas e embalagem para 10. Vinte pedidos simultâneos de 1 isca.
        int tentativas = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        for (int i = 0; i < tentativas; i++) {
            resultados.add(pool.submit(() -> {
                largada.await();
                try {
                    pedidos.lancar(pedido(FormaPagamento.PIX, 1), "concorrente");
                    return true;
                } catch (EstoqueInsuficienteException e) {
                    return false;
                }
            }));
        }
        largada.countDown();
        int vendidos = 0;
        for (Future<Boolean> r : resultados) {
            if (r.get()) {
                vendidos++;
            }
        }
        pool.shutdown();

        assertThat(vendidos).isEqualTo(10);
        assertThat(estoque.buscar(embalagem.getId()).getEstoqueAtual()).isEqualByComparingTo("0");
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("0");
    }

    private NovoPedidoRequest pedido(FormaPagamento forma, int quantidade) {
        return new NovoPedidoRequest(CanalVenda.BALCAO, forma, null, null, null, null,
                List.of(new ItemRequest(isca.getId(), quantidade)));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
