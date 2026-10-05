package com.pointdofrango.pedido;

import com.pointdofrango.eventos.EventoTempoReal;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.loja.LojaService;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Abertura, fechamento e cancelamento de comandas. Os pedidos entram pelo PedidoService, com comandaId.
 */
@Service
public class ComandaService {

    private static final Logger log = LoggerFactory.getLogger(ComandaService.class);

    private final ComandaRepository comandas;
    private final PedidoService pedidoService;
    private final ConfiguracaoFinanceiraService financeiro;
    private final LojaService loja;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;
    private final ZoneId zona;

    public ComandaService(ComandaRepository comandas, PedidoService pedidoService, ConfiguracaoFinanceiraService financeiro,
                          LojaService loja, ApplicationEventPublisher eventos, Clock clock, ZoneId zona) {
        this.comandas = comandas;
        this.pedidoService = pedidoService;
        this.financeiro = financeiro;
        this.loja = loja;
        this.eventos = eventos;
        this.clock = clock;
        this.zona = zona;
    }

    @Transactional
    public Comanda abrir(String identificacao, String observacao, String usuario) {
        if (identificacao != null && comandas.existsByIdentificacaoIgnoreCaseAndStatus(identificacao.trim(), StatusComanda.ABERTA)) {
            throw new RegraDeNegocioException("Já existe uma comanda aberta para \"" + identificacao.trim() + "\".");
        }
        Comanda comanda = comandas.save(new Comanda(identificacao, observacao, usuario, Instant.now(clock)));
        avisar(comanda);
        return comanda;
    }

    @Transactional(readOnly = true)
    public Comanda buscar(Long id) {
        return comandas.findWithPedidosById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Comanda", id));
    }

    @Transactional(readOnly = true)
    public List<Comanda> abertas() {
        return comandas.findByStatusOrderByAbertaEmAsc(StatusComanda.ABERTA);
    }

    /** Fechadas e canceladas no período, inclusive, no fuso da loja. */
    @Transactional(readOnly = true)
    public List<Comanda> encerradasNoPeriodo(LocalDate inicio, LocalDate fim) {
        if (fim.isBefore(inicio)) {
            throw new RegraDeNegocioException("A data final não pode ser antes da inicial.");
        }
        if (ChronoUnit.DAYS.between(inicio, fim) > 366) {
            throw new RegraDeNegocioException("Período máximo: 1 ano.");
        }
        return comandas.encerradasNoPeriodo(inicio.atStartOfDay(zona).toInstant(),
                fim.plusDays(1).atStartOfDay(zona).toInstant());
    }

    /**
     * O cliente pode recusar a taxa de serviço. O percentual vigente no fechamento fica gravado na comanda.
     */
    @Transactional
    public Comanda fechar(Long id, FormaPagamento forma, boolean cobrarServico, String usuario) {
        return fechar(id, forma, cobrarServico, null, usuario);
    }

    /** valorRecebido só vale para dinheiro, para calcular o troco. */
    @Transactional
    public Comanda fechar(Long id, FormaPagamento forma, boolean cobrarServico, BigDecimal valorRecebido, String usuario) {
        Comanda comanda = buscar(id);
        comanda.fechar(forma, cobrarServico, loja.obter().getPercentualServico(), valorRecebido, financeiro.obter(),
                usuario, Instant.now(clock));
        log.info("Comanda #{} ({}) fechada por {}: itens={} serviço={} total={} {}", id, comanda.getIdentificacao(),
                usuario, comanda.getValorItens(), comanda.getValorServico(), comanda.getValorTotal(), forma);
        avisar(comanda);
        return comanda;
    }

    /** Cancela cada pedido da comanda, estornando o estoque. */
    @Transactional
    public Comanda cancelar(Long id, String motivo, String usuario) {
        Comanda comanda = buscar(id);
        comanda.garantirAberta();
        for (Pedido p : comanda.pedidosValidos()) {
            pedidoService.cancelar(p.getId(), "Comanda cancelada: " + motivo, usuario);
        }
        comanda.cancelar(motivo, usuario, Instant.now(clock));
        avisar(comanda);
        return comanda;
    }

    private void avisar(Comanda comanda) {
        eventos.publishEvent(new EventoTempoReal(EventoTempoReal.Tipo.COMANDA_ALTERADA, comanda.getId()));
    }
}
