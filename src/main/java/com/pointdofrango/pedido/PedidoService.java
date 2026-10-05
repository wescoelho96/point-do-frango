package com.pointdofrango.pedido;

import com.pointdofrango.cliente.Cliente;
import com.pointdofrango.cliente.ClienteEsquecido;
import com.pointdofrango.cliente.ClienteService;
import com.pointdofrango.entrega.Bairro;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.eventos.EventoTempoReal;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoDtos.PedidoPlataformaRequest;
import com.pointdofrango.produto.ConsumoFicha;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.produto.PromocaoService;
import com.pointdofrango.produto.PromocaoService.Brinde;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Lançamento de pedidos (balcão, WhatsApp, mesa e apps), todos pelo mesmo fluxo.
 * O preço vem sempre do cardápio, nunca do cliente, e o estoque é travado antes da baixa:
 * se faltar insumo, nada é gravado.
 */
@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final PedidoRepository pedidos;
    private final ProdutoService produtoService;
    private final EstoqueService estoqueService;
    private final ConfiguracaoFinanceiraService configuracaoService;
    private final ComandaRepository comandas;
    private final EntregaCadastroService cadastros;
    private final ClienteService clientes;
    private final PromocaoService promocoes;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;
    private final ZoneId zona;

    public PedidoService(PedidoRepository pedidos, ProdutoService produtoService, EstoqueService estoqueService,
                         ConfiguracaoFinanceiraService configuracaoService, ComandaRepository comandas,
                         EntregaCadastroService cadastros, ClienteService clientes, PromocaoService promocoes,
                         ApplicationEventPublisher eventos, Clock clock, ZoneId zona) {
        this.promocoes = promocoes;
        this.pedidos = pedidos;
        this.comandas = comandas;
        this.cadastros = cadastros;
        this.clientes = clientes;
        this.eventos = eventos;
        this.produtoService = produtoService;
        this.estoqueService = estoqueService;
        this.configuracaoService = configuracaoService;
        this.clock = clock;
        this.zona = zona;
    }

    @Transactional
    public Pedido lancar(NovoPedidoRequest req, String usuario) {
        return registrar(req, usuario, null);
    }

    /**
     * Pedido de iFood/99Food lançado à mão, normalmente no fim do dia a partir do extrato do app.
     * Valor, promoção bancada pela loja e taxas vêm do app, não do cardápio.
     */
    @Transactional
    public Pedido lancarDePlataforma(PedidoPlataformaRequest req, String usuario) {
        if (!req.plataforma().plataforma()) {
            throw new RegraDeNegocioException("Canal " + req.plataforma() + " não é uma plataforma de delivery.");
        }
        String codigo = limpar(req.codigoExterno());
        if (codigo != null && pedidos.existsByCanalAndCodigoExterno(req.plataforma(), codigo)) {
            throw new RegraDeNegocioException("O pedido " + codigo + " do " + req.plataforma().getGrupo().getRotulo()
                    + " já foi lançado.");
        }
        Instant agora = Instant.now(clock);
        Instant quando = req.realizadoEm() == null ? agora : req.realizadoEm().atZone(zona).toInstant();
        if (quando.isAfter(agora.plusSeconds(300))) {
            throw new RegraDeNegocioException("A data/hora do pedido não pode estar no futuro.");
        }
        if (quando.isBefore(agora.minus(Duration.ofDays(31)))) {
            throw new RegraDeNegocioException("Só é possível lançar pedidos dos últimos 31 dias.");
        }
        var dados = new NovoPedidoRequest(req.plataforma(), FormaPagamento.PAGO_NO_APP, req.clienteNome(), null, null,
                req.observacao(), req.itens());
        return registrar(dados, usuario, new DadosPlataforma(codigo, req.valorPedido(), req.descontoLoja(),
                req.taxasPlataforma(), quando, !req.enviarParaCozinha()));
    }

    private record DadosPlataforma(String codigo, BigDecimal valorPedido, BigDecimal desconto, BigDecimal taxas,
                                   Instant realizadoEm, boolean jaEntregue) {
    }

    private Pedido registrar(NovoPedidoRequest req, String usuario, DadosPlataforma plataforma) {
        // Na comanda o pagamento só é definido no fechamento; venda avulsa precisa dele agora.
        Comanda comanda = null;
        if (req.comandaId() != null) {
            comanda = comandas.findById(req.comandaId())
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Comanda", req.comandaId()));
            comanda.garantirAberta();
        } else if (req.formaPagamento() == null) {
            throw new RegraDeNegocioException("Informe a forma de pagamento (ou lance numa comanda).");
        }
        if ((req.formaPagamento() == FormaPagamento.PAGO_NO_APP) != req.canal().plataforma()) {
            throw new RegraDeNegocioException(req.canal().plataforma()
                    ? "Pedidos de iFood/99Food são lançados com as taxas do app (Pedidos → \"Pedido iFood / 99Food\")."
                    : "\"Pago no app\" só vale para pedidos do iFood/99Food.");
        }

        String telefone = limpar(req.clienteTelefone()) == null ? null : Cliente.normalizarTelefone(req.clienteTelefone());
        Entrega entrega = req.entrega() == null ? null : validarEntrega(req, comanda, telefone);

        // LinkedHashMap mantém a ordem em que os itens foram lançados
        Map<Long, Integer> quantidades = new LinkedHashMap<>();
        for (ItemRequest item : req.itens()) {
            quantidades.merge(item.produtoId(), item.quantidade(), Integer::sum);
        }

        // Brinde da promoção do dia não vale para pedidos de app
        List<Brinde> brindes = req.semPromocao() || plataforma != null ? List.of()
                : promocoes.brindesPara(quantidades, LocalDate.now(clock.withZone(zona)).getDayOfWeek());
        Map<Long, Integer> saindo = new LinkedHashMap<>(quantidades);
        brindes.forEach(b -> saindo.merge(b.produtoId(), b.quantidade(), Integer::sum));

        Map<Long, Produto> produtos = produtoService.buscarVarios(saindo.keySet());

        // Consumo por insumo inclui os brindes, que também saem do estoque
        Map<Long, BigDecimal> consumoTotal = new TreeMap<>();
        Map<Long, List<ConsumoFicha>> fichaPorProduto = new LinkedHashMap<>();
        for (ConsumoFicha linha : produtoService.consumoDosProdutos(saindo.keySet())) {
            fichaPorProduto.computeIfAbsent(linha.produtoId(), k -> new ArrayList<>()).add(linha);
            BigDecimal usado = linha.quantidade().multiply(BigDecimal.valueOf(saindo.get(linha.produtoId())));
            consumoTotal.merge(linha.insumoId(), usado, BigDecimal::add);
        }

        // Ficha tem 10 casas e o estoque 6. Arredondar só o total evita acumular erro
        // (12 iscas de 0,0833333333 kg dão exatamente 1 kg).
        consumoTotal.replaceAll((insumoId, qtd) -> qtd.setScale(6, RoundingMode.HALF_UP));

        // Trava todos os insumos antes de baixar qualquer um
        Map<Long, Insumo> insumos = estoqueService.travarEConferir(consumoTotal);

        // Custo dos itens congelado com o custo médio lido sob a trava
        Instant agora = Instant.now(clock);
        String endereco = entrega != null ? entrega.enderecoCompleto() : limpar(req.enderecoEntrega());
        Pedido pedido = new Pedido(req.canal(), req.formaPagamento(),
                new Pedido.DadosCliente(limpar(req.clienteNome()), telefone, endereco, limpar(req.observacao())),
                usuario, agora);
        Function<Long, BigDecimal> custoDe = produtoId -> fichaPorProduto.getOrDefault(produtoId, List.of())
                .stream().map(l -> l.quantidade().multiply(insumos.get(l.insumoId()).getCustoUnitario()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        quantidades.forEach((produtoId, qtd) -> pedido.adicionarItem(produtos.get(produtoId), qtd, custoDe.apply(produtoId)));
        brindes.forEach(b -> pedido.adicionarBrinde(produtos.get(b.produtoId()), b.quantidade(), custoDe.apply(b.produtoId()),
                b.promocao()));

        if (comanda != null) {
            comanda.adicionar(pedido); // canal MESA; pagamento fica para o fechamento
        }

        // Na comanda o rateio é refeito no fechamento; no app a taxa é a que o app descontou
        if (req.formaPagamento() == FormaPagamento.CORTESIA) {
            if (comanda != null) {
                throw new RegraDeNegocioException("Cortesia é lançada como pedido avulso, não dentro de uma comanda.");
            }
            pedido.tornarCortesia(req.motivoCortesia());
        }
        if (plataforma == null) {
            pedido.fecharValores(configuracaoService.obter());
            // Sem item de preparo (ex.: só bebida) não entra na fila da cozinha
            if (quantidades.keySet().stream().noneMatch(id -> produtos.get(id).isVaiParaCozinha())) {
                pedido.registrarComoJaEntregue(agora);
            }
        } else {
            pedido.fecharValoresDePlataforma(plataforma.codigo(), plataforma.valorPedido(), plataforma.desconto(),
                    plataforma.taxas(), configuracaoService.obter());
            if (plataforma.jaEntregue()) {
                pedido.registrarComoJaEntregue(plataforma.realizadoEm()); // não vai para a fila da cozinha
            }
        }

        if (entrega != null) {
            boolean gratis = req.entrega().gratis() || req.formaPagamento() == FormaPagamento.CORTESIA;
            pedido.definirEntrega(entrega.bairro().getNome(), entrega.bairro().getTaxaEntrega(), gratis,
                    entrega.motoboy(), req.entrega().peloDono());
        }
        if (comanda == null) {
            pedido.registrarValorRecebido(req.valorRecebido());
        }
        if (req.salvarCliente() && telefone != null && pedido.getClienteNome() != null) {
            Cliente cliente = clientes.registrarDoPedido(pedido.getClienteNome(), telefone, limpar(req.enderecoEntrega()),
                    entrega != null ? entrega.referencia() : null, entrega != null ? entrega.bairro() : null, agora);
            pedido.vincularCliente(cliente.getId());
        }

        // Salva antes da baixa: o extrato do estoque referencia o id do pedido
        pedidos.save(pedido);
        estoqueService.baixarParaPedido(insumos, consumoTotal, pedido.getId(), usuario);

        log.info("Pedido #{} lançado ({}, {}) total={} por {}", pedido.getId(), pedido.getCanal(),
                pedido.getFormaPagamento(), pedido.getValorTotal(), usuario);
        // Só é transmitido após o commit (ver TransmissorEventos)
        eventos.publishEvent(new EventoTempoReal(pedido.getStatus() == StatusPedido.EM_PREPARO
                ? EventoTempoReal.Tipo.PEDIDO_NOVO : EventoTempoReal.Tipo.PEDIDO_STATUS, pedido.getId()));
        if (comanda != null) {
            eventos.publishEvent(new EventoTempoReal(EventoTempoReal.Tipo.COMANDA_ALTERADA, comanda.getId()));
        }
        return pedido;
    }

    private record Entrega(Bairro bairro, String endereco, String referencia, Colaborador motoboy) {

        String enderecoCompleto() {
            String completo = referencia == null ? endereco : endereco + " (ref.: " + referencia + ")";
            return completo.length() > 255 ? completo.substring(0, 255) : completo;
        }
    }

    private Entrega validarEntrega(NovoPedidoRequest req, Comanda comanda, String telefone) {
        if (comanda != null || (req.canal() != CanalVenda.WHATSAPP && req.canal() != CanalVenda.TELEFONE)) {
            throw new RegraDeNegocioException("Entrega só vale para pedidos de WhatsApp ou telefone.");
        }
        if (limpar(req.clienteNome()) == null || telefone == null || limpar(req.enderecoEntrega()) == null) {
            throw new RegraDeNegocioException("Para entrega, informe nome, telefone e endereço do cliente.");
        }
        Bairro bairro = cadastros.bairro(req.entrega().bairroId());
        if (!bairro.isAtivo()) {
            throw new RegraDeNegocioException("Não estamos entregando no bairro " + bairro.getNome() + ".");
        }
        Long motoboyId = req.entrega().entregadorId();
        return new Entrega(bairro, limpar(req.enderecoEntrega()), limpar(req.entrega().referencia()),
                motoboyId == null ? null : cadastros.motoboyAtivo(motoboyId));
    }

    @Transactional
    public Pedido atribuirEntregador(Long id, Long entregadorId, boolean peloDono) {
        Pedido pedido = buscar(id);
        pedido.atribuirEntregador(entregadorId == null ? null : cadastros.motoboyAtivo(entregadorId), peloDono);
        eventos.publishEvent(new EventoTempoReal(EventoTempoReal.Tipo.PEDIDO_STATUS, id));
        return pedido;
    }

    /** Entregas feitas e ainda não pagas ao motoboy, de qualquer dia. */
    @Transactional(readOnly = true)
    public List<Pedido> entregasSemAcerto() {
        return pedidos.entregasSemAcerto();
    }

    @EventListener
    @Transactional
    public void aoEsquecerCliente(ClienteEsquecido evento) {
        int n = pedidos.anonimizarCliente(evento.clienteId(), evento.telefone());
        log.info("{} pedido(s) anonimizado(s) do cliente #{}", n, evento.clienteId());
    }

    /** Política de retenção: tira nome, telefone e endereço dos pedidos mais antigos que o limite. */
    @Transactional
    public int anonimizarAnterioresA(Instant limite) {
        return pedidos.anonimizarAnterioresA(limite);
    }

    @Transactional
    public Pedido mudarStatus(Long id, StatusPedido status) {
        Pedido pedido = buscar(id);
        pedido.mudarStatus(status, Instant.now(clock));
        eventos.publishEvent(new EventoTempoReal(EventoTempoReal.Tipo.PEDIDO_STATUS, id));
        return pedido;
    }

    @Transactional
    public Pedido cancelar(Long id, String motivo, String usuario) {
        Pedido pedido = buscar(id);
        pedido.cancelar(motivo, Instant.now(clock));
        estoqueService.estornarPedido(pedido.getId(), usuario);
        log.info("Pedido #{} cancelado por {}: {}", id, usuario, motivo);
        eventos.publishEvent(new EventoTempoReal(EventoTempoReal.Tipo.PEDIDO_CANCELADO, id));
        if (pedido.getComanda() != null) {
            eventos.publishEvent(new EventoTempoReal(EventoTempoReal.Tipo.COMANDA_ALTERADA, pedido.getComanda().getId()));
        }
        return pedido;
    }

    @Transactional(readOnly = true)
    public Pedido buscar(Long id) {
        return pedidos.findWithItensById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Pedido", id));
    }

    /** Pedidos de um dia no fuso da loja (não no fuso do servidor). */
    @Transactional(readOnly = true)
    public List<Pedido> doDia(LocalDate dia) {
        return pedidos.doPeriodo(dia.atStartOfDay(zona).toInstant(), dia.plusDays(1).atStartOfDay(zona).toInstant());
    }

    /** Cortesias dos últimos dias, para conferir o que foi dado de graça (e quanto custou). */
    @Transactional(readOnly = true)
    public List<Pedido> cortesias(int dias) {
        return pedidos.cortesiasDesde(Instant.now(clock).minus(Duration.ofDays(dias)));
    }

    /** Fila da cozinha: o mais antigo primeiro. */
    @Transactional(readOnly = true)
    public List<Pedido> filaDaCozinha() {
        return pedidos.findByStatusInOrderByCriadoEmAsc(EnumSet.of(StatusPedido.EM_PREPARO, StatusPedido.PRONTO));
    }

    private static String limpar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
