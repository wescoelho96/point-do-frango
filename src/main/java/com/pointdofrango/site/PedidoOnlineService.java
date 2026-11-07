package com.pointdofrango.site;

import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.cliente.Cliente;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.loja.ConfiguracaoLoja;
import com.pointdofrango.loja.LojaService;
import com.pointdofrango.pedido.CanalVenda;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoDtos.EntregaRequest;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoService;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.RegraDeNegocioException;
import com.pointdofrango.site.PedidoOnlineDtos.BairroEntrega;
import com.pointdofrango.site.PedidoOnlineDtos.Cardapio;
import com.pointdofrango.site.PedidoOnlineDtos.ItemCardapio;
import com.pointdofrango.site.PedidoOnlineDtos.PedidoCriado;
import com.pointdofrango.site.PedidoOnlineDtos.PedidoRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Pedido feito pelo próprio cliente no site. Entra pelo mesmo fluxo do balcão (cozinha, estoque, potes,
 * histórico), com canal SITE. O preço e a taxa de entrega vêm sempre do cadastro, nunca do navegador.
 */
@Service
public class PedidoOnlineService {

    private static final Logger log = LoggerFactory.getLogger(PedidoOnlineService.class);
    static final String USUARIO = "site";
    private static final Set<FormaPagamento> FORMAS = EnumSet.of(FormaPagamento.PIX, FormaPagamento.DINHEIRO,
            FormaPagamento.DEBITO, FormaPagamento.CREDITO);
    /** Freio contra robô e brincadeira: pedidos por telefone e por aparelho numa janela de tempo. */
    private static final int POR_TELEFONE = 3;
    private static final int POR_ORIGEM = 6;
    private static final Duration JANELA = Duration.ofMinutes(30);

    private final PedidoService pedidos;
    private final ProdutoService produtos;
    private final EntregaCadastroService cadastros;
    private final LojaService loja;
    private final CaixaService caixa;
    private final Clock clock;
    private final ZoneId zona;
    private final Map<String, Deque<Instant>> recentes = new ConcurrentHashMap<>();

    public PedidoOnlineService(PedidoService pedidos, ProdutoService produtos, EntregaCadastroService cadastros,
                               LojaService loja, CaixaService caixa, Clock clock, ZoneId zona) {
        this.pedidos = pedidos;
        this.produtos = produtos;
        this.cadastros = cadastros;
        this.loja = loja;
        this.caixa = caixa;
        this.clock = clock;
        this.zona = zona;
    }

    @Transactional(readOnly = true)
    public Cardapio cardapio() {
        ConfiguracaoLoja l = loja.obter();
        var itens = produtos.listar(true).stream()
                .map(p -> new ItemCardapio(p.getId(), p.getNome(), p.getDescricao(), p.getCategoria(),
                        p.getCategoria().getRotulo(), p.getPrecoVenda(), p.unidadesDisponiveis() > 0))
                .toList();
        var bairros = cadastros.listarBairros(true).stream()
                .map(b -> new BairroEntrega(b.getId(), b.getNome(), entregaGratisHoje(l) ? BigDecimal.ZERO : b.getTaxaEntrega()))
                .toList();
        return new Cardapio(l.getNome(), l.getTelefone(), l.getEndereco(), aberto(), entregaGratisHoje(l), itens, bairros);
    }

    /** origem: IP de quem pediu, só para o limite de pedidos (não é gravado). */
    @Transactional
    public PedidoCriado pedir(PedidoRequest req, String origem) {
        if (req.site() != null && !req.site().isEmpty()) {
            throw new RegraDeNegocioException("Não foi possível enviar o pedido.");
        }
        if (!aberto()) {
            throw new RegraDeNegocioException("A loja está fechada agora. Tente de novo no horário de funcionamento.");
        }
        if (!FORMAS.contains(req.formaPagamento())) {
            throw new RegraDeNegocioException("Escolha PIX, dinheiro, débito ou crédito.");
        }
        String telefone = Cliente.normalizarTelefone(req.telefone());
        if (telefone.replaceAll("\\D", "").length() < 10) {
            throw new RegraDeNegocioException("Informe o telefone com DDD.");
        }
        boolean entrega = req.bairroId() != null;
        if (entrega && (req.endereco() == null || req.endereco().isBlank())) {
            throw new RegraDeNegocioException("Informe o endereço de entrega.");
        }
        Instant agora = Instant.now(clock);
        frear("tel:" + telefone, POR_TELEFONE, agora, "Você já fez vários pedidos agora há pouco. Fale com a loja pelo WhatsApp.");
        frear("ip:" + origem, POR_ORIGEM, agora, "Muitos pedidos deste aparelho. Fale com a loja pelo WhatsApp.");

        boolean dinheiro = req.formaPagamento() == FormaPagamento.DINHEIRO;
        var pedido = new NovoPedidoRequest(CanalVenda.SITE, req.formaPagamento(),
                req.nome(), telefone, entrega ? req.endereco() : null,
                observacao(req, entrega),
                req.itens().stream().map(i -> new ItemRequest(i.produtoId(), i.quantidade())).toList(), null,
                entrega ? new EntregaRequest(req.bairroId(), req.referencia(), null, entregaGratisHoje(loja.obter()), false) : null,
                false, dinheiro ? req.trocoPara() : null, req.salvarDados(), null);
        Pedido p = pedidos.lancar(pedido, USUARIO);
        registrar("tel:" + telefone, agora);
        registrar("ip:" + origem, agora);
        log.info("Pedido #{} feito pelo site: {} {}", p.getId(), p.totalACobrar(), entrega ? "entrega" : "retirada");
        return new PedidoCriado(p.getId(), p.getValorTotal(), entrega ? p.taxaCobrada() : BigDecimal.ZERO, p.totalACobrar(),
                entrega, p.getFormaPagamento().name(), p.troco());
    }

    private static String observacao(PedidoRequest req, boolean entrega) {
        String obs = req.observacao() == null || req.observacao().isBlank() ? "" : req.observacao().strip();
        String tipo = entrega ? "" : "Retirada na loja";
        String junto = String.join(" · ", Stream.of(tipo, obs).filter(s -> !s.isEmpty()).toList());
        return junto.isEmpty() ? null : junto.length() > 255 ? junto.substring(0, 255) : junto;
    }

    private boolean aberto() {
        return caixa.atual().isPresent();
    }

    private boolean entregaGratisHoje(ConfiguracaoLoja l) {
        return l.getDiasEntregaGratis().contains(LocalDate.now(clock.withZone(zona)).getDayOfWeek());
    }

    private void frear(String chave, int limite, Instant agora, String mensagem) {
        Deque<Instant> fila = recentes.get(chave);
        if (fila == null) {
            return;
        }
        synchronized (fila) {
            fila.removeIf(t -> t.isBefore(agora.minus(JANELA)));
            if (fila.size() >= limite) {
                throw new RegraDeNegocioException(mensagem);
            }
        }
    }

    private void registrar(String chave, Instant agora) {
        Deque<Instant> fila = recentes.computeIfAbsent(chave, k -> new ArrayDeque<>());
        synchronized (fila) {
            fila.addLast(agora);
        }
        // não deixa o mapa crescer para sempre
        if (recentes.size() > 5000) {
            recentes.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast().isBefore(agora.minus(JANELA)));
        }
    }
}
