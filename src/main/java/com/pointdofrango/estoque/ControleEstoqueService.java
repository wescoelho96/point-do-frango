package com.pointdofrango.estoque;

import com.pointdofrango.estoque.EstoqueDtos.FuroInsumo;
import com.pointdofrango.estoque.EstoqueDtos.IndicadorInsumo;
import com.pointdofrango.estoque.EstoqueDtos.LoteResponse;
import com.pointdofrango.estoque.EstoqueDtos.StatusEstoque;
import com.pointdofrango.estoque.EstoqueDtos.ValidadeRequest;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Indicadores da tela de estoque: situação de cada item, furo de inventário e validades. */
@Service
public class ControleEstoqueService {

    /** Abaixo disso o saldo é crítico (ou alerta), em dias do consumo médio. */
    static final BigDecimal DIAS_CRITICO = BigDecimal.valueOf(2);
    static final BigDecimal DIAS_ALERTA = BigDecimal.valueOf(5);
    /** Mínimo sugerido: o consumo de 3 dias, o tempo de ir ao mercado e voltar com folga. */
    static final BigDecimal DIAS_MINIMO = BigDecimal.valueOf(3);
    private static final List<TipoMovimentacao> CONSUMO =
            List.of(TipoMovimentacao.SAIDA_VENDA, TipoMovimentacao.ESTORNO_VENDA, TipoMovimentacao.CONSUMO_INTERNO);

    private final InsumoRepository insumos;
    private final MovimentacaoEstoqueRepository movimentacoes;
    private final LoteValidadeRepository lotes;
    private final Clock clock;
    private final ZoneId zona;

    public ControleEstoqueService(InsumoRepository insumos, MovimentacaoEstoqueRepository movimentacoes,
                                  LoteValidadeRepository lotes, Clock clock, ZoneId zona) {
        this.insumos = insumos;
        this.movimentacoes = movimentacoes;
        this.lotes = lotes;
        this.clock = clock;
        this.zona = zona;
    }

    @Transactional(readOnly = true)
    public List<IndicadorInsumo> indicadores(int dias) {
        if (dias < 1 || dias > 180) {
            throw new RegraDeNegocioException("Período de 1 a 180 dias.");
        }
        Instant desde = Instant.now(clock).minus(Duration.ofDays(dias));
        Map<Long, BigDecimal> consumo = new HashMap<>();
        for (Object[] linha : movimentacoes.somaPorInsumo(CONSUMO, desde)) {
            consumo.put((Long) linha[0], ((BigDecimal) linha[1]).negate());
        }
        BigDecimal periodo = BigDecimal.valueOf(dias);
        return insumos.findAllByOrderByNomeAsc().stream().filter(Insumo::isAtivo).map(i -> {
            BigDecimal noPeriodo = consumo.getOrDefault(i.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            BigDecimal porDia = noPeriodo.divide(periodo, 4, RoundingMode.HALF_UP);
            BigDecimal cobertura = porDia.signum() > 0
                    ? i.getEstoqueAtual().max(BigDecimal.ZERO).divide(porDia, 1, RoundingMode.HALF_UP) : null;
            return new IndicadorInsumo(i.getId(), noPeriodo, porDia, cobertura, status(i, cobertura),
                    porDia.multiply(DIAS_MINIMO).setScale(3, RoundingMode.UP));
        }).toList();
    }

    /** Crítico: zerado, abaixo do mínimo ou acaba em menos de 2 dias. Alerta: perto do mínimo ou menos de 5 dias. */
    static StatusEstoque status(Insumo i, BigDecimal coberturaDias) {
        BigDecimal saldo = i.getEstoqueAtual();
        BigDecimal minimo = i.getEstoqueMinimo();
        boolean temMinimo = minimo.signum() > 0;
        if (saldo.signum() <= 0 || (temMinimo && saldo.compareTo(minimo) < 0)
                || (coberturaDias != null && coberturaDias.compareTo(DIAS_CRITICO) < 0)) {
            return StatusEstoque.CRITICO;
        }
        if ((temMinimo && saldo.compareTo(minimo.multiply(new BigDecimal("1.5"))) < 0)
                || (coberturaDias != null && coberturaDias.compareTo(DIAS_ALERTA) < 0)) {
            return StatusEstoque.ALERTA;
        }
        return StatusEstoque.NORMAL;
    }

    @Transactional(readOnly = true)
    public List<FuroInsumo> furo(LocalDate inicio, LocalDate fim) {
        if (fim.isBefore(inicio)) {
            throw new RegraDeNegocioException("A data final não pode ser antes da inicial.");
        }
        Instant desde = inicio.atStartOfDay(zona).toInstant();
        Instant ate = fim.plusDays(1).atStartOfDay(zona).toInstant();
        Map<Long, Insumo> porId = insumos.findAllByOrderByNomeAsc().stream()
                .collect(Collectors.toMap(Insumo::getId, Function.identity()));
        Map<Long, BigDecimal[]> somas = new HashMap<>(); // vendido, consumo, perdas, sobras, valorPerdas, valorSobras
        for (Object[] l : movimentacoes.furoPorInsumo(desde, ate)) {
            BigDecimal[] s = somas.computeIfAbsent((Long) l[0], id -> zeros());
            TipoMovimentacao tipo = (TipoMovimentacao) l[1];
            BigDecimal negativo = (BigDecimal) l[2];
            BigDecimal positivo = (BigDecimal) l[3];
            switch (tipo) {
                case SAIDA_VENDA, ESTORNO_VENDA -> s[0] = s[0].subtract(negativo).subtract(positivo);
                case CONSUMO_INTERNO -> s[1] = s[1].subtract(negativo);
                case AJUSTE -> {
                    s[2] = s[2].subtract(negativo);
                    s[3] = s[3].add(positivo);
                    s[4] = s[4].subtract((BigDecimal) l[4]);
                    s[5] = s[5].add((BigDecimal) l[5]);
                }
                default -> { }
            }
        }
        return somas.entrySet().stream().filter(e -> porId.containsKey(e.getKey())).map(e -> {
            Insumo i = porId.get(e.getKey());
            BigDecimal[] s = e.getValue();
            BigDecimal saiu = s[0].add(s[1]);
            BigDecimal percentual = saiu.signum() > 0
                    ? s[2].multiply(BigDecimal.valueOf(100)).divide(saiu, 1, RoundingMode.HALF_UP) : null;
            return new FuroInsumo(i.getId(), i.getNome(), i.getUnidade().getSigla(), i.getGrupo(), s[0], s[1], s[2], s[3],
                    Dinheiro.centavos(s[4]), Dinheiro.centavos(s[5]), percentual);
        }).sorted(Comparator.comparing(FuroInsumo::valorPerdas).reversed()).toList();
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] z = new BigDecimal[6];
        Arrays.fill(z, BigDecimal.ZERO);
        return z;
    }

