package com.pointdofrango.painel;

import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.DistribuicaoFinanceira;
import com.pointdofrango.painel.PainelDtos.AlertaEstoque;
import com.pointdofrango.painel.PainelDtos.Agrupamento;
import com.pointdofrango.painel.PainelDtos.ContasDoMes;
import com.pointdofrango.painel.PainelDtos.Dia;
import com.pointdofrango.painel.PainelDtos.Cortesias;
import com.pointdofrango.painel.PainelDtos.EquipeEEntregas;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.saida.RelatorioSaidasService;
import com.pointdofrango.saida.RelatorioSaidasService.Lancamento;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.painel.PainelDtos.MaisVendido;
import com.pointdofrango.painel.PainelDtos.Resumo;
import com.pointdofrango.pedido.CanalVenda;
import com.pointdofrango.pedido.ComandaRepository;
import com.pointdofrango.pedido.ItemPedido;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoRepository;
import com.pointdofrango.pedido.StatusPedido;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agrega os pedidos do período em Java sobre uma única consulta. O volume é de centenas de pedidos
 * por mês, e assim o comportamento é o mesmo em H2 e PostgreSQL. Se crescer, vira GROUP BY no banco.
 */
@Service
public class PainelService {

    private static final BigDecimal CEM = new BigDecimal("100");

    private final PedidoRepository pedidos;
    private final ComandaRepository comandas;
    private final ConfiguracaoFinanceiraService financeiro;
    private final EstoqueService estoque;
    private final RelatorioSaidasService saidas;
    private final Clock clock;
    private final ZoneId zona;

    public PainelService(PedidoRepository pedidos, ComandaRepository comandas, ConfiguracaoFinanceiraService financeiro,
                         EstoqueService estoque, RelatorioSaidasService saidas, Clock clock, ZoneId zona) {
        this.saidas = saidas;
        this.pedidos = pedidos;
        this.comandas = comandas;
        this.financeiro = financeiro;
        this.estoque = estoque;
        this.clock = clock;
        this.zona = zona;
    }

    @Transactional(readOnly = true)
    public Resumo resumo(LocalDate inicio, LocalDate fim) {
        if (fim.isBefore(inicio)) {
            throw new RegraDeNegocioException("A data final não pode ser antes da inicial.");
        }
        if (ChronoUnit.DAYS.between(inicio, fim) > 366) {
            throw new RegraDeNegocioException("Período máximo: 1 ano.");
        }

        List<Pedido> doPeriodo = pedidos.doPeriodo(inicioDe(inicio), inicioDe(fim.plusDays(1)));
        List<Pedido> validos = doPeriodo.stream().filter(p -> p.getStatus() != StatusPedido.CANCELADO).toList();

        DistribuicaoFinanceira potes = somar(validos);
        BigDecimal bruto = potes.valorBruto();
        // cortesia fica fora do ticket médio
        List<Pedido> cortesias = validos.stream().filter(Pedido::cortesia).toList();
        int vendas = validos.size() - cortesias.size();
        BigDecimal ticket = vendas == 0 ? BigDecimal.ZERO
                : bruto.divide(BigDecimal.valueOf(vendas), 2, RoundingMode.HALF_UP);

        BigDecimal servico = comandas.servicoNoPeriodo(inicioDe(inicio), inicioDe(fim.plusDays(1)));

        EquipeEEntregas equipe = equipeEEntregas(inicio, fim);
        DistribuicaoFinanceira finais = descontarDoLucro(potes, equipe.descontadoDoLucro(),
                financeiro.obter().getPercentualProLabore());
        BigDecimal margem = bruto.signum() == 0 ? BigDecimal.ZERO
                : finais.lucroLiquido().multiply(CEM).divide(bruto, 1, RoundingMode.HALF_UP);

        return new Resumo(inicio, fim, validos.size(), doPeriodo.size() - validos.size(), bruto, ticket, potes, margem,
                servico,
                porCanal(validos),
                agrupar(validos, p -> p.getFormaPagamento() != null ? p.getFormaPagamento().name() : "A_RECEBER"),
                maisVendidos(validos),
                porDia(validos, inicio, fim),
                contasDoMes(YearMonth.from(fim)),
                alertasEstoque(), equipe, finais,
                new Cortesias(cortesias.size(), somar(cortesias).reposicaoEstoque()));
    }

    /**
     * Só saídas sem pote próprio (equipe, motoboy, outras). Insumos, contas fixas e pró-labore já têm pote
     * e seriam descontados duas vezes.
     */
    private EquipeEEntregas equipeEEntregas(LocalDate inicio, LocalDate fim) {
        List<Lancamento> gastos = saidas.lancamentosEntre(inicio, fim);
        BigDecimal equipe = somar(gastos, EnumSet.of(CategoriaSaida.FUNCIONARIO, CategoriaSaida.SALARIO));
        BigDecimal motoboys = somar(gastos, EnumSet.of(CategoriaSaida.MOTOBOY));
        BigDecimal outras = somar(gastos, EnumSet.of(CategoriaSaida.EQUIPAMENTO, CategoriaSaida.OUTRO));
        BigDecimal taxasEntrega = pedidos.taxasEntregaEntre(inicioDe(inicio), inicioDe(fim.plusDays(1)));
        return new EquipeEEntregas(equipe, motoboys, outras, taxasEntrega,
                equipe.add(motoboys).add(outras).subtract(taxasEntrega));
    }

