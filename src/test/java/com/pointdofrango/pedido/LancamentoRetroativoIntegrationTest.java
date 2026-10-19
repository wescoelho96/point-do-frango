package com.pointdofrango.pedido;

import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.consumo.ConsumoInternoService;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.ModoQuantidade;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.painel.PainelVendasService;
import com.pointdofrango.painel.PainelVendasService.ProdutoMeta;
import com.pointdofrango.pedido.PedidoDtos.EntregaRequest;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Comanda de papel lançada depois: data da venda, preço cobrado na época e baixa mesmo sem saldo no sistema. */
@SpringBootTest
@ActiveProfiles("test")
class LancamentoRetroativoIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Autowired PedidoService pedidos;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired PainelVendasService painelVendas;
    @Autowired ConsumoInternoService consumo;
    @Autowired EntregaCadastroService cadastros;
    @Autowired CaixaService caixa;

    @Test
    @DisplayName("Venda de 3 dias atrás: fica naquele dia, já entregue, com o preço cobrado, e baixa o estoque mesmo negativo")
    void retroativo() {
        Insumo frango = estoque.criar(new InsumoRequest("Frango retro " + SEQ.incrementAndGet(), UnidadeMedida.QUILOGRAMA,
                bd("0"), bd("20"), bd("0.5")), "t");
        Produto combo = produtos.criar(new ProdutoRequest("Combo retro " + SEQ.get(), null, CategoriaProduto.COMBO, bd("55"),
                List.of(new ItemFichaRequest(frango.getId(), bd("1")))));
        LocalDateTime quando = LocalDateTime.now(SP).minusDays(3).withHour(20).withMinute(15).withSecond(0).withNano(0);

        Pedido p = pedidos.lancar(retroativo(combo, FormaPagamento.PIX, bd("56"), quando), "dono");

        assertThat(p.getStatus()).isEqualTo(StatusPedido.ENTREGUE);
        assertThat(p.getCriadoEm()).isEqualTo(quando.atZone(SP).toInstant());
        assertThat(p.getValorTotal()).isEqualByComparingTo("56.00");
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("-0.5");
    }

    @Test
    @DisplayName("Preço diferente do cardápio só no retroativo; data no futuro ou com mais de 31 dias é recusada")
    void regras() {
        Insumo lata = estoque.criar(new InsumoRequest("Lata retro " + SEQ.incrementAndGet(), UnidadeMedida.UNIDADE,
                bd("0"), bd("3"), bd("10")), "t");
        Produto venda = produtos.criar(new ProdutoRequest("Lata retro venda " + SEQ.get(), null, CategoriaProduto.BEBIDA, bd("6"),
                List.of(new ItemFichaRequest(lata.getId(), bd("1")))));

        assertThatThrownBy(() -> pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.DINHEIRO, null, null,
                null, null, List.of(new ItemRequest(venda.getId(), 1, bd("4")))), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("cardápio");
        assertThatThrownBy(() -> pedidos.lancar(retroativo(venda, FormaPagamento.DINHEIRO, null,
                LocalDateTime.now(SP).plusDays(1)), "dono")).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> pedidos.lancar(retroativo(venda, FormaPagamento.DINHEIRO, null,
                LocalDateTime.now(SP).minusDays(40)), "dono")).isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Juntar cadastros duplicados: saldo e valor vão para o destino pelo custo médio e a origem fica inativa")
    void juntar() {
        Insumo file = estoque.criar(new InsumoRequest("Filé juntar " + SEQ.incrementAndGet(), UnidadeMedida.QUILOGRAMA,
                bd("0"), bd("15"), bd("2")), "t");
        Insumo sassami = estoque.criar(new InsumoRequest("Sassami juntar " + SEQ.get(), UnidadeMedida.QUILOGRAMA,
                bd("0"), bd("18"), bd("6")), "t");

        Insumo destino = estoque.juntar(sassami.getId(), file.getId(), "dono");

        assertThat(destino.getEstoqueAtual()).isEqualByComparingTo("8");
        assertThat(destino.getCustoUnitario()).isEqualByComparingTo("17.25"); // (2 × 15 + 6 × 18) / 8
        Insumo origem = estoque.buscar(sassami.getId());
        assertThat(origem.getEstoqueAtual()).isEqualByComparingTo("0");
        assertThat(origem.isAtivo()).isFalse();
    }

    @Test
    @DisplayName("Painel de metas: média por dia de venda no período e o que já saiu hoje de cada produto")
    void painelDeVendas() {
        Insumo lata = estoque.criar(new InsumoRequest("Lata meta " + SEQ.incrementAndGet(), UnidadeMedida.UNIDADE,
                bd("0"), bd("3"), bd("50")), "t");
        Produto venda = produtos.criar(new ProdutoRequest("Lata meta venda " + SEQ.get(), null, CategoriaProduto.BEBIDA, bd("6"),
                List.of(new ItemFichaRequest(lata.getId(), bd("1")))));
        LocalDateTime agora = LocalDateTime.now(SP).withSecond(0).withNano(0);
        pedidos.lancar(retroativo(venda, FormaPagamento.PIX, null, agora.minusDays(2)), "dono");
        pedidos.lancar(retroativo(venda, FormaPagamento.PIX, null, agora.minusDays(2)), "dono");
        pedidos.lancar(retroativo(venda, FormaPagamento.PIX, null, agora.minusDays(5)), "dono");
        pedidos.lancar(retroativo(venda, FormaPagamento.PIX, null, agora.minusMinutes(1)), "dono");

        PainelVendasService.PainelVendas r = painelVendas.painel(30);

        ProdutoMeta meta = r.produtos().stream().filter(p -> p.produtoId().equals(venda.getId())).findFirst().orElseThrow();
        assertThat(meta.quantidadePeriodo()).isEqualTo(3);
        assertThat(meta.faturamentoPeriodo()).isEqualByComparingTo("18.00");
        assertThat(meta.hoje()).isEqualTo(1);
        assertThat(r.diasComVenda()).isGreaterThanOrEqualTo(2);
        assertThat(r.porHora()).hasSize(24);
        assertThat(r.hojeTotais().faturamento()).isGreaterThanOrEqualTo(bd("6"));
    }

    @Test
    @DisplayName("Consumo da equipe lançado depois baixa mesmo sem saldo; no dia, sem saldo é recusado")
    void consumoJaFeito() {
        Insumo refri = estoque.criar(new InsumoRequest("Refri equipe " + SEQ.incrementAndGet(), UnidadeMedida.UNIDADE,
                bd("0"), bd("5"), bd("0")), "t");

        assertThatThrownBy(() -> consumo.registrar("Janta", Map.of(), Map.of(refri.getId(), bd("1")), "dono"))
                .isInstanceOf(RegraDeNegocioException.class);
        consumo.registrar("Janta de ontem", Map.of(), Map.of(refri.getId(), bd("1")), "dono", true);

        assertThat(estoque.buscar(refri.getId()).getEstoqueAtual()).isEqualByComparingTo("-1");
    }

    @Test
    @DisplayName("Entrega de ontem com motoboy já pago por fora não fica pendente no acerto; no dia isso é recusado")
    void motoboyJaPago() {
        Insumo lata = estoque.criar(new InsumoRequest("Lata entrega " + SEQ.incrementAndGet(), UnidadeMedida.UNIDADE,
                bd("0"), bd("3"), bd("10")), "t");
        Produto venda = produtos.criar(new ProdutoRequest("Lata entrega venda " + SEQ.get(), null, CategoriaProduto.BEBIDA, bd("6"),
                List.of(new ItemFichaRequest(lata.getId(), bd("1")))));
        var bairro = cadastros.criarBairro("Bairro retro " + SEQ.get(), bd("4"));
        Colaborador motoboy = cadastros.criarColaborador("Motoboy retro " + SEQ.get(), Colaborador.Funcao.MOTOBOY, bd("50"));
        LocalDateTime ontem = LocalDateTime.now(SP).minusDays(1).withHour(19).withMinute(0).withSecond(0).withNano(0);

        pedidos.lancar(new NovoPedidoRequest(CanalVenda.WHATSAPP, FormaPagamento.CREDITO, "Bianca", null, "Rua Touro, 35", null,
                List.of(new ItemRequest(venda.getId(), 1, null)), null,
                new EntregaRequest(bairro.getId(), null, motoboy.getId(), false, false, true), true, null, false, null, ontem), "dono");

        assertThat(caixa.pendenciasMotoboys()).filteredOn(x -> x.motoboy().getId().equals(motoboy.getId()))
                .allSatisfy(x -> assertThat(x.entregas()).isEmpty());
        assertThatThrownBy(() -> pedidos.lancar(new NovoPedidoRequest(CanalVenda.WHATSAPP, FormaPagamento.CREDITO, "Bianca",
                "11999998888", "Rua Touro, 35", null, List.of(new ItemRequest(venda.getId(), 1)), null,
                new EntregaRequest(bairro.getId(), null, motoboy.getId(), false, false, true), true, null, false, null), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("Motoboy já pago");
    }

    @Test
    @DisplayName("Caixa marcada só para viagem: sai na retirada, não sai quando o cliente come na mesa")
    void caixaSoParaViagem() {
        Insumo frango = estoque.criar(new InsumoRequest("Frango caixa " + SEQ.incrementAndGet(), UnidadeMedida.QUILOGRAMA,
                bd("0"), bd("20"), bd("5")), "t");
        Insumo caixa = estoque.criar(new InsumoRequest("Caixa viagem " + SEQ.get(), UnidadeMedida.UNIDADE,
                bd("0"), bd("1"), bd("10")), "t");
        Produto combo = produtos.criar(new ProdutoRequest("Combo caixa " + SEQ.get(), null, CategoriaProduto.COMBO, bd("55"),
                List.of(new ItemFichaRequest(frango.getId(), ModoQuantidade.UNIDADE_BASE, bd("0.5"), null, false),
                        new ItemFichaRequest(caixa.getId(), ModoQuantidade.UNIDADE_BASE, bd("1"), null, true))));
        LocalDateTime ontem = LocalDateTime.now(SP).minusDays(1).withHour(20).withMinute(0).withSecond(0).withNano(0);

        pedidos.lancar(new NovoPedidoRequest(CanalVenda.MESA, FormaPagamento.PIX, "Mesa 2", null, null, null,
                List.of(new ItemRequest(combo.getId(), 1, null)), null, null, true, null, false, null, ontem), "dono");
        assertThat(estoque.buscar(caixa.getId()).getEstoqueAtual()).isEqualByComparingTo("10");
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo("4.5");

        pedidos.lancar(retroativo(combo, FormaPagamento.PIX, null, ontem), "dono");
        assertThat(estoque.buscar(caixa.getId()).getEstoqueAtual()).isEqualByComparingTo("9");
    }

    private static NovoPedidoRequest retroativo(Produto produto, FormaPagamento forma, BigDecimal preco, LocalDateTime quando) {
        return new NovoPedidoRequest(CanalVenda.BALCAO, forma, null, null, null, "Comanda de papel",
                List.of(new ItemRequest(produto.getId(), 1, preco)), null, null, false, null, false, null, quando);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
