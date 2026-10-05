package com.pointdofrango.contador;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.entrega.Bairro;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.entrega.Colaborador.Funcao;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.FinanceiroDtos.DespesaRequest;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.CanalVenda;
import com.pointdofrango.pedido.ItemPedido;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoDtos.EntregaRequest;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoService;
import com.pointdofrango.pedido.StatusPedido;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.produto.PromocaoService;
import com.pointdofrango.saida.RelatorioSaidasService;
import com.pointdofrango.saida.SaidaService;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Entrega grátis e entrega pelo dono, promoção com brinde, conta de luz pelo código de barras e relatório do contador. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AjustesDeOutubroIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Autowired PedidoService pedidos;
    @Autowired CaixaService caixa;
    @Autowired EntregaCadastroService cadastros;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired PromocaoService promocoes;
    @Autowired SaidaService saidas;
    @Autowired RelatorioSaidasService relatorioSaidas;
    @Autowired RelatorioContadorService contador;
    @Autowired ConfiguracaoFinanceiraService financeiro;
    @Autowired MockMvc mvc;

    private int n;
    private Insumo frango;
    private Insumo coca;
    private Produto combo;
    private Produto refri;
    private Bairro bairro;
    private Colaborador motoboy;

    @BeforeEach
    void preparar() {
        n = SEQ.incrementAndGet();
        frango = estoque.criar(new InsumoRequest("Frango O" + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("16.90"), bd("10")), "t");
        coca = estoque.criar(new InsumoRequest("Coca 2 L O" + n, UnidadeMedida.UNIDADE, bd("0"), bd("9.50"), bd("10")), "t");
        combo = produtos.criar(new ProdutoRequest("Combo O" + n, null, CategoriaProduto.COMBO, bd("75"),
                List.of(new ItemFichaRequest(frango.getId(), bd("1")))));
        refri = produtos.criar(new ProdutoRequest("Coca O" + n, null, CategoriaProduto.BEBIDA, bd("16"),
                List.of(new ItemFichaRequest(coca.getId(), bd("1")))));
        bairro = cadastros.criarBairro("Bairro O" + n, bd("4"));
        motoboy = cadastros.criarColaborador("Motoboy O" + n, Funcao.MOTOBOY, bd("50"));
        // cada teste começa com um caixa recém-fechado: o próximo só soma o que vier depois
        if (caixa.atual().isEmpty()) {
            caixa.abrir(BigDecimal.ZERO, "teste");
        }
        caixa.fechar(BigDecimal.ZERO, "limpeza do teste", "teste");
    }

    @Test
    @DisplayName("Entrega grátis: cliente não paga a taxa, mas o motoboy recebe (custo da loja)")
    void entregaGratis() {
        Pedido p = pedidos.lancar(entrega(true, motoboy.getId(), false, false), "caixa");

        assertThat(p.totalACobrar()).isEqualByComparingTo("75.00");
        assertThat(p.entregaGratis()).isTrue();
        var pendencia = caixa.pendenciasMotoboys().stream()
                .filter(x -> x.motoboy().getId().equals(motoboy.getId())).findFirst().orElseThrow();
        assertThat(pendencia.taxas()).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("Dono entregou: ninguém a pagar e a taxa cobrada fica com a loja")
    void donoEntregou() {
        Pedido p = pedidos.lancar(entrega(false, null, true, false), "caixa");

        assertThat(p.totalACobrar()).isEqualByComparingTo("79.00");
        assertThat(p.isEntregaPeloDono()).isTrue();
        assertThat(caixa.pendenciasMotoboys()).noneMatch(x -> x.entregas().stream().anyMatch(e -> e.getId().equals(p.getId())));
    }

    @Test
    @DisplayName("Promoção do dia: combo ganha refri com preço zero; o refri sai do estoque e o custo entra no CMV")
    void promocaoComBrinde() {
        DayOfWeek hoje = LocalDate.now(SP).getDayOfWeek();
        var promo = promocoes.salvar(null, "Combo ganha Coca O" + n, combo.getId(), 1, refri.getId(), 1, EnumSet.of(hoje), true);

        Pedido p = pedidos.lancar(entrega(false, motoboy.getId(), false, false), "caixa");
        ItemPedido brinde = p.getItens().stream().filter(ItemPedido::isBrinde).findFirst().orElseThrow();
        assertThat(brinde.getPrecoUnitario()).isEqualByComparingTo("0");
        assertThat(brinde.getPromocao()).isEqualTo(promo.getNome());
        assertThat(p.getValorTotal()).isEqualByComparingTo("75.00");
        assertThat(p.getDistribuicao().reposicaoEstoque()).isEqualByComparingTo("26.40"); // 16,90 + 9,50
        assertThat(estoque.buscar(coca.getId()).getEstoqueAtual()).isEqualByComparingTo("9");

        Pedido semPromo = pedidos.lancar(entrega(false, motoboy.getId(), false, true), "caixa");
        assertThat(semPromo.getItens()).noneMatch(ItemPedido::isBrinde);

        promocoes.salvar(promo.getId(), promo.getNome(), combo.getId(), 1, refri.getId(), 1, EnumSet.of(hoje.plus(1)), true);
        assertThat(pedidos.lancar(entrega(false, motoboy.getId(), false, false), "caixa").getItens()).noneMatch(ItemPedido::isBrinde);
    }

    @Test
    @DisplayName("Pedido só de bebida não vai para a cozinha; com comida, vai (com a bebida junto)")
    void bebidaNaoVaiParaCozinha() {
        Pedido soBebida = pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(refri.getId(), 2))), "caixa");
        Pedido comComida = pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, null, null, null, null,
                List.of(new ItemRequest(combo.getId(), 1), new ItemRequest(refri.getId(), 1))), "caixa");

        assertThat(soBebida.getStatus()).isEqualTo(StatusPedido.ENTREGUE);
        assertThat(comComida.getStatus()).isEqualTo(StatusPedido.EM_PREPARO);
        assertThat(pedidos.filaDaCozinha()).extracting(Pedido::getId)
                .contains(comComida.getId()).doesNotContain(soBebida.getId());
        assertThat(estoque.buscar(coca.getId()).getEstoqueAtual()).isLessThanOrEqualTo(bd("7"));
    }

    @Test
    @DisplayName("Cortesia: sai do estoque e vai para a cozinha, mas não tem receita; o custo sai do lucro e não entra no caixa")
    void cortesia() {
        BigDecimal frangoAntes = estoque.buscar(frango.getId()).getEstoqueAtual();
        Pedido p = pedidos.lancar(new NovoPedidoRequest(CanalVenda.WHATSAPP, FormaPagamento.CORTESIA, "Cliente fiel", "11944443333",
                "Rua Z, 9", null, List.of(new ItemRequest(combo.getId(), 1)), null,
                new EntregaRequest(bairro.getId(), null, motoboy.getId()), true, null, false, "Aniversário do cliente"), "dono");

        assertThat(p.getValorTotal()).isEqualByComparingTo("0");
        assertThat(p.totalACobrar()).isEqualByComparingTo("0");          // entrega também é grátis
        assertThat(p.getStatus()).isEqualTo(StatusPedido.EM_PREPARO);     // vai para a cozinha
        assertThat(p.getDistribuicao().reposicaoEstoque()).isEqualByComparingTo("16.90");
        assertThat(p.getDistribuicao().lucroLiquido()).isEqualByComparingTo("-16.90");
        assertThat(estoque.buscar(frango.getId()).getEstoqueAtual()).isEqualByComparingTo(frangoAntes.subtract(BigDecimal.ONE));
        assertThat(pedidos.cortesias(1)).extracting(Pedido::getId).contains(p.getId());
        assertThat(p.getMotivoCortesia()).isEqualTo("Aniversário do cliente");

        assertThatThrownBy(() -> pedidos.lancar(new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.CORTESIA, null, null,
                null, null, List.of(new ItemRequest(combo.getId(), 1))), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("motivo");
    }

    @Test
    @DisplayName("Conta de luz: valor sai do código de barras, vira a nova referência e o mês anterior aparece para comparar")
    void contaDeLuzPeloCodigo() {
        var luz = financeiro.criarDespesa(new DespesaRequest("Luz O" + n, bd("220"), 10, true, true));
        String semDv = "836" + "00000023500" + "0".repeat(29);
        int dv = (10 - somaModulo10(semDv) % 10) % 10; // conta de luz de R$ 235,00 com o dígito verificador certo
        String codigo = semDv.substring(0, 3) + dv + semDv.substring(3);
        LocalDate hoje = LocalDate.now(SP);
        saidas.pagarContaFixa(luz.getId(), bd("220"), FormaPagamento.PIX, hoje.minusMonths(1), "dono");

        var saida = saidas.pagarContaFixa(luz.getId(), null, FormaPagamento.PIX, hoje, codigo, "dono");

        assertThat(saida.getValor()).isEqualByComparingTo("235.00");
        assertThat(saida.getCodigoBarras()).isEqualTo(codigo);
        assertThat(financeiro.listarDespesas()).filteredOn(d -> d.getId().equals(luz.getId()))
                .singleElement().satisfies(d -> assertThat(d.getValorMensal()).isEqualByComparingTo("235.00"));
        assertThat(relatorioSaidas.doMes(YearMonth.from(hoje)).contasFixas()).filteredOn(c -> c.despesaFixaId().equals(luz.getId()))
                .singleElement().satisfies(c -> {
                    assertThat(c.situacao()).isEqualTo("PAGA");
                    assertThat(c.pagoMesAnterior()).isEqualByComparingTo("220.00");
                });
    }

    @Test
    @DisplayName("Contador: receita separa revenda (bebida), industrializado e serviço; planilha CSV só para o dono")
    void relatorioDoContador() throws Exception {
        YearMonth mes = YearMonth.now(SP);
        var antes = contador.resumo(mes, mes).total();
        pedidos.lancar(new NovoPedidoRequest(CanalVenda.WHATSAPP, FormaPagamento.PIX, "Ana", "11900000000", "Rua X", null,
                List.of(new ItemRequest(combo.getId(), 1), new ItemRequest(refri.getId(), 1)), null,
                new EntregaRequest(bairro.getId(), null, motoboy.getId()), true, null, false, null), "caixa");

        var depois = contador.resumo(mes, mes).total();
        assertThat(depois.revendaMercadorias().subtract(antes.revendaMercadorias())).isEqualByComparingTo("16.00");
        assertThat(depois.produtosIndustrializados().subtract(antes.produtosIndustrializados())).isEqualByComparingTo("75.00");
        assertThat(depois.servicos().subtract(antes.servicos())).isEqualByComparingTo("4.00");

        String dono = token("dono", "senha-do-dono");
        mvc.perform(get("/api/v1/contador/receitas.csv?de=" + mes + "&ate=" + mes).header("Authorization", "Bearer " + dono))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("receitas")))
                .andExpect(content().string(startsWith("﻿Data;Pedidos;")));
        mvc.perform(get("/api/v1/contador/pedidos.csv?de=" + mes + "&ate=" + mes).header("Authorization", "Bearer " + dono))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("11900000000"))));
    }

    private String token(String usuario, String senha) throws Exception {
        String corpo = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"senha\":\"%s\"}".formatted(usuario, senha)))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(corpo).get("token").asText();
    }

    private NovoPedidoRequest entrega(boolean gratis, Long motoboyId, boolean peloDono, boolean semPromocao) {
        return new NovoPedidoRequest(CanalVenda.WHATSAPP, FormaPagamento.PIX, "Cliente " + n, "11988887777", "Rua A, 1", null,
                List.of(new ItemRequest(combo.getId(), 1)), null,
                new EntregaRequest(bairro.getId(), null, motoboyId, gratis, peloDono), semPromocao, null, false, null);
    }

    private static int somaModulo10(String numero) {
        int soma = 0;
        int peso = 2;
        for (int i = numero.length() - 1; i >= 0; i--) {
            int produto = (numero.charAt(i) - '0') * peso;
            soma += produto / 10 + produto % 10;
            peso = peso == 2 ? 1 : 2;
        }
        return soma;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
