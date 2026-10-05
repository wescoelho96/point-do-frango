package com.pointdofrango.saida;

import com.pointdofrango.caixa.MovimentoCaixa;
import com.pointdofrango.caixa.MovimentoCaixaRepository;
import com.pointdofrango.consumo.ConsumoInternoService;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.MovimentacaoEstoque;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.ConfiguracaoFinanceira;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.DespesaFixa;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.financeiro.RegimeTributario;
import com.pointdofrango.pedido.ComandaRepository;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoRepository;
import com.pointdofrango.shared.Dinheiro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Saídas do mês (gaveta e pagamentos por fora) comparadas com as entradas. */
@Service
public class RelatorioSaidasService {

    private static final BigDecimal CEM = new BigDecimal("100");

    private final SaidaRepository saidas;
    private final MovimentoCaixaRepository caixa;
    private final FornecedorRepository fornecedores;
    private final PedidoRepository pedidos;
    private final ComandaRepository comandas;
    private final EstoqueService estoque;
    private final ConfiguracaoFinanceiraService financeiro;
    private final Clock clock;
    private final ZoneId zona;

    public RelatorioSaidasService(SaidaRepository saidas, MovimentoCaixaRepository caixa, FornecedorRepository fornecedores,
                                  PedidoRepository pedidos, ComandaRepository comandas, EstoqueService estoque,
                                  ConfiguracaoFinanceiraService financeiro, Clock clock, ZoneId zona) {
        this.saidas = saidas;
        this.caixa = caixa;
        this.fornecedores = fornecedores;
        this.pedidos = pedidos;
        this.comandas = comandas;
        this.estoque = estoque;
        this.financeiro = financeiro;
        this.clock = clock;
        this.zona = zona;
    }

    /** origem: "CAIXA" (gaveta) ou "CONTA" (pago por fora). */
    public record Lancamento(String origem, Long id, Instant quando, LocalDate data, CategoriaSaida categoria,
                             String descricao, Long colaboradorId, String pessoa, Colaborador.Funcao funcao,
                             Long fornecedorId, String fornecedor, FormaPagamento forma, BigDecimal valor,
                             String criadoPor) {
    }

    public record PorCategoria(CategoriaSaida categoria, String rotulo, int quantidade, BigDecimal valor,
                               BigDecimal percentual) {
    }

    /** dias: datas em que a pessoa recebeu no mês. */
    public record PorPessoa(Long colaboradorId, String nome, Colaborador.Funcao funcao, int pagamentos, BigDecimal valor,
                            List<LocalDate> dias) {
    }

    public record PorFornecedor(Long fornecedorId, String nome, int compras, BigDecimal valor) {
    }

    public record ContaDoMes(Long despesaFixaId, String descricao, BigDecimal valorMensal, Integer diaVencimento,
                             BigDecimal pago, String situacao, boolean valorVariavel, BigDecimal pagoMesAnterior) {
    }

    /** projecaoAno = média mensal até agora x 12; se passar do teto, o alerta sugere migrar de MEI para ME. */
    public record Regime(RegimeTributario regime, BigDecimal limiteAnual, BigDecimal faturamentoAno,
                         BigDecimal percentualUsado, BigDecimal mediaMensal, BigDecimal projecaoAno, String alerta) {
    }

    public record Mes(YearMonth mes, BigDecimal entradas, BigDecimal saidas, BigDecimal resultado) {
    }

    /**
     * Taxa de entrega fica com a loja quando o dono entrega e com o motoboy quando ele entrega.
     * Entrega grátis feita por motoboy é custo da loja.
     */
    public record Entregas(int total, int peloDono, BigDecimal taxasFicaramComALoja, int porMotoboy,
                           BigDecimal taxasDosMotoboys, int gratis, BigDecimal custoEntregaGratis) {
    }