    private static BigDecimal somar(List<Lancamento> gastos, Set<CategoriaSaida> categorias) {
        return gastos.stream().filter(l -> categorias.contains(l.categoria())).map(Lancamento::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Desconta os pagamentos do lucro antes de dividir; reserva por subtração para não perder centavos. */
    static DistribuicaoFinanceira descontarDoLucro(DistribuicaoFinanceira potes, BigDecimal desconto,
                                                   BigDecimal percentualProLabore) {
        BigDecimal lucro = potes.lucroLiquido().subtract(desconto);
        BigDecimal proLabore = BigDecimal.ZERO.setScale(2);
        BigDecimal reserva = BigDecimal.ZERO.setScale(2);
        if (lucro.signum() > 0) {
            proLabore = Dinheiro.percentual(lucro, percentualProLabore);
            reserva = lucro.subtract(proLabore);
        }
        return new DistribuicaoFinanceira(potes.taxaPagamento(), potes.reposicaoEstoque(), potes.contasFixas(), lucro,
                proLabore, reserva);
    }

    private ContasDoMes contasDoMes(YearMonth mes) {
        BigDecimal totalContas = financeiro.totalMensalDespesasAtivas();
        List<Pedido> doMes = validos(inicioDe(mes.atDay(1)), inicioDe(mes.plusMonths(1).atDay(1)));
        BigDecimal separado = somar(doMes).contasFixas();
        BigDecimal falta = totalContas.subtract(separado).max(BigDecimal.ZERO);
        BigDecimal coberto = totalContas.signum() == 0 ? CEM
                : separado.multiply(CEM).divide(totalContas, 1, RoundingMode.HALF_UP).min(CEM);

        Instant agora = Instant.now(clock);
        BigDecimal faturamento30d = somar(validos(agora.minus(30, ChronoUnit.DAYS), agora)).valorBruto();
        BigDecimal sugerido = faturamento30d.signum() == 0 ? null
                : totalContas.multiply(CEM).divide(faturamento30d, 1, RoundingMode.HALF_UP);

        return new ContasDoMes(mes, totalContas, separado, falta, coberto,
                financeiro.obter().getPercentualContasFixas(), sugerido);
    }

    private List<AlertaEstoque> alertasEstoque() {
        return estoque.listar().stream()
                .filter(i -> i.isAtivo() && i.abaixoDoMinimo())
                .map(i -> new AlertaEstoque(i.getId(), i.getNome(), i.getEstoqueAtual(), i.getEstoqueMinimo(),
                        i.getUnidade().getSigla()))
                .toList();
    }

    private static List<Agrupamento> agrupar(List<Pedido> pedidos, Function<Pedido, String> chave) {
        Map<String, List<Pedido>> grupos = pedidos.stream()
                .collect(Collectors.groupingBy(chave, LinkedHashMap::new, Collectors.toList()));
        return grupos.entrySet().stream()
                .map(e -> agrupamento(e.getKey(), e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Agrupamento::valor).reversed())
                .toList();
    }

    /** Os quatro canais sempre aparecem, mesmo zerados e em ordem fixa, para comparar períodos. */
    private static List<Agrupamento> porCanal(List<Pedido> pedidos) {
        Map<CanalVenda.Grupo, List<Pedido>> porGrupo = pedidos.stream()
                .collect(Collectors.groupingBy(p -> p.getCanal().getGrupo()));
        return java.util.Arrays.stream(CanalVenda.Grupo.values())
                .map(g -> agrupamento(g.name(), g.getRotulo(), porGrupo.getOrDefault(g, List.of())))
                .toList();
    }

    private static Agrupamento agrupamento(String chave, String rotulo, List<Pedido> pedidos) {
        DistribuicaoFinanceira soma = somar(pedidos);
        return new Agrupamento(chave, rotulo, pedidos.size(), soma.valorBruto(), soma.taxaPagamento());
    }

    private static List<MaisVendido> maisVendidos(List<Pedido> pedidos) {
        Map<Long, MaisVendido> porProduto = new LinkedHashMap<>();
        for (Pedido pedido : pedidos) {
            for (ItemPedido item : pedido.getItens()) {
                if (item.isBrinde()) {
                    continue;
                }
                porProduto.merge(item.getProdutoId(),
                        new MaisVendido(item.getProdutoId(), item.getNomeProduto(), item.getQuantidade(), item.subtotal()),
                        (a, b) -> new MaisVendido(a.produtoId(), a.produto(), a.quantidade() + b.quantidade(),
                                a.valor().add(b.valor())));
            }
        }
        return porProduto.values().stream()
                .sorted(Comparator.comparingInt(MaisVendido::quantidade).reversed())
                .limit(10)
                .toList();
    }

    private List<Dia> porDia(List<Pedido> pedidos, LocalDate inicio, LocalDate fim) {
        Map<LocalDate, List<Pedido>> porData = pedidos.stream()
                .collect(Collectors.groupingBy(p -> LocalDate.ofInstant(p.getCriadoEm(), zona), TreeMap::new,
                        Collectors.toList()));
        return inicio.datesUntil(fim.plusDays(1))
                .map(dia -> {
                    List<Pedido> doDia = porData.getOrDefault(dia, List.of());
                    DistribuicaoFinanceira soma = somar(doDia);
                    return new Dia(dia, doDia.size(), soma.valorBruto(), soma.lucroLiquido());
                })
                .toList();
    }

    private List<Pedido> validos(Instant inicio, Instant fim) {
        return pedidos.doPeriodo(inicio, fim).stream().filter(p -> p.getStatus() != StatusPedido.CANCELADO).toList();
    }

    private static DistribuicaoFinanceira somar(List<Pedido> pedidos) {
        return pedidos.stream().map(Pedido::getDistribuicao)
                .reduce(DistribuicaoFinanceira.ZERO, DistribuicaoFinanceira::somar);
    }

    private Instant inicioDe(LocalDate dia) {
        return dia.atStartOfDay(zona).toInstant();
    }
}
