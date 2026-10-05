package com.pointdofrango.contador;

import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.consumo.ConsumoInternoService;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.financeiro.RegimeTributario;
import com.pointdofrango.pedido.ComandaRepository;
import com.pointdofrango.pedido.ItemPedido;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoRepository;
import com.pointdofrango.pedido.StatusPedido;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.saida.RelatorioSaidasService;
import com.pointdofrango.saida.RelatorioSaidasService.Lancamento;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * RMRB mensal, resumo anual para a DASN-SIMEI e despesas por categoria.
 * No RMRB, bebida conta como revenda, comida da cozinha como industrializado e taxa de entrega como serviço,
 * seguindo a categoria do produto no cardápio. O enquadramento final é do contador.
 */
@Service
public class RelatorioContadorService {

    private static final int MAX_MESES = 24;

    private final PedidoRepository pedidos;
    private final ComandaRepository comandas;
    private final ProdutoService produtos;
    private final RelatorioSaidasService saidas;
    private final EstoqueService estoque;
    private final ConfiguracaoFinanceiraService financeiro;
    private final ZoneId zona;

    public RelatorioContadorService(PedidoRepository pedidos, ComandaRepository comandas, ProdutoService produtos,
                                    RelatorioSaidasService saidas, EstoqueService estoque,
                                    ConfiguracaoFinanceiraService financeiro, ZoneId zona) {
        this.pedidos = pedidos;
        this.comandas = comandas;
        this.produtos = produtos;
        this.saidas = saidas;
        this.estoque = estoque;
        this.financeiro = financeiro;
        this.zona = zona;
    }

    /** Linha do RMRB. O sistema não emite nota fiscal, então tudo entra como "sem nota". */
    public record Receita(String periodo, int pedidos, BigDecimal revendaMercadorias, BigDecimal produtosIndustrializados,
                          BigDecimal servicos, BigDecimal total, BigDecimal taxaServicoEquipe) {
    }

    public record PorForma(FormaPagamento forma, int pedidos, BigDecimal valor) {
    }

    public record Despesa(CategoriaSaida categoria, String rotulo, int lancamentos, BigDecimal valor) {
    }

    public record Dasn(int ano, BigDecimal receitaComercioIndustria, BigDecimal receitaServicos, BigDecimal receitaTotal,
                       boolean teveEmpregado) {
    }

    public record Resumo(YearMonth de, YearMonth ate, RegimeTributario regime, List<Receita> meses, Receita total,
                         List<PorForma> porFormaPagamento, BigDecimal taxasMaquininhaEApps,
                         List<Despesa> despesas, BigDecimal totalDespesas, BigDecimal consumoInterno,
                         BigDecimal resultado, Dasn dasn, List<String> observacoes) {
    }

    public record ReceitaDia(LocalDate dia, Receita receita, Map<FormaPagamento, BigDecimal> porForma, BigDecimal taxas) {
    }