    public record Relatorio(YearMonth mes, BigDecimal vendas, BigDecimal taxasEntrega, BigDecimal taxaServico,
                            BigDecimal totalEntradas, BigDecimal totalSaidas, BigDecimal resultado,
                            BigDecimal saidasDoCaixa, BigDecimal saidasPorFora, BigDecimal consumoInterno,
                            List<PorCategoria> porCategoria, List<PorPessoa> porPessoa,
                            List<PorFornecedor> porFornecedor, List<ContaDoMes> contasFixas, Regime regime,
                            List<Mes> historico, Entregas entregas, List<Lancamento> lancamentos) {
    }

    @Transactional(readOnly = true)
    public Relatorio doMes(YearMonth mes) {
        List<Lancamento> lancamentos = lancamentos(mes);
        BigDecimal total = somar(lancamentos);
        BigDecimal doCaixa = somar(lancamentos.stream().filter(l -> l.origem().equals("CAIXA")).toList());

        Instant inicio = inicio(mes);
        Instant fim = inicio(mes.plusMonths(1));
        BigDecimal vendas = pedidos.faturamentoEntre(inicio, fim);
        BigDecimal entregas = pedidos.taxasEntregaEntre(inicio, fim);
        BigDecimal servico = comandas.servicoNoPeriodo(inicio, fim);
        BigDecimal entradas = vendas.add(entregas).add(servico);

        BigDecimal consumoInterno = Dinheiro.centavos(estoque.consumoInternoEntre(inicio, fim).stream()
                .map(ConsumoInternoService::custo).reduce(BigDecimal.ZERO, BigDecimal::add));

        return new Relatorio(mes, vendas, entregas, servico, entradas, total, entradas.subtract(total), doCaixa,
                total.subtract(doCaixa), consumoInterno, porCategoria(lancamentos, total), porPessoa(lancamentos),
                porFornecedor(lancamentos), contasFixas(mes), regime(mes), historico(mes), entregas(inicio, fim), lancamentos);
    }

    @Transactional(readOnly = true)
    public List<Lancamento> lancamentos(YearMonth mes) {
        return lancamentosEntre(mes.atDay(1), mes.atEndOfMonth());
    }

    /** Intervalo inclusivo. Também usado pelo painel de vendas. */
    @Transactional(readOnly = true)
    public List<Lancamento> lancamentosEntre(LocalDate inicio, LocalDate fim) {
        Map<Long, String> nomesFornecedor = fornecedores.findAll().stream()
                .collect(Collectors.toMap(Fornecedor::getId, Fornecedor::getNome));
        List<Lancamento> lista = new ArrayList<>();
        Instant de = inicio.atStartOfDay(zona).toInstant();
        Instant ate = fim.plusDays(1).atStartOfDay(zona).toInstant();
        for (MovimentoCaixa m : caixa.gastosEntre(de, ate)) {
            Colaborador c = m.getColaborador();
            lista.add(new Lancamento("CAIXA", m.getId(), m.getCriadoEm(), LocalDate.ofInstant(m.getCriadoEm(), zona),
                    m.getCategoria(), m.getDescricao(), c != null ? c.getId() : null, c != null ? c.getNome() : null,
                    c != null ? c.getFuncao() : null, m.getFornecedorId(), nomesFornecedor.get(m.getFornecedorId()), m.getForma(), m.getValor(),
                    m.getCriadoPor()));
        }
        for (Saida s : saidas.findByDataBetweenOrderByDataDescIdDesc(inicio, fim)) {
            Colaborador c = s.getColaborador();
            Fornecedor f = s.getFornecedor();
            lista.add(new Lancamento("CONTA", s.getId(), s.getCriadoEm(), s.getData(), s.getCategoria(), s.getDescricao(),
                    c != null ? c.getId() : null, c != null ? c.getNome() : null, c != null ? c.getFuncao() : null,
                    f != null ? f.getId() : null,
                    f != null ? f.getNome() : null, s.getForma(), s.getValor(), s.getCriadoPor()));
        }
        lista.sort(Comparator.comparing(Lancamento::data).thenComparing(Lancamento::quando).reversed());
        return lista;
    }

