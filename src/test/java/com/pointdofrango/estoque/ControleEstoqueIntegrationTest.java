package com.pointdofrango.estoque;

import com.pointdofrango.estoque.EstoqueDtos.AjusteRequest;
import com.pointdofrango.estoque.EstoqueDtos.ContagemRequest;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.IndicadorInsumo;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueDtos.ItemContado;
import com.pointdofrango.estoque.EstoqueDtos.LoteResponse;
import com.pointdofrango.estoque.EstoqueDtos.StatusEstoque;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.painel.PainelService;
import com.pointdofrango.pedido.CanalVenda;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoService;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ControleEstoqueIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final LocalDate HOJE = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

    @Autowired EstoqueService estoque;
    @Autowired ControleEstoqueService controle;
    @Autowired ProdutoService produtos;
    @Autowired PedidoService pedidos;
    @Autowired PainelService painel;

    @Test
    @DisplayName("Status: zerado é crítico; 10 un vendendo 3 por dia dá 3,3 dias (alerta); sem venda e com saldo é normal")
    void status() {
        Insumo zerado = insumo("Zerado", "0", "2");
        Insumo girando = insumo("Girando", "100", "1");
        Insumo parado = insumo("Parado", "5", "1");
        vender(revenda(girando), 90); // 90 em 30 dias = 3 por dia, sobram 10

        IndicadorInsumo z = indicador(zerado);
        IndicadorInsumo g = indicador(girando);
        IndicadorInsumo p = indicador(parado);

        assertThat(z.status()).isEqualTo(StatusEstoque.CRITICO);
        assertThat(g.consumoDiario()).isEqualByComparingTo("3");
        assertThat(g.coberturaDias()).isEqualByComparingTo("3.3");
        assertThat(g.status()).isEqualTo(StatusEstoque.ALERTA);
        assertThat(g.minimoSugerido()).isEqualByComparingTo("9");
        assertThat(p.status()).isEqualTo(StatusEstoque.NORMAL);
        assertThat(p.coberturaDias()).isNull();
    }

    @Test
    @DisplayName("Furo: 5 vendidas e a contagem achou 2 a menos (perda de R$ 6, 40% do que saiu)")
    void furo() {
        Insumo lata = insumo("Lata furo", "20", "3");
        vender(revenda(lata), 5);
        estoque.registrarAjuste(lata.getId(), new AjusteRequest(bd("13"), "Contagem"), "t");

        var furo = controle.furo(HOJE, HOJE).stream().filter(f -> f.insumoId().equals(lata.getId())).findFirst().orElseThrow();

        assertThat(furo.vendido()).isEqualByComparingTo("5");
        assertThat(furo.perdas()).isEqualByComparingTo("2");
        assertThat(furo.valorPerdas()).isEqualByComparingTo("6.00");
        assertThat(furo.percentualPerda()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("Validade: o saldo fica com os lotes mais novos; o lote antigo já consumido some da lista")
    void validades() {
        Insumo molho = insumo("Molho validade", "0", "10");
        estoque.registrarEntrada(molho.getId(), new EntradaRequest(bd("4"), null, null, bd("40"), "lote 1", null,
                HOJE.plusDays(3)), "t");
        estoque.registrarEntrada(molho.getId(), new EntradaRequest(bd("6"), null, null, bd("60"), "lote 2", null,
                HOJE.plusDays(30)), "t");
        estoque.registrarAjuste(molho.getId(), new AjusteRequest(bd("7"), "usado"), "t"); // saíram 3 do lote antigo

        List<LoteResponse> lotes = controle.lotes().stream().filter(l -> l.insumoId().equals(molho.getId())).toList();

        assertThat(lotes).extracting(LoteResponse::restante).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("1"), bd("6"));
        assertThat(lotes.get(0).diasParaVencer()).isEqualTo(3);
    }

    @Test
    @DisplayName("Contagem em lote: só ajusta o que mudou e grava o local do item")
    void contagemELocal() {
        Insumo a = estoque.criar(new InsumoRequest("Freezer A " + SEQ.incrementAndGet(), UnidadeMedida.QUILOGRAMA, bd("0"),
                bd("10"), bd("5"), null, null, null, null, null, "Freezer"), "t");
        Insumo b = insumo("Freezer B", "3", "10");

        int ajustados = estoque.registrarContagem(new ContagemRequest("Contagem do freezer",
                List.of(new ItemContado(a.getId(), bd("5")), new ItemContado(b.getId(), bd("2")))), "t");

        assertThat(ajustados).isEqualTo(1);
        assertThat(estoque.buscar(b.getId()).getEstoqueAtual()).isEqualByComparingTo("2");
        assertThat(estoque.buscar(a.getId()).getLocal()).isEqualTo("Freezer");
    }

    @Test
    @DisplayName("Margem por produto: faturamento, custo e lucro reais das vendas do período")
    void margens() {
        Insumo refri = insumo("Refri margem", "10", "4");
        Produto venda = revenda(refri);
        vender(venda, 3);

        var m = painel.margens(HOJE, HOJE).stream().filter(x -> x.produtoId().equals(venda.getId())).findFirst().orElseThrow();

        assertThat(m.faturamento()).isEqualByComparingTo("30.00");
        assertThat(m.custo()).isEqualByComparingTo("12.00");
        assertThat(m.lucro()).isEqualByComparingTo("18.00");
        assertThat(m.margemPercentual()).isEqualByComparingTo("60");
    }

    private IndicadorInsumo indicador(Insumo i) {
        return controle.indicadores(30).stream().filter(x -> x.insumoId().equals(i.getId())).findFirst().orElseThrow();
    }

    private Insumo insumo(String nome, String saldo, String custo) {
        return estoque.criar(new InsumoRequest(nome + " " + SEQ.incrementAndGet(), UnidadeMedida.UNIDADE, bd("0"), bd(custo),
                bd(saldo)), "t");
    }

    private Produto revenda(Insumo insumo) {
        return produtos.criar(new ProdutoRequest(insumo.getNome() + " venda " + SEQ.incrementAndGet(), null,
                CategoriaProduto.BEBIDA, bd("10"), List.of(new ItemFichaRequest(insumo.getId(), bd("1")))));
    }

    private void vender(Produto produto, int quantidade) {
        pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(produto.getId(), quantidade))), "caixa");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
