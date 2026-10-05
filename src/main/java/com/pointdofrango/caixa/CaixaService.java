package com.pointdofrango.caixa;

import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.caixa.MovimentoCaixa.Tipo;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.Comanda;
import com.pointdofrango.pedido.ComandaRepository;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoRepository;
import com.pointdofrango.pedido.StatusComanda;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CaixaService {

    private static final Logger log = LoggerFactory.getLogger(CaixaService.class);

    private final CaixaRepository caixas;
    private final PedidoRepository pedidos;
    private final ComandaRepository comandas;
    private final EntregaCadastroService cadastros;
    private final Clock clock;
    private final ZoneId zona;

    public CaixaService(CaixaRepository caixas, PedidoRepository pedidos, ComandaRepository comandas,
                        EntregaCadastroService cadastros, Clock clock, ZoneId zona) {
        this.zona = zona;
        this.caixas = caixas;
        this.pedidos = pedidos;
        this.comandas = comandas;
        this.cadastros = cadastros;
        this.clock = clock;
    }

    public record Recebimento(FormaPagamento forma, int quantidade, BigDecimal valor) {
    }

    /** Venda direta ou comanda fechada. */
    public record Venda(Instant quando, String descricao, FormaPagamento forma, BigDecimal valor) {
    }

    /** Calculados a partir das vendas pagas no período do caixa; não ficam gravados. */
    public record Totais(List<Venda> vendas, List<Recebimento> recebimentos, BigDecimal totalRecebido, BigDecimal taxasEntrega,
                         BigDecimal vendasApps, BigDecimal suprimentos, BigDecimal saidasDinheiro,
                         BigDecimal saidasOutras, BigDecimal dinheiroEsperado) {
    }

    public record PendenciaMotoboy(Colaborador motoboy, List<Pedido> entregas, BigDecimal taxas, boolean diariaPaga) {

        public BigDecimal totalComDiaria() {
            return diariaPaga ? taxas : taxas.add(motoboy.getValorDiaria());
        }
    }

    @Transactional(readOnly = true)
    public Optional<Caixa> atual() {
        return caixas.findFirstByStatus(Caixa.Status.ABERTO);
    }

    @Transactional(readOnly = true)
    public Caixa buscar(Long id) {
        return caixas.findWithMovimentosById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Caixa", id));
    }

    @Transactional(readOnly = true)
    public List<Caixa> historico() {
        return caixas.findTop30ByStatusOrderByAbertoEmDesc(Caixa.Status.FECHADO);
    }

    @Transactional
    public Caixa abrir(BigDecimal valorAbertura, String usuario) {
        if (atual().isPresent()) {
            throw new RegraDeNegocioException("Já existe um caixa aberto. Feche o anterior antes de abrir outro.");
        }
        Caixa caixa = caixas.save(new Caixa(valorAbertura, usuario, agora(), vendasDesde()));
        log.info("Caixa #{} aberto por {} com {}", caixa.getId(), usuario, caixa.getValorAbertura());
        return caixa;
    }

    @Transactional
    public Caixa registrarSaida(CategoriaSaida categoria, String descricao, BigDecimal valor, FormaPagamento forma,
                                Long colaboradorId, Long fornecedorId, String usuario) {
        if (categoria == CategoriaSaida.SUPRIMENTO) {
            throw new RegraDeNegocioException("Use \"suprimento\" para colocar dinheiro na gaveta.");
        }
        Caixa caixa = aberto();
        Colaborador colaborador = colaboradorId == null ? null : cadastros.colaborador(colaboradorId);
        caixa.registrar(Tipo.SAIDA, categoria, descricao, valor, forma, colaborador, false, usuario, agora())
                .definirFornecedor(fornecedorId);
        return caixa;
    }

    @Transactional
    public Caixa registrarSuprimento(BigDecimal valor, String descricao, String usuario) {
        Caixa caixa = aberto();
        caixa.registrar(Tipo.SUPRIMENTO, CategoriaSaida.SUPRIMENTO, descricao == null || descricao.isBlank()
                ? "Suprimento de troco" : descricao, valor, FormaPagamento.DINHEIRO, null, false, usuario, agora());
        return caixa;
    }

    /** Sem valor informado, usa a diária do cadastro. */
    @Transactional
    public Caixa pagarDiaria(Long colaboradorId, BigDecimal valor, FormaPagamento forma, String usuario) {
        Caixa caixa = aberto();
        Colaborador c = cadastros.colaborador(colaboradorId);
        BigDecimal pago = valor != null ? valor : c.getValorDiaria();
        caixa.registrar(Tipo.SAIDA, c.motoboy() ? CategoriaSaida.MOTOBOY : CategoriaSaida.FUNCIONARIO, "Diária de " + c.getNome(),
                pago, forma, c, true, usuario, agora());
        return caixa;
    }

    @Transactional(readOnly = true)
    public List<PendenciaMotoboy> pendenciasMotoboys() {
        Optional<Caixa> caixa = atual();
        Map<Long, List<Pedido>> porMotoboy = new LinkedHashMap<>();
        Map<Long, Colaborador> motoboys = new LinkedHashMap<>();
        for (Pedido p : pedidos.entregasSemAcerto()) {
            porMotoboy.computeIfAbsent(p.getEntregador().getId(), k -> new ArrayList<>()).add(p);
            motoboys.putIfAbsent(p.getEntregador().getId(), p.getEntregador());
        }
        // motoboy ativo sem entrega pendente ainda pode receber a diária
        cadastros.listarColaboradores().stream().filter(c -> c.isAtivo() && c.motoboy())
                .forEach(c -> motoboys.putIfAbsent(c.getId(), c));
        return motoboys.values().stream().map(m -> {
            List<Pedido> entregas = porMotoboy.getOrDefault(m.getId(), List.of());
            BigDecimal taxas = entregas.stream().map(Pedido::getTaxaEntrega).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new PendenciaMotoboy(m, entregas, taxas, caixa.map(c -> diariaPaga(c, m)).orElse(false));
        }).toList();
    }

    /**
     * Paga ao motoboy as taxas das entregas pendentes, mais a diária se pedido, numa única saída do caixa.
     * As entregas ficam vinculadas a essa saída para poderem ser reabertas se ela for removida.
     */
    @Transactional
    public Caixa acertarMotoboy(Long motoboyId, boolean incluirDiaria, FormaPagamento forma, String usuario) {
        Caixa caixa = aberto();
        Colaborador motoboy = cadastros.colaborador(motoboyId);
        if (!motoboy.motoboy()) {
            throw new RegraDeNegocioException(motoboy.getNome() + " não está cadastrado como motoboy.");
        }
        if (incluirDiaria && diariaPaga(caixa, motoboy)) {
            throw new RegraDeNegocioException("A diária de " + motoboy.getNome() + " já foi paga neste caixa.");
        }
        List<Pedido> entregas = pedidos.entregasSemAcerto().stream()
                .filter(p -> p.getEntregador().getId().equals(motoboyId)).toList();
        BigDecimal taxas = entregas.stream().map(Pedido::getTaxaEntrega).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diaria = incluirDiaria ? motoboy.getValorDiaria() : BigDecimal.ZERO;
        if (taxas.add(diaria).signum() <= 0) {
            throw new RegraDeNegocioException(motoboy.getNome() + " não tem entregas pendentes para receber.");
        }
        String descricao = "%s: %d entrega(s)%s".formatted(motoboy.getNome(), entregas.size(), incluirDiaria ? " + diária" : "");
        MovimentoCaixa saida = caixa.registrar(Tipo.SAIDA, CategoriaSaida.MOTOBOY, descricao, taxas.add(diaria), forma,
                motoboy, incluirDiaria, usuario, agora());
        caixas.flush(); // precisa do id da saída; save/merge devolveria outra instância
        entregas.forEach(p -> p.registrarAcerto(saida.getId()));
        log.info("Motoboy #{} acertado por {}: {} entregas, total {}", motoboyId, usuario, entregas.size(), saida.getValor());
        return caixa;
    }

    @Transactional
    public Caixa removerMovimento(Long movimentoId, String usuario) {
        Caixa caixa = aberto();
        MovimentoCaixa m = caixa.getMovimentos().stream().filter(x -> x.getId().equals(movimentoId)).findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("Movimento do caixa", movimentoId));
        pedidos.findByAcertoEntregadorId(movimentoId).forEach(Pedido::desfazerAcerto);
        caixa.remover(m);
        log.info("Movimento #{} ({}, {}) removido do caixa #{} por {}", movimentoId, m.getDescricao(), m.getValor(),
                caixa.getId(), usuario);
        return caixa;
    }

    @Transactional
    public Caixa fechar(BigDecimal dinheiroContado, String observacao, String usuario) {
        Caixa caixa = aberto();
        Totais totais = totais(caixa);
        caixa.fechar(totais.dinheiroEsperado(), dinheiroContado, observacao, usuario, agora());
        log.info("Caixa #{} fechado por {}: esperado {} contado {}", caixa.getId(), usuario,
                caixa.getDinheiroEsperado(), caixa.getDinheiroContado());
        return caixa;
    }

    /**
     * Vendas pagas com o caixa fechado não podem se perder: o novo caixa conta desde o fechamento
     * do anterior, limitado ao início do dia.
     */
    private Instant vendasDesde() {
        Instant inicioDoDia = LocalDate.now(clock.withZone(zona)).atStartOfDay(zona).toInstant();
        return caixas.findFirstByStatusOrderByFechadoEmDesc(Caixa.Status.FECHADO)
                .map(Caixa::getFechadoEm)
                .filter(fechado -> fechado.isAfter(inicioDoDia))
                .orElse(inicioDoDia);
    }

    @Transactional(readOnly = true)
    public Totais totais(Caixa caixa) {
        Instant inicio = caixa.getVendasDesde();
        Instant fim = caixa.aberto() ? agora().plusSeconds(1) : caixa.getFechadoEm();

        Map<FormaPagamento, BigDecimal> valores = new EnumMap<>(FormaPagamento.class);
        Map<FormaPagamento, Integer> quantidades = new EnumMap<>(FormaPagamento.class);
        BigDecimal taxasEntrega = BigDecimal.ZERO;
        BigDecimal apps = BigDecimal.ZERO;
        List<Venda> vendas = new ArrayList<>();

        for (Pedido p : pedidos.doPeriodo(inicio, fim)) {
            if (p.cancelado() || p.getComanda() != null) {
                continue; // pedido de comanda entra quando a comanda é paga
            }
            if (p.getCanal().plataforma()) {
                apps = apps.add(p.getValorTotal());
                continue;
            }
            if (p.cortesia()) {
                continue;
            }
            valores.merge(p.getFormaPagamento(), p.totalACobrar(), BigDecimal::add);
            quantidades.merge(p.getFormaPagamento(), 1, Integer::sum);
            vendas.add(new Venda(p.getCriadoEm(), "Pedido #" + p.getId() + (p.entrega() ? " (entrega)" : ""),
                    p.getFormaPagamento(), p.totalACobrar()));
            if (p.entrega()) {
                taxasEntrega = taxasEntrega.add(p.taxaCobrada());
            }
        }
        for (Comanda c : comandas.encerradasNoPeriodo(inicio, fim)) {
            if (c.getStatus() == StatusComanda.FECHADA) {
                valores.merge(c.getFormaPagamento(), c.getValorTotal(), BigDecimal::add);
                quantidades.merge(c.getFormaPagamento(), 1, Integer::sum);
                vendas.add(new Venda(c.getFechadaEm(), "Comanda " + c.getIdentificacao(), c.getFormaPagamento(),
                        c.getValorTotal()));
            }
        }

        List<Recebimento> recebimentos = valores.entrySet().stream()
                .map(e -> new Recebimento(e.getKey(), quantidades.get(e.getKey()), e.getValue())).toList();
        BigDecimal total = valores.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal suprimentos = caixa.somar(m -> !m.saida());
        BigDecimal saidasDinheiro = caixa.somar(m -> m.saida() && m.emDinheiro());
        BigDecimal saidasOutras = caixa.somar(m -> m.saida() && !m.emDinheiro());
        BigDecimal esperado = caixa.getValorAbertura()
                .add(valores.getOrDefault(FormaPagamento.DINHEIRO, BigDecimal.ZERO))
                .add(suprimentos)
                .subtract(saidasDinheiro);
        vendas.sort(Comparator.comparing(Venda::quando).reversed());
        return new Totais(vendas, recebimentos, total, taxasEntrega, apps, suprimentos, saidasDinheiro, saidasOutras, esperado);
    }

    private Caixa aberto() {
        return atual().orElseThrow(() -> new RegraDeNegocioException("O caixa está fechado. Abra o caixa primeiro."));
    }

    private static boolean diariaPaga(Caixa caixa, Colaborador c) {
        return caixa.getMovimentos().stream().anyMatch(m -> m.isIncluiDiaria() && m.getColaborador() != null
                && m.getColaborador().getId().equals(c.getId()));
    }

    private Instant agora() {
        return Instant.now(clock);
    }
}