    /**
     * Lotes que ainda devem estar no estoque. O saldo de cada insumo vai primeiro para os lotes mais novos:
     * o que chegou antes é o que sai antes (PEPS), sem precisar dizer de qual lote saiu cada venda.
     */
    @Transactional(readOnly = true)
    public List<LoteResponse> lotes() {
        LocalDate hoje = LocalDate.now(clock.withZone(zona));
        Map<Long, Insumo> porId = insumos.findAllByOrderByNomeAsc().stream()
                .collect(Collectors.toMap(Insumo::getId, Function.identity()));
        Map<Long, BigDecimal> saldoLivre = new HashMap<>();
        porId.values().forEach(i -> saldoLivre.put(i.getId(), i.getEstoqueAtual().max(BigDecimal.ZERO)));
        List<LoteResponse> resposta = new ArrayList<>();
        for (LoteValidade lote : lotes.findAllByOrderByCriadoEmDescIdDesc()) {
            Insumo i = porId.get(lote.getInsumoId());
            if (i == null) {
                continue;
            }
            BigDecimal livre = saldoLivre.get(i.getId());
            BigDecimal restante = lote.getQuantidade().min(livre);
            saldoLivre.put(i.getId(), livre.subtract(restante));
            if (restante.signum() > 0) {
                resposta.add(new LoteResponse(lote.getId(), i.getId(), i.getNome(), i.getUnidade().getSigla(), i.getLocal(),
                        lote.getQuantidade(), restante, lote.getValidade(), ChronoUnit.DAYS.between(hoje, lote.getValidade()),
                        Dinheiro.centavos(restante.multiply(i.getCustoUnitario()))));
            }
        }
        resposta.sort(Comparator.comparing(LoteResponse::validade));
        return resposta;
    }

    @Transactional
    public LoteValidade registrarLote(Long insumoId, ValidadeRequest req, String usuario) {
        if (!insumos.existsById(insumoId)) {
            throw new RecursoNaoEncontradoException("Insumo", insumoId);
        }
        return lotes.save(new LoteValidade(insumoId, req.quantidade(), req.validade(), usuario, Instant.now(clock)));
    }

    @Transactional
    public void removerLote(Long id) {
        lotes.deleteById(id);
    }
}