    private static List<PorCategoria> porCategoria(List<Lancamento> lancamentos, BigDecimal total) {
        Map<CategoriaSaida, List<Lancamento>> grupos = lancamentos.stream()
                .collect(Collectors.groupingBy(Lancamento::categoria, () -> new EnumMap<>(CategoriaSaida.class), Collectors.toList()));
        return grupos.entrySet().stream().map(e -> {
            BigDecimal valor = somar(e.getValue());
            return new PorCategoria(e.getKey(), e.getKey().getRotulo(), e.getValue().size(), valor, percentual(valor, total));
        }).sorted(Comparator.comparing(PorCategoria::valor).reversed()).toList();
    }

    private static List<PorPessoa> porPessoa(List<Lancamento> lancamentos) {
        Map<Long, List<Lancamento>> grupos = lancamentos.stream().filter(l -> l.colaboradorId() != null)
                .collect(Collectors.groupingBy(Lancamento::colaboradorId));
        return grupos.values().stream().map(lista -> {
            Lancamento l = lista.get(0);
            List<LocalDate> dias = lista.stream().map(Lancamento::data).distinct().sorted().toList();
            return new PorPessoa(l.colaboradorId(), l.pessoa(), l.funcao(), lista.size(), somar(lista), dias);
        }).sorted(Comparator.comparing(PorPessoa::valor).reversed()).toList();
    }

    private static List<PorFornecedor> porFornecedor(List<Lancamento> lancamentos) {
        Map<Long, List<Lancamento>> grupos = lancamentos.stream().filter(l -> l.fornecedorId() != null)
                .collect(Collectors.groupingBy(Lancamento::fornecedorId));
        return grupos.values().stream()
                .map(lista -> new PorFornecedor(lista.get(0).fornecedorId(), lista.get(0).fornecedor(), lista.size(), somar(lista)))
                .sorted(Comparator.comparing(PorFornecedor::valor).reversed()).toList();
    }

    private Map<Long, BigDecimal> pagoPorConta(YearMonth mes) {
        Map<Long, BigDecimal> pago = new LinkedHashMap<>();
        for (Saida s : saidas.findByDataBetweenOrderByDataDescIdDesc(mes.atDay(1), mes.atEndOfMonth())) {
            if (s.getDespesaFixaId() != null) {
                pago.merge(s.getDespesaFixaId(), s.getValor(), BigDecimal::add);
            }
        }
        return pago;
    }

    private List<ContaDoMes> contasFixas(YearMonth mes) {
        Map<Long, BigDecimal> pagoPorConta = pagoPorConta(mes);
        Map<Long, BigDecimal> mesAnterior = pagoPorConta(mes.minusMonths(1));
        LocalDate hoje = LocalDate.now(clock.withZone(zona));
        return financeiro.listarDespesas().stream().filter(DespesaFixa::isAtiva).map(d -> {
            BigDecimal pago = pagoPorConta.getOrDefault(d.getId(), BigDecimal.ZERO);
            String situacao;
            if (pago.compareTo(d.getValorMensal()) >= 0) {
                situacao = "PAGA";
            } else if (pago.signum() > 0) {
                situacao = "PARCIAL";
            } else if (mes.isBefore(YearMonth.from(hoje)) || (YearMonth.from(hoje).equals(mes)
                    && d.getDiaVencimento() != null && hoje.getDayOfMonth() > d.getDiaVencimento())) {
                situacao = "ATRASADA";
            } else {
                situacao = "PENDENTE";
            }
            return new ContaDoMes(d.getId(), d.getDescricao(), d.getValorMensal(), d.getDiaVencimento(), pago, situacao,
                    d.isValorVariavel(), mesAnterior.get(d.getId()));
        }).toList();
    }

