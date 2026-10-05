package com.pointdofrango.saida;

import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.consumo.ConsumoInternoService;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.entrega.Colaborador.Funcao;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueDtos.PagamentoCompra;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.FinanceiroDtos.DespesaRequest;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.saida.RelatorioSaidasService.Relatorio;
import com.pointdofrango.saida.SaidaService.NovaSaida;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Saídas do mês (caixa + por fora), fornecedores, contas fixas, consumo da equipe e grupos do estoque. */
@SpringBootTest
@ActiveProfiles("test")
class SaidasDoMesIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final YearMonth MES = YearMonth.now(ZoneId.of("America/Sao_Paulo"));

    @Autowired SaidaService saidas;
    @Autowired RelatorioSaidasService relatorio;
    @Autowired CaixaService caixa;
    @Autowired EntregaCadastroService cadastros;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;
    @Autowired ConsumoInternoService consumo;
    @Autowired ConfiguracaoFinanceiraService financeiro;

    private int n;
    private Insumo coca;
    private Colaborador ajudante;
    private Fornecedor atacadao;

    @BeforeEach
    void preparar() {
        n = SEQ.incrementAndGet();
        coca = estoque.criar(new InsumoRequest("Coca 2 L S" + n, UnidadeMedida.UNIDADE, bd("2"), bd("9.50"), bd("10"),
                null, null, null, "Refrigerantes", "Coca-Cola"), "teste");
        ajudante = cadastros.criarColaborador("Ajudante S" + n, Funcao.FUNCIONARIO, bd("80"));
        atacadao = saidas.salvarFornecedor(null, "Atacadão S" + n, "bebidas", null, null, null, true);
        // cada teste começa com um caixa recém-fechado: o próximo só soma o que vier depois
        if (caixa.atual().isEmpty()) {
            caixa.abrir(BigDecimal.ZERO, "teste");
        }
        caixa.fechar(BigDecimal.ZERO, "limpeza do teste", "teste");
    }

    @Test
    @DisplayName("Mês junta caixa e pagamentos por fora, por categoria, pessoa e fornecedor")
    void relatorioDoMes() {
        Relatorio antes = relatorio.doMes(MES);
        caixa.abrir(bd("100"), "caixa");
        caixa.pagarDiaria(ajudante.getId(), null, FormaPagamento.DINHEIRO, "caixa");
        saidas.lancar(new NovaSaida(null, CategoriaSaida.PRO_LABORE, "Meu salário", bd("1500"), FormaPagamento.PIX,
                null, null, null), "dono");
        saidas.lancar(new NovaSaida(null, CategoriaSaida.IMPOSTO, "DAS MEI", bd("81.90"), FormaPagamento.PIX,
                null, null, null), "dono");
        // compra por fora (PIX) e compra com dinheiro da gaveta, as duas no Atacadão
        estoque.registrarEntrada(coca.getId(), entrada(bd("12"), bd("114"), new PagamentoCompra(FormaPagamento.PIX, false,
                atacadao.getId())), "dono");
        estoque.registrarEntrada(coca.getId(), entrada(bd("6"), bd("57"), new PagamentoCompra(FormaPagamento.DINHEIRO, true,
                atacadao.getId())), "dono");

        Relatorio depois = relatorio.doMes(MES);

        assertThat(depois.totalSaidas().subtract(antes.totalSaidas())).isEqualByComparingTo("1832.90");
        assertThat(depois.saidasDoCaixa().subtract(antes.saidasDoCaixa())).isEqualByComparingTo("137.00");
        assertThat(valorCategoria(depois, CategoriaSaida.INSUMOS).subtract(valorCategoria(antes, CategoriaSaida.INSUMOS)))
                .isEqualByComparingTo("171.00");
        assertThat(depois.porPessoa()).anySatisfy(p -> {
            assertThat(p.colaboradorId()).isEqualTo(ajudante.getId());
            assertThat(p.valor()).isEqualByComparingTo("80.00");
        });
        assertThat(depois.porFornecedor()).anySatisfy(f -> {
            assertThat(f.fornecedorId()).isEqualTo(atacadao.getId());
            assertThat(f.compras()).isEqualTo(2);
            assertThat(f.valor()).isEqualByComparingTo("171.00");
        });
        assertThat(depois.historico()).hasSize(6).last().satisfies(m -> assertThat(m.mes()).isEqualTo(MES));
        assertThat(depois.regime().limiteAnual()).isPositive();
        assertThat(estoque.buscar(coca.getId()).getEstoqueAtual()).isEqualByComparingTo("28");
    }

    @Test
    @DisplayName("Compra paga com dinheiro da gaveta e caixa fechado: nada entra no estoque")
    void compraSemCaixaAberto() {
        assertThatThrownBy(() -> estoque.registrarEntrada(coca.getId(), entrada(bd("6"), bd("57"),
                new PagamentoCompra(FormaPagamento.DINHEIRO, true, null)), "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("caixa está fechado");
        assertThat(estoque.buscar(coca.getId()).getEstoqueAtual()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Conta fixa: pagar marca como PAGA no mês")
    void contaFixa() {
        var aluguel = financeiro.criarDespesa(new DespesaRequest("Aluguel S" + n, bd("1500"), 5, true));
        saidas.pagarContaFixa(aluguel.getId(), null, FormaPagamento.PIX, null, "dono");

        assertThat(relatorio.doMes(MES).contasFixas()).anySatisfy(c -> {
            assertThat(c.despesaFixaId()).isEqualTo(aluguel.getId());
            assertThat(c.situacao()).isEqualTo("PAGA");
            assertThat(c.pago()).isEqualByComparingTo("1500.00");
        });
    }

    @Test
    @DisplayName("Consumo da equipe: baixa pelo produto (ficha) e pelo insumo, sem pedido; custo aparece no mês")
    void consumoDaEquipe() {
        Insumo batata = estoque.criar(new InsumoRequest("Batata S" + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("10"),
                bd("5")), "teste");
        Produto porcao = produtos.criar(new ProdutoRequest("Batata porção S" + n, null, CategoriaProduto.PORCAO, bd("22"),
                List.of(new ItemFichaRequest(batata.getId(), bd("0.3")))));
        BigDecimal custoAntes = relatorio.doMes(MES).consumoInterno();

        var baixas = consumo.registrar("Janta da equipe", Map.of(porcao.getId(), 2), Map.of(coca.getId(), bd("1")), "caixa");

        assertThat(baixas).hasSize(2);
        assertThat(estoque.buscar(batata.getId()).getEstoqueAtual()).isEqualByComparingTo("4.4");
        assertThat(estoque.buscar(coca.getId()).getEstoqueAtual()).isEqualByComparingTo("9");
        // 0,6 kg × R$ 10 + 1 × R$ 9,50
        assertThat(relatorio.doMes(MES).consumoInterno().subtract(custoAntes)).isEqualByComparingTo("15.50");
        assertThatThrownBy(() -> consumo.registrar("x", Map.of(), Map.of(coca.getId(), bd("100")), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Estoque guarda grupo e marca")
    void grupoEMarca() {
        Insumo lido = estoque.buscar(coca.getId());
        assertThat(lido.getGrupo()).isEqualTo("Refrigerantes");
        assertThat(lido.getMarca()).isEqualTo("Coca-Cola");
    }

    private static BigDecimal valorCategoria(Relatorio r, CategoriaSaida c) {
        return r.porCategoria().stream().filter(x -> x.categoria() == c).map(x -> x.valor()).findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private static EntradaRequest entrada(BigDecimal qtd, BigDecimal valor, PagamentoCompra pagamento) {
        return new EntradaRequest(qtd, null, null, valor, null, pagamento);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
