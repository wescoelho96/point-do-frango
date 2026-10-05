package com.pointdofrango.pedido;

import com.pointdofrango.estoque.EmbalagemCompra;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Perfil demo: gera pedidos dos últimos 14 dias pelo fluxo real do PedidoService, para o painel
 * já abrir com números. Semente fixa para os dados serem reproduzíveis.
 */
@Component
@Profile("demo")
@Order(2)
class DemoPedidosSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPedidosSeeder.class);
    private static final int DIAS = 14;

    private final PedidoService pedidoService;
    private final PedidoRepository pedidos;
    private final ProdutoService produtos;
    private final EstoqueService estoque;
    private final ComandaService comandas;
    private final ComandaRepository comandaRepository;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zona;

    DemoPedidosSeeder(PedidoService pedidoService, PedidoRepository pedidos, ProdutoService produtos,
                      EstoqueService estoque, ComandaService comandas, ComandaRepository comandaRepository,
                      TransactionTemplate tx, Clock clock, ZoneId zona) {
        this.pedidoService = pedidoService;
        this.pedidos = pedidos;
        this.produtos = produtos;
        this.estoque = estoque;
        this.comandas = comandas;
        this.comandaRepository = comandaRepository;
        this.tx = tx;
        this.clock = clock;
        this.zona = zona;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (pedidos.existsBy()) {
            return;
        }
        List<Produto> cardapio = produtos.listar(true);
        if (cardapio.isEmpty()) {
            return;
        }
        Random aleatorio = new Random(42);
        FormaPagamento[] formas = {FormaPagamento.DINHEIRO, FormaPagamento.PIX, FormaPagamento.DEBITO, FormaPagamento.CREDITO};
        CanalVenda[] canais = {CanalVenda.BALCAO, CanalVenda.BALCAO, CanalVenda.WHATSAPP, CanalVenda.WHATSAPP,
                CanalVenda.TELEFONE};
        LocalDate hoje = LocalDate.now(clock.withZone(zona));
        int criados = 0;

        for (int d = DIAS; d >= 0; d--) {
            LocalDate dia = hoje.minusDays(d);
            int pedidosNoDia = d == 0 ? 3 : 2 + aleatorio.nextInt(3);
            for (int n = 0; n < pedidosNoDia; n++) {
                List<ItemRequest> itens = new ArrayList<>();
                int qtdItens = 1 + aleatorio.nextInt(2);
                for (int i = 0; i < qtdItens; i++) {
                    Produto p = cardapio.get(aleatorio.nextInt(cardapio.size()));
                    itens.add(new ItemRequest(p.getId(), 1 + aleatorio.nextInt(2)));
                }
                CanalVenda canal = canais[aleatorio.nextInt(canais.length)];
                var req = new NovoPedidoRequest(canal, formas[aleatorio.nextInt(formas.length)],
                        canal == CanalVenda.BALCAO ? null : "Cliente " + (100 + aleatorio.nextInt(900)),
                        null, null, null, itens);
                try {
                    Pedido pedido = pedidoService.lancar(req, "demo");
                    var quando = dia.atTime(11 + aleatorio.nextInt(11), aleatorio.nextInt(60)).atZone(zona).toInstant();
                    boolean historico = d > 0;
                    tx.executeWithoutResult(s -> pedidos.findById(pedido.getId()).ifPresent(p -> {
                        if (historico) {
                            p.mudarStatus(StatusPedido.PRONTO, quando);
                            p.mudarStatus(StatusPedido.ENTREGUE, quando);
                            p.retroagirPara(quando);
                        }
                    }));
                    criados++;
                } catch (RegraDeNegocioException e) {
                    // estoque da demo acabou para esse item
                }
            }
        }
        reporEstoque(cardapio);
        criados += pedidosDeApps(cardapio, hoje, aleatorio);
        reporEstoque(cardapio);
        log.info("Perfil DEMO: {} pedidos de exemplo gerados.", criados);
        historicoDeComandas(cardapio, hoje, aleatorio);
        abrirMesas(cardapio);
    }

    /** Comandas fechadas nos últimos 6 dias. */
    private void historicoDeComandas(List<Produto> cardapio, LocalDate hoje, Random aleatorio) {
        FormaPagamento[] formas = {FormaPagamento.PIX, FormaPagamento.DINHEIRO, FormaPagamento.DEBITO, FormaPagamento.CREDITO};
        for (int d = 6; d >= 1; d--) {
            int qtd = 1 + aleatorio.nextInt(3);
            for (int i = 0; i < qtd; i++) {
                Comanda c = comandas.abrir("Mesa " + (1 + aleatorio.nextInt(8)), null, "demo");
                lancarNaMesa(c, cardapio.get(aleatorio.nextInt(cardapio.size())), 1 + aleatorio.nextInt(2));
                lancarNaMesa(c, cardapio.get(aleatorio.nextInt(cardapio.size())), 1);
                try {
                    comandas.fechar(c.getId(), formas[aleatorio.nextInt(formas.length)], aleatorio.nextInt(10) < 7, "demo");
                } catch (RegraDeNegocioException e) {
                    comandas.cancelar(c.getId(), "sem consumo (demo)", "demo"); // estoque de demo acabou
                }
                var abertura = hoje.minusDays(d).atTime(19 + i, aleatorio.nextInt(30)).atZone(zona).toInstant();
                var fechamento = abertura.plusSeconds(60L * (40 + aleatorio.nextInt(50)));
                tx.executeWithoutResult(s -> comandaRepository.findWithPedidosById(c.getId())
                        .ifPresent(x -> x.retroagirPara(abertura, fechamento)));
            }
        }
    }

    /** Mesas abertas para Comandas e Cozinha não abrirem vazias. */
    private void abrirMesas(List<Produto> cardapio) {
        Comanda mesa2 = comandas.abrir("Mesa 2", null, "demo");
        Comanda mesa5 = comandas.abrir("Mesa 5", "Aniversário", "demo");
        lancarNaMesa(mesa2, cardapio.get(0), 2);
        lancarNaMesa(mesa2, cardapio.get(cardapio.size() - 1), 1);
        lancarNaMesa(mesa5, cardapio.get(Math.min(1, cardapio.size() - 1)), 1);
    }

    private void lancarNaMesa(Comanda mesa, Produto produto, int qtd) {
        try {
            pedidoService.lancar(new NovoPedidoRequest(CanalVenda.MESA, null, null, null, null, null,
                    List.of(new ItemRequest(produto.getId(), qtd)), mesa.getId()), "demo");
        } catch (RegraDeNegocioException e) {
            // sem estoque na demo
        }
    }

    /** Preço no app 15% acima do cardápio; taxas ilustrativas (iFood 15,2%, 99Food 12%). */
    private int pedidosDeApps(List<Produto> cardapio, LocalDate hoje, Random aleatorio) {
        int criados = 0;
        for (int d = DIAS; d >= 1; d--) {
            for (CanalVenda app : new CanalVenda[]{CanalVenda.IFOOD, CanalVenda.NOVENTA_NOVE_FOOD}) {
                if (aleatorio.nextInt(10) < (app == CanalVenda.IFOOD ? 7 : 4)) {
                    Produto p = cardapio.get(aleatorio.nextInt(cardapio.size()));
                    BigDecimal valor = Dinheiro.centavos(p.getPrecoVenda().multiply(new BigDecimal("1.15")));
                    BigDecimal taxa = Dinheiro.percentual(valor, new BigDecimal(app == CanalVenda.IFOOD ? "15.2" : "12"));
                    try {
                        pedidoService.lancarDePlataforma(new PedidoDtos.PedidoPlataformaRequest(app,
                                (app == CanalVenda.IFOOD ? "IF" : "99") + "-" + (1000 + aleatorio.nextInt(9000)),
                                hoje.minusDays(d).atTime(19 + aleatorio.nextInt(4), aleatorio.nextInt(60)),
                                "Cliente app", null, List.of(new ItemRequest(p.getId(), 1)), valor, null, taxa, false),
                                "demo");
                        criados++;
                    } catch (RegraDeNegocioException e) {
                        // sem estoque ou código repetido
                    }
                }
            }
        }
        return criados;
    }

    /** Repõe pelo fluxo de entrada o que ficou baixo, para a demo não abrir com o PDV sem estoque. */
    private void reporEstoque(List<Produto> cardapio) {
        Set<Long> usados = new HashSet<>();
        cardapio.forEach(p -> produtos.buscar(p.getId()).getFichaTecnica()
                .forEach(f -> usados.add(f.getInsumo().getId())));
        for (Insumo i : estoque.listar()) {
            BigDecimal alvo = i.getEstoqueMinimo().multiply(BigDecimal.valueOf(3));
            if (!usados.contains(i.getId()) || i.getEstoqueAtual().compareTo(i.getEstoqueMinimo().multiply(new BigDecimal("1.5"))) >= 0) {
                continue;
            }
            BigDecimal falta = alvo.subtract(i.getEstoqueAtual());
            EntradaRequest compra;
            if (!i.getEmbalagens().isEmpty()) {
                EmbalagemCompra emb = i.getEmbalagens().get(0);
                BigDecimal qtd = falta.divide(emb.getConteudo(), 0, RoundingMode.CEILING);
                compra = new EntradaRequest(null, emb.getId(), qtd,
                        Dinheiro.centavos(qtd.multiply(emb.getConteudo()).multiply(i.getCustoUnitario())), "Compra (demo)");
            } else {
                compra = new EntradaRequest(falta, null, null, Dinheiro.centavos(falta.multiply(i.getCustoUnitario())), "Compra (demo)");
            }
            estoque.registrarEntrada(i.getId(), compra, "demo");
        }
    }
}