    private Regime regime(YearMonth mes) {
        ConfiguracaoFinanceira cfg = financeiro.obter();
        YearMonth agora = YearMonth.now(clock.withZone(zona));
        YearMonth ate = mes.isAfter(agora) ? agora : mes;
        YearMonth janeiro = YearMonth.of(mes.getYear(), 1);
        BigDecimal ano = BigDecimal.ZERO;
        for (YearMonth m = janeiro; !m.isAfter(ate); m = m.plusMonths(1)) {
            ano = ano.add(faturamentoDoMes(m));
        }
        int meses = Math.max(1, ate.getMonthValue());
        BigDecimal media = ano.divide(BigDecimal.valueOf(meses), 2, RoundingMode.HALF_UP);
        BigDecimal projecao = media.multiply(BigDecimal.valueOf(12));
        BigDecimal limite = cfg.getLimiteFaturamentoAnual();
        BigDecimal usado = percentual(ano, limite);
        String alerta;
        if (ano.compareTo(limite) > 0) {
            alerta = "O faturamento do ano já passou do limite. Procure o contador: é hora de migrar de regime.";
        } else if (projecao.compareTo(limite) > 0) {
            alerta = "No ritmo atual, o ano fecha acima do limite. Vale conversar com o contador sobre virar ME.";
        } else if (usado.compareTo(new BigDecimal("80")) >= 0) {
            alerta = "Já foram usados mais de 80% do limite do ano.";
        } else {
            alerta = null;
        }
        return new Regime(cfg.getRegimeTributario(), limite, ano, usado, media, projecao, alerta);
    }

    private Entregas entregas(Instant inicio, Instant fim) {
        int total = 0;
        int dono = 0;
        int motoboy = 0;
        int gratis = 0;
        BigDecimal daLoja = BigDecimal.ZERO;
        BigDecimal dosMotoboys = BigDecimal.ZERO;
        BigDecimal custoGratis = BigDecimal.ZERO;
        for (Pedido p : pedidos.doPeriodo(inicio, fim)) {
            if (!p.entrega() || p.cancelado()) {
                continue;
            }
            total++;
            if (p.isEntregaPeloDono()) {
                dono++;
                daLoja = daLoja.add(p.taxaCobrada());
            } else if (p.getEntregador() != null) {
                motoboy++;
                dosMotoboys = dosMotoboys.add(p.getTaxaEntrega());
                if (p.entregaGratis()) {
                    gratis++;
                    custoGratis = custoGratis.add(p.getTaxaEntrega());
                }
            }
        }
        return new Entregas(total, dono, daLoja, motoboy, dosMotoboys, gratis, custoGratis);
    }

    private List<Mes> historico(YearMonth ate) {
        List<Mes> meses = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth m = ate.minusMonths(i);
            BigDecimal entradas = faturamentoDoMes(m);
            BigDecimal saidasMes = somar(lancamentos(m));
            meses.add(new Mes(m, entradas, saidasMes, entradas.subtract(saidasMes)));
        }
        return meses;
    }

    /** Receita do mês: vendas + taxas de entrega + taxa de serviço das comandas. */
    private BigDecimal faturamentoDoMes(YearMonth mes) {
        Instant inicio = inicio(mes);
        Instant fim = inicio(mes.plusMonths(1));
        return pedidos.faturamentoEntre(inicio, fim).add(pedidos.taxasEntregaEntre(inicio, fim))
                .add(comandas.servicoNoPeriodo(inicio, fim));
    }

    private Instant inicio(YearMonth mes) {
        return mes.atDay(1).atStartOfDay(zona).toInstant();
    }

    private static BigDecimal somar(List<Lancamento> lista) {
        return lista.stream().map(Lancamento::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal percentual(BigDecimal parte, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO : parte.multiply(CEM).divide(total, 1, RoundingMode.HALF_UP);
    }
}
