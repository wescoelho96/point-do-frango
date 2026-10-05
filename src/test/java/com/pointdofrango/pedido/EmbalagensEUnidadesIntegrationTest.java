package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.EmbalagemRequest;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.ModoQuantidade;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Saco de batata de 2 kg, anel de cebola de 1 kg e isca contada por unidade. */
@SpringBootTest
@ActiveProfiles("test")
class EmbalagensEUnidadesIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired PedidoService pedidos;

    private int n;
    private Insumo batata;
    private Insumo isca;

    @BeforeEach
    void cadastro() {
        n = SEQ.incrementAndGet();
        batata = estoque.criar(new InsumoRequest("Batata " + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("9"), null,
                null, null, List.of(new EmbalagemRequest(null, "Saco 2 kg", bd("2")))), "teste");
        isca = estoque.criar(new InsumoRequest("Isca " + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("30"), null,
                "isca", bd("12"), List.of(new EmbalagemRequest(null, "Saco 6 kg", bd("6")),
                new EmbalagemRequest(null, "Pacote 1 kg", bd("1")))), "teste");
    }

    @Test
    @DisplayName("Entrada por embalagem: 1 saco de 2 kg vira 2 kg no estoque")
    void entradaPorEmbalagem() {
        Long saco = embalagem(batata, "Saco 2 kg");

        Insumo depois = estoque.registrarEntrada(batata.getId(), new EntradaRequest(null, saco, bd("1"), bd("18"), null), "teste");

        assertThat(depois.getEstoqueAtual()).isEqualByComparingTo("2");
        assertThat(depois.getCustoUnitario()).isEqualByComparingTo("9");
        assertThat(estoque.extrato(batata.getId()).get(0).getObservacao()).isEqualTo("1× Saco 2 kg");
    }

    @Test
    @DisplayName("Porção de 300 g: 1 saco de 2 kg faz 6 porções; a 7ª é bloqueada (sobram 200 g)")
    void porcaoDe300g() {
        entrada(batata, "Saco 2 kg", "1");
        Produto porcao = produto("Batata 300g " + n, new ItemFichaRequest(batata.getId(), ModoQuantidade.SUBUNIDADE, bd("300"), null));

        for (int i = 0; i < 6; i++) {
            vender(porcao, 1);
        }

        assertThat(estoque.buscar(batata.getId()).getEstoqueAtual()).isEqualByComparingTo("0.2");
        assertThatThrownBy(() -> vender(porcao, 1)).isInstanceOf(EstoqueInsuficienteException.class);
    }

    @Test
    @DisplayName("Rendimento '7 porções por saco': 7 vendas zeram exatamente o saco")
    void rendimentoPorSaco() {
        entrada(batata, "Saco 2 kg", "1");
        Produto porcao = produto("Batata rende 7 " + n, new ItemFichaRequest(batata.getId(),
                ModoQuantidade.RENDIMENTO_EMBALAGEM, bd("7"), embalagem(batata, "Saco 2 kg")));

        for (int i = 0; i < 7; i++) {
            vender(porcao, 1);
        }

        assertThat(estoque.buscar(batata.getId()).getEstoqueAtual()).isEqualByComparingTo("0");
        assertThat(produtos.buscar(porcao.getId()).getFichaTecnica().get(0).descricao()).isEqualTo("rende 7 por Saco 2 kg");
    }

    @Test
    @DisplayName("Combo especial com 12 iscas baixa exatamente 1 kg")
    void comboDe12Iscas() {
        entrada(isca, "Pacote 1 kg", "2");
        Produto combo = produto("Combo especial " + n, new ItemFichaRequest(isca.getId(), ModoQuantidade.UNIDADE_USO, bd("12"), null));

        vender(combo, 1);

        assertThat(estoque.buscar(isca.getId()).getEstoqueAtual()).isEqualByComparingTo("1");
        assertThat(produtos.buscar(combo.getId()).getFichaTecnica().get(0).descricao()).isEqualTo("12 iscas");
    }

    @Test
    @DisplayName("Mudou de 12 para 15 iscas por kg: a ficha é recalculada (12 iscas passam a ser 0,8 kg)")
    void recalculaAoMudarUnidadeDeUso() {
        Produto combo = produto("Combo recalc " + n, new ItemFichaRequest(isca.getId(), ModoQuantidade.UNIDADE_USO, bd("12"), null));

        estoque.atualizar(isca.getId(), new InsumoRequest(isca.getNome(), UnidadeMedida.QUILOGRAMA, bd("0"), bd("30"), null,
                "isca", bd("15"), embalagensAtuais(isca)));

        assertThat(produtos.buscar(combo.getId()).getFichaTecnica().get(0).getQuantidade()).isEqualByComparingTo("0.8");
    }

    @Test
    @DisplayName("Não deixa apagar a embalagem usada no rendimento de uma ficha")
    void protegeEmbalagemEmUso() {
        produto("Batata protegida " + n, new ItemFichaRequest(batata.getId(),
                ModoQuantidade.RENDIMENTO_EMBALAGEM, bd("7"), embalagem(batata, "Saco 2 kg")));

        assertThatThrownBy(() -> estoque.atualizar(batata.getId(), new InsumoRequest(batata.getNome(),
                UnidadeMedida.QUILOGRAMA, bd("0"), bd("9"), null, null, null, List.of())))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("Saco 2 kg");
        assertThat(estoque.buscar(batata.getId()).getEmbalagens()).hasSize(1);
    }

    @Test
    @DisplayName("Tela de rendimento: quantas porções cada saco rende em cada produto")
    void telaDeRendimento() {
        entrada(batata, "Saco 2 kg", "1");
        produto("Batata 300g R " + n, new ItemFichaRequest(batata.getId(), ModoQuantidade.SUBUNIDADE, bd("300"), null));

        var r = produtos.rendimento(batata.getId());

        assertThat(r.usos()).hasSize(1);
        assertThat(r.usos().get(0).descricao()).isEqualTo("300 g");
        assertThat(r.usos().get(0).unidadesComEstoqueAtual()).isEqualTo(6);
        assertThat(r.usos().get(0).rendimentos().get(0).rende()).isEqualByComparingTo("6.67");
    }

    // ---------- apoio ----------

    private Produto produto(String nome, ItemFichaRequest item) {
        return produtos.criar(new ProdutoRequest(nome, null, CategoriaProduto.PORCAO, bd("30"), List.of(item)));
    }

    private void vender(Produto p, int qtd) {
        pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(p.getId(), qtd))), "teste");
    }

    private void entrada(Insumo insumo, String embalagem, String qtd) {
        estoque.registrarEntrada(insumo.getId(),
                new EntradaRequest(null, embalagem(insumo, embalagem), bd(qtd), bd("10"), null), "teste");
    }

    private Long embalagem(Insumo insumo, String nome) {
        return estoque.buscar(insumo.getId()).getEmbalagens().stream()
                .filter(e -> e.getNome().equals(nome)).findFirst().orElseThrow().getId();
    }

    private List<EmbalagemRequest> embalagensAtuais(Insumo insumo) {
        return estoque.buscar(insumo.getId()).getEmbalagens().stream()
                .map(e -> new EmbalagemRequest(e.getId(), e.getNome(), e.getConteudo())).toList();
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
