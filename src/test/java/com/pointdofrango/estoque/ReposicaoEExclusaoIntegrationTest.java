package com.pointdofrango.estoque;

import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueDtos.ReposicaoResponse;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.CanalVenda;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoService;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoRepository;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ReposicaoEExclusaoIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired ProdutoRepository produtoRepository;
    @Autowired PedidoService pedidos;

    @Test
    @DisplayName("Separar para repor: custo do que foi vendido desde a última compra; zera na nova entrada")
    void reposicao() {
        Insumo cerveja = insumo("Long neck", "7.50", "5");
        Produto venda = revenda(cerveja, "10");

        vender(venda, 3);
        assertThat(paraRepor(cerveja)).hasValueSatisfying(r -> {
            assertThat(r.vendido()).isEqualByComparingTo("3");
            assertThat(r.valor()).isEqualByComparingTo("22.50");
        });

        estoque.registrarEntrada(cerveja.getId(), new EntradaRequest(bd("6"), null, null, bd("45"), "Fardo"), "teste");
        assertThat(paraRepor(cerveja)).isEmpty();

        vender(venda, 1);
        assertThat(paraRepor(cerveja)).hasValueSatisfying(r -> assertThat(r.valor()).isEqualByComparingTo("7.50"));
    }

    @Test
    @DisplayName("Excluir insumo sem vendas leva junto o produto de revenda que nunca foi vendido")
    void excluirSemHistorico() {
        Insumo refri = insumo("Refri", "4", "6");
        Produto venda = revenda(refri, "8");

        estoque.excluir(refri.getId());

        assertThatThrownBy(() -> estoque.buscar(refri.getId())).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThat(produtoRepository.findById(venda.getId())).isEmpty();
    }

    @Test
    @DisplayName("Insumo já vendido, ou usado num prato com outros insumos, não pode ser excluído")
    void excluirComHistorico() {
        Insumo vendido = insumo("Lata", "3", "5");
        vender(revenda(vendido, "6"), 1);
        assertThatThrownBy(() -> estoque.excluir(vendido.getId()))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("desmarque");

        Insumo frango = insumo("Frango", "20", "2");
        Insumo caixa = insumo("Caixa", "1", "10");
        produtos.criar(new ProdutoRequest("Combo " + SEQ.get(), null, CategoriaProduto.PORCAO, bd("40"), List.of(
                new ItemFichaRequest(frango.getId(), bd("0.5")), new ItemFichaRequest(caixa.getId(), bd("1")))));
        assertThatThrownBy(() -> estoque.excluir(caixa.getId()))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("Combo");
        assertThat(estoque.buscar(caixa.getId())).isNotNull();
    }

    @Test
    @DisplayName("Compra sem nota: entrada de cada item com o próprio custo médio e um total único")
    void compraComVariosItens() {
        Insumo oleo = insumo("Oleo", "8", "10");
        Insumo sal = insumo("Sal", "2", "0");

        var r = estoque.registrarCompra(new EstoqueDtos.CompraRequest(List.of(
                new EstoqueDtos.ItemCompra(oleo.getId(), bd("10"), null, null, bd("100")),
                new EstoqueDtos.ItemCompra(sal.getId(), bd("5"), null, null, bd("15"))), "Cupom mercado", null), "teste");

        assertThat(r.itens()).isEqualTo(2);
        assertThat(r.valorTotal()).isEqualByComparingTo("115.00");
        Insumo oleoDepois = estoque.buscar(oleo.getId());
        assertThat(oleoDepois.getEstoqueAtual()).isEqualByComparingTo("20");
        assertThat(oleoDepois.getCustoUnitario()).isEqualByComparingTo("9"); // (10 × 8 + 100) / 20
        assertThat(estoque.buscar(sal.getId()).getCustoUnitario()).isEqualByComparingTo("3");
    }

    private Insumo insumo(String nome, String custo, String estoqueInicial) {
        return estoque.criar(new InsumoRequest("Repor " + nome + " " + SEQ.incrementAndGet(), UnidadeMedida.UNIDADE, bd("0"), bd(custo),
                bd(estoqueInicial)), "teste");
    }

    private Produto revenda(Insumo insumo, String preco) {
        return produtos.criar(new ProdutoRequest(insumo.getNome(), null, CategoriaProduto.BEBIDA, bd(preco),
                List.of(new ItemFichaRequest(insumo.getId(), bd("1")))));
    }

    private void vender(Produto produto, int quantidade) {
        pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(produto.getId(), quantidade))), "caixa");
    }

    private Optional<ReposicaoResponse> paraRepor(Insumo insumo) {
        return estoque.reposicao().stream().filter(r -> r.insumoId().equals(insumo.getId())).findFirst();
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
