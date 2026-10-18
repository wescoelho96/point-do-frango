package com.pointdofrango.painel;

import com.pointdofrango.pedido.ItemPedido;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoRepository;
import com.pointdofrango.pedido.StatusPedido;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Painel de vendas com metas: o dia de hoje comparado com a média dos dias anteriores em que houve venda.
 * Dias com a loja fechada não entram na média, para a meta não ficar artificialmente baixa.
 */
@Service
public class PainelVendasService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    public record Totais(int pedidos, BigDecimal faturamento, BigDecimal ticketMedio) {
    }

    public record Hora(int hora, BigDecimal mediaFaturamento, BigDecimal mediaPedidos, BigDecimal hojeFaturamento,
                       int hojePedidos) {
    }

    public record DiaDaSemana(int dia, String rotulo, BigDecimal mediaFaturamento, int diasComVenda) {
    }

    /** mediaDiaria: unidades por dia de venda no período, a meta do dia para o produto. */
    public record ProdutoMeta(Long produtoId, String produto, int quantidadePeriodo, BigDecimal faturamentoPeriodo,
                              BigDecimal mediaDiaria, int hoje) {
    }

    public record PainelVendas(LocalDate hoje, int dias, int diasComVenda, Totais hojeTotais, Totais mediaDiaria,
                               List<Hora> porHora, List<DiaDaSemana> porDiaDaSemana, List<ProdutoMeta> produtos) {
    }

    private final PedidoRepository pedidos;
    private final Clock clock;
    private final ZoneId zona;

    public PainelVendasService(PedidoRepository pedidos, Clock clock, ZoneId zona) {
        this.pedidos = pedidos;
        this.clock = clock;
        this.zona = zona;
    }

    @Transactional(readOnly = true)
    public PainelVendas painel(int dias) {
        if (dias < 7 || dias > 180) {
            throw new RegraDeNegocioException("Período de 7 a 180 dias.");
        }
        LocalDate hoje = LocalDate.now(clock.withZone(zona));
        List<Pedido> base = vendas(hoje.minusDays(dias), hoje);
        List<Pedido> deHoje = vendas(hoje, hoje.plusDays(1));

        Set<LocalDate> diasComVenda = new HashSet<>();
        base.forEach(p -> diasComVenda.add(dia(p)));
        int n = Math.max(1, diasComVenda.size());

        BigDecimal[] faturamentoHora = zeros(24);
        int[] pedidosHora = new int[24];
        BigDecimal[] hojeHora = zeros(24);
        int[] hojePedidosHora = new int[24];
        base.forEach(p -> {
            int h = hora(p);
            faturamentoHora[h] = faturamentoHora[h].add(p.getValorTotal());
            pedidosHora[h]++;
        });
        deHoje.forEach(p -> {
            int h = hora(p);
            hojeHora[h] = hojeHora[h].add(p.getValorTotal());
            hojePedidosHora[h]++;
        });
        List<Hora> porHora = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            porHora.add(new Hora(h, Dinheiro.centavos(faturamentoHora[h].divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP)),
                    BigDecimal.valueOf(pedidosHora[h]).divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP),
                    Dinheiro.centavos(hojeHora[h]), hojePedidosHora[h]));
        }

        Map<DayOfWeek, BigDecimal> somaSemana = new LinkedHashMap<>();
        Map<DayOfWeek, Set<LocalDate>> datasSemana = new HashMap<>();
        base.forEach(p -> {
            LocalDate d = dia(p);
            somaSemana.merge(d.getDayOfWeek(), p.getValorTotal(), BigDecimal::add);
            datasSemana.computeIfAbsent(d.getDayOfWeek(), k -> new HashSet<>()).add(d);
        });
        List<DiaDaSemana> porDia = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            int vezes = datasSemana.getOrDefault(d, Set.of()).size();
            BigDecimal media = vezes == 0 ? BigDecimal.ZERO
                    : somaSemana.get(d).divide(BigDecimal.valueOf(vezes), 2, RoundingMode.HALF_UP);
            porDia.add(new DiaDaSemana(d.getValue(), d.getDisplayName(TextStyle.SHORT, PT_BR), media, vezes));
        }

        Map<Long, String> nomes = new LinkedHashMap<>();
        Map<Long, int[]> qtdPeriodo = new HashMap<>();
        Map<Long, BigDecimal> fatPeriodo = new HashMap<>();
        Map<Long, int[]> qtdHoje = new HashMap<>();
        base.forEach(p -> itens(p).forEach(i -> {
            nomes.putIfAbsent(i.getProdutoId(), i.getNomeProduto());
            qtdPeriodo.computeIfAbsent(i.getProdutoId(), k -> new int[1])[0] += i.getQuantidade();
            fatPeriodo.merge(i.getProdutoId(), i.subtotal(), BigDecimal::add);
        }));
        deHoje.forEach(p -> itens(p).forEach(i -> {
            nomes.putIfAbsent(i.getProdutoId(), i.getNomeProduto());
            qtdHoje.computeIfAbsent(i.getProdutoId(), k -> new int[1])[0] += i.getQuantidade();
        }));
        List<ProdutoMeta> produtos = nomes.entrySet().stream().map(e -> {
            int periodo = qtdPeriodo.getOrDefault(e.getKey(), new int[1])[0];
            return new ProdutoMeta(e.getKey(), e.getValue(), periodo,
                    Dinheiro.centavos(fatPeriodo.getOrDefault(e.getKey(), BigDecimal.ZERO)),
                    BigDecimal.valueOf(periodo).divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP),
                    qtdHoje.getOrDefault(e.getKey(), new int[1])[0]);
        }).sorted(Comparator.comparingInt(ProdutoMeta::quantidadePeriodo).reversed()
                .thenComparing(Comparator.comparingInt(ProdutoMeta::hoje).reversed())).toList();

        BigDecimal fatBase = base.stream().map(Pedido::getValorTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        Totais media = new Totais(Math.round((float) base.size() / n),
                Dinheiro.centavos(fatBase.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP)), ticket(fatBase, base.size()));
        BigDecimal fatHoje = deHoje.stream().map(Pedido::getValorTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        Totais deHojeTotais = new Totais(deHoje.size(), Dinheiro.centavos(fatHoje), ticket(fatHoje, deHoje.size()));
        return new PainelVendas(hoje, dias, diasComVenda.size(), deHojeTotais, media, porHora, porDia, produtos);
    }

    /** Vendas de verdade: sem canceladas e sem cortesias. */
    private List<Pedido> vendas(LocalDate de, LocalDate ateExclusivo) {
        return pedidos.doPeriodo(de.atStartOfDay(zona).toInstant(), ateExclusivo.atStartOfDay(zona).toInstant()).stream()
                .filter(p -> p.getStatus() != StatusPedido.CANCELADO && !p.cortesia()).toList();
    }

    private static List<ItemPedido> itens(Pedido p) {
        return p.getItens().stream().filter(i -> !i.isBrinde()).toList();
    }

    private LocalDate dia(Pedido p) {
        return p.getCriadoEm().atZone(zona).toLocalDate();
    }

    private int hora(Pedido p) {
        ZonedDateTime z = p.getCriadoEm().atZone(zona);
        return z.getHour();
    }

    private static BigDecimal ticket(BigDecimal faturamento, int pedidos) {
        return pedidos == 0 ? BigDecimal.ZERO : faturamento.divide(BigDecimal.valueOf(pedidos), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] z = new BigDecimal[n];
        Arrays.fill(z, BigDecimal.ZERO);
        return z;
    }
}