    @Transactional(readOnly = true)
    public Resumo resumo(YearMonth de, YearMonth ate) {
        validar(de, ate);
        Map<Long, CategoriaProduto> categorias = categorias();
        List<Receita> meses = new ArrayList<>();
        Map<FormaPagamento, BigDecimal> formas = new EnumMap<>(FormaPagamento.class);
        Map<FormaPagamento, Integer> qtdFormas = new EnumMap<>(FormaPagamento.class);
        BigDecimal taxas = BigDecimal.ZERO;
        Map<CategoriaSaida, List<Lancamento>> despesasPorCategoria = new EnumMap<>(CategoriaSaida.class);
        BigDecimal consumo = BigDecimal.ZERO;

        for (YearMonth m = de; !m.isAfter(ate); m = m.plusMonths(1)) {
            List<Pedido> doMes = validos(inicio(m.atDay(1)), inicio(m.plusMonths(1).atDay(1)));
            meses.add(receita(m.toString(), doMes, categorias, comandas.servicoNoPeriodo(inicio(m.atDay(1)),
                    inicio(m.plusMonths(1).atDay(1)))));
            for (Pedido p : doMes) {
                if (p.getFormaPagamento() != null) {
                    formas.merge(p.getFormaPagamento(), p.totalACobrar(), BigDecimal::add);
                    qtdFormas.merge(p.getFormaPagamento(), 1, Integer::sum);
                }
                taxas = taxas.add(p.getDistribuicao().taxaPagamento());
            }
            saidas.lancamentos(m).forEach(l -> despesasPorCategoria.computeIfAbsent(l.categoria(), k -> new ArrayList<>()).add(l));
            consumo = consumo.add(estoque.consumoInternoEntre(inicio(m.atDay(1)), inicio(m.plusMonths(1).atDay(1))).stream()
                    .map(ConsumoInternoService::custo).reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        Receita total = somar("TOTAL", meses);
        List<Despesa> despesas = despesasPorCategoria.entrySet().stream()
                .map(e -> new Despesa(e.getKey(), e.getKey().getRotulo(), e.getValue().size(),
                        e.getValue().stream().map(Lancamento::valor).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .sorted(Comparator.comparing(Despesa::valor).reversed()).toList();
        BigDecimal totalDespesas = despesas.stream().map(Despesa::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean teveEmpregado = despesasPorCategoria.containsKey(CategoriaSaida.SALARIO);

        Dasn dasn = de.getMonthValue() == 1 && ate.getMonthValue() == 12 && de.getYear() == ate.getYear()
                ? new Dasn(de.getYear(), total.revendaMercadorias().add(total.produtosIndustrializados()),
                total.servicos(), total.total(), teveEmpregado)
                : null;

        List<String> observacoes = new ArrayList<>();
        observacoes.add("Valores sem nota fiscal: o sistema não emite NF. Se emitiu nota em alguma venda, informe ao contador.");
        observacoes.add("Revenda de mercadorias = produtos da categoria Bebidas; industrializados = o que a cozinha produz;"
                + " serviços = taxas de entrega cobradas. Confirme o enquadramento com o contador.");
        observacoes.add("A taxa de serviço (10%) das comandas é repassada à equipe e aparece separada da receita.");
        if (taxas.signum() > 0) {
            observacoes.add("Taxas de maquininha e de apps (iFood/99Food) já descontadas pelos bancos/apps: "
                    + "R$ " + Dinheiro.centavos(taxas).toPlainString().replace('.', ',') + ".");
        }

        BigDecimal consumoInterno = Dinheiro.centavos(consumo);
        return new Resumo(de, ate, financeiro.obter().getRegimeTributario(), meses, total,
                formas.entrySet().stream().map(e -> new PorForma(e.getKey(), qtdFormas.get(e.getKey()), e.getValue())).toList(),
                Dinheiro.centavos(taxas), despesas, totalDespesas, consumoInterno,
                total.total().subtract(totalDespesas), dasn, observacoes);
    }

    @Transactional(readOnly = true)
    public List<ReceitaDia> porDia(YearMonth de, YearMonth ate) {
        validar(de, ate);
        Map<Long, CategoriaProduto> categorias = categorias();
        Map<LocalDate, List<Pedido>> dias = validos(inicio(de.atDay(1)), inicio(ate.plusMonths(1).atDay(1))).stream()
                .collect(Collectors.groupingBy(p -> LocalDate.ofInstant(p.getCriadoEm(), zona), TreeMap::new, Collectors.toList()));
        return dias.entrySet().stream().map(e -> {
            Map<FormaPagamento, BigDecimal> formas = new EnumMap<>(FormaPagamento.class);
            BigDecimal taxas = BigDecimal.ZERO;
            for (Pedido p : e.getValue()) {
                if (p.getFormaPagamento() != null) {
                    formas.merge(p.getFormaPagamento(), p.totalACobrar(), BigDecimal::add);
                }
                taxas = taxas.add(p.getDistribuicao().taxaPagamento());
            }
            return new ReceitaDia(e.getKey(), receita(e.getKey().toString(), e.getValue(), categorias, BigDecimal.ZERO),
                    formas, taxas);
        }).toList();
    }

    /** Inclui cancelados, para conferência. */
    @Transactional(readOnly = true)
    public List<Pedido> pedidos(YearMonth de, YearMonth ate) {
        validar(de, ate);
        List<Pedido> lista = new ArrayList<>(pedidos.doPeriodo(inicio(de.atDay(1)), inicio(ate.plusMonths(1).atDay(1))));
        lista.sort(Comparator.comparing(Pedido::getCriadoEm));
        return lista;
    }

    @Transactional(readOnly = true)
    public List<Lancamento> saidas(YearMonth de, YearMonth ate) {
        validar(de, ate);
        List<Lancamento> lista = new ArrayList<>();
        for (YearMonth m = de; !m.isAfter(ate); m = m.plusMonths(1)) {
            lista.addAll(saidas.lancamentos(m));
        }
        lista.sort(Comparator.comparing(Lancamento::data).thenComparing(Lancamento::quando));
        return lista;
    }

    /**
     * Rateia cada pedido entre revenda e industrializado pela categoria dos itens, proporcional ao valor
     * recebido, porque no iFood as promoções fazem o total diferir do preço de cardápio.
     */
    private static Receita receita(String periodo, List<Pedido> lista, Map<Long, CategoriaProduto> categorias,
                                   BigDecimal taxaServico) {
        BigDecimal revenda = BigDecimal.ZERO;
        BigDecimal vendas = BigDecimal.ZERO;
        BigDecimal servicos = BigDecimal.ZERO;
        for (Pedido p : lista) {
            vendas = vendas.add(p.getValorTotal());
            if (p.entrega()) {
                servicos = servicos.add(p.taxaCobrada());
            }
            BigDecimal cardapio = p.valorCardapio();
            if (cardapio.signum() == 0) {
                continue;
            }
            BigDecimal fator = p.getValorTotal().divide(cardapio, 10, RoundingMode.HALF_UP);
            for (ItemPedido i : p.getItens()) {
                if (categorias.get(i.getProdutoId()) == CategoriaProduto.BEBIDA) {
                    revenda = revenda.add(i.subtotal().multiply(fator));
                }
            }
        }
        revenda = Dinheiro.centavos(revenda);
        BigDecimal industrializados = vendas.subtract(revenda);
        return new Receita(periodo, lista.size(), revenda, industrializados, servicos, vendas.add(servicos),
                Dinheiro.centavos(taxaServico));
    }

    private static Receita somar(String periodo, List<Receita> lista) {
        return new Receita(periodo, lista.stream().mapToInt(Receita::pedidos).sum(),
                lista.stream().map(Receita::revendaMercadorias).reduce(BigDecimal.ZERO, BigDecimal::add),
                lista.stream().map(Receita::produtosIndustrializados).reduce(BigDecimal.ZERO, BigDecimal::add),
                lista.stream().map(Receita::servicos).reduce(BigDecimal.ZERO, BigDecimal::add),
                lista.stream().map(Receita::total).reduce(BigDecimal.ZERO, BigDecimal::add),
                lista.stream().map(Receita::taxaServicoEquipe).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private Map<Long, CategoriaProduto> categorias() {
        return produtos.listar(false).stream().collect(Collectors.toMap(Produto::getId, Produto::getCategoria));
    }

    private List<Pedido> validos(Instant inicio, Instant fim) {
        return pedidos.doPeriodo(inicio, fim).stream().filter(p -> p.getStatus() != StatusPedido.CANCELADO).toList();
    }

    private Instant inicio(LocalDate dia) {
        return dia.atStartOfDay(zona).toInstant();
    }

    private static void validar(YearMonth de, YearMonth ate) {
        if (ate.isBefore(de)) {
            throw new RegraDeNegocioException("O mês final não pode ser antes do inicial.");
        }
        if (de.plusMonths(MAX_MESES).isBefore(ate)) {
            throw new RegraDeNegocioException("Período máximo: " + MAX_MESES + " meses.");
        }
    }
}
