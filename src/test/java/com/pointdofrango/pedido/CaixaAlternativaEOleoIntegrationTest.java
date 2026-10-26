package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EstoqueDtos.AjusteRequest;
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
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Combo especial: 1 caixa 30x30 ou, quando ela acaba, 2 caixas Assaí. Óleo reaproveitado não trava a venda. */
@SpringBootTest
@ActiveProfiles("test")
class CaixaAlternativaEOleoIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired PedidoService pedidos;

    private int n;
    private Insumo frango;
    private Insumo caixa30;
    private Insumo caixaAssai;
    private Insumo oleo;

    @BeforeEach
    void cadastro() {
        n = SEQ.incrementAndGet();
        frango = insumo("CxAlt frango " + n, UnidadeMedida.QUILOGRAMA, "20", "10");
        caixa30 = insumo("CxAlt caixa 30x30 " + n, UnidadeMedida.UNIDADE, "3.49", "1");
        caixaAssai = insumo("CxAlt caixa Assai " + n, UnidadeMedida.UNIDADE, "1", "10");
        oleo = insumo("CxAlt oleo " + n, UnidadeMedida.LITRO, "8", "10");
    }

    @Test
    @DisplayName("3 combos com 1 caixa 30x30: sai a 30x30 e depois 4 Assaí; o custo segue as caixas usadas")
    void usaAlternativaQuandoAPrincipalAcaba() {
        Produto combo = combo();

        Pedido p = vender(combo, 3);

        assertThat(estoque.buscar(caixa30.getId()).getEstoqueAtual()).isEqualByComparingTo("0");
        assertThat(estoque.buscar(caixaAssai.getId()).getEstoqueAtual()).isEqualByComparingTo("6");
        // 0,8 kg x R$ 20 + 0,5 L x R$ 8 por combo + caixas (3,49 + 4 x 1) / 3
        assertThat(p.getItens().get(0).getCustoUnitario())
                .isEqualByComparingTo(new BigDecimal("16").add(new BigDecimal("0.5").multiply(new BigDecimal("8")))
                        .add(new BigDecimal("7.49").divide(new BigDecimal("3"), 10, RoundingMode.HALF_UP)));
    }

    @Test
    @DisplayName("Dá para fazer soma as alternativas e aponta o que falta")
    void disponibilidadeComAlternativa() {
        Produto combo = combo();

        Produto.Disponibilidade d = produtos.buscar(combo.getId()).disponibilidade();
        assertThat(d.unidades()).isEqualTo(6); // 1 caixa 30x30 + 10 Assaí / 2

        vender(combo, 6);
        Produto.Disponibilidade zerado = produtos.buscar(combo.getId()).disponibilidade();
        assertThat(zerado.unidades()).isZero();
        assertThat(zerado.limitadoPor()).isEqualTo(caixa30.getNome() + " ou " + caixaAssai.getNome());
        assertThatThrownBy(() -> vender(combo, 1)).isInstanceOf(EstoqueInsuficienteException.class);
    }

    @Test
    @DisplayName("Na mesa a caixa não sai, nem a principal nem a alternativa")
    void naMesaNenhumaCaixaSai() {
        Produto combo = combo();

        pedidos.lancar(new NovoPedidoRequest(CanalVenda.MESA, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(combo.getId(), 2))), "teste");

        assertThat(estoque.buscar(caixa30.getId()).getEstoqueAtual()).isEqualByComparingTo("1");
        assertThat(estoque.buscar(caixaAssai.getId()).getEstoqueAtual()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Óleo sem trava: vende com saldo zerado, o saldo fica negativo e o óleo não limita o dá para fazer")
    void oleoSemTravaNaoBloqueia() {
        Produto combo = combo();
        zerar(oleo);
        estoque.atualizar(oleo.getId(), new InsumoRequest(oleo.getNome(), UnidadeMedida.LITRO, bd("0"), bd("8"), null,
                null, null, List.of(), null, null, null, false));
        assertThatCode(() -> vender(combo, 1)).doesNotThrowAnyException();

        assertThat(estoque.buscar(oleo.getId()).getEstoqueAtual()).isEqualByComparingTo("-0.5");
        Produto.Disponibilidade d = produtos.buscar(combo.getId()).disponibilidade();
        assertThat(d.unidades()).isEqualTo(5);
        assertThat(d.limitadoPor()).doesNotContain(oleo.getNome());
    }

    @Test
    @DisplayName("Óleo com trava (padrão) bloqueia e é apontado como o que falta")
    void oleoComTravaApontaFalta() {
        Produto combo = combo();
        zerar(oleo);

        Produto.Disponibilidade d = produtos.buscar(combo.getId()).disponibilidade();
        assertThat(d.unidades()).isZero();
        assertThat(d.limitadoPor()).isEqualTo(oleo.getNome());
    }

    @Test
    @DisplayName("Alternativa de um insumo que não está na ficha é recusada")
    void alternativaSemPrincipal() {
        assertThatThrownBy(() -> produtos.criar(new ProdutoRequest("CxAlt combo invalido " + n, null, CategoriaProduto.COMBO,
                bd("75"), List.of(new ItemFichaRequest(frango.getId(), ModoQuantidade.UNIDADE_BASE, bd("0.8"), null),
                new ItemFichaRequest(caixaAssai.getId(), ModoQuantidade.UNIDADE_BASE, bd("2"), null, true, caixa30.getId())))))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    // ---------- apoio ----------

    private Produto combo() {
        return produtos.criar(new ProdutoRequest("CxAlt combo especial " + n, null, CategoriaProduto.COMBO, bd("75"), List.of(
                new ItemFichaRequest(frango.getId(), ModoQuantidade.UNIDADE_BASE, bd("0.8"), null),
                new ItemFichaRequest(oleo.getId(), ModoQuantidade.UNIDADE_BASE, bd("0.5"), null),
                new ItemFichaRequest(caixa30.getId(), ModoQuantidade.UNIDADE_BASE, bd("1"), null, true),
                new ItemFichaRequest(caixaAssai.getId(), ModoQuantidade.UNIDADE_BASE, bd("2"), null, true, caixa30.getId()))));
    }

    private void zerar(Insumo insumo) {
        estoque.registrarAjuste(insumo.getId(), new AjusteRequest(BigDecimal.ZERO, "Contagem"), "teste");
    }

    private Pedido vender(Produto p, int qtd) {
        return pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(p.getId(), qtd))), "teste");
    }

    private Insumo insumo(String nome, UnidadeMedida unidade, String custo, String estoqueInicial) {
        return estoque.criar(new InsumoRequest(nome, unidade, bd("0"), bd(custo), bd(estoqueInicial)), "teste");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
