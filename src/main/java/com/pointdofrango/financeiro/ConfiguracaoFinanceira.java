package com.pointdofrango.financeiro;

import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Percentuais do rateio e taxas de pagamento, em linha única (id = 1). Valores em pontos percentuais (20.00 = 20%).
 * Contas fixas incidem sobre o faturamento; pró-labore sobre o lucro, e o que sobra do lucro vai para a reserva.
 */
@Entity
@Table(name = "configuracao_financeira")
public class ConfiguracaoFinanceira {

    public static final long ID_UNICO = 1L;
    private static final BigDecimal CEM = new BigDecimal("100");

    @Id
    private Long id;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percentualContasFixas;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percentualProLabore;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxaPix;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxaDebito;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxaCredito;

    /** Só pré-preenche o lançamento; o valor real vem do extrato do app. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxaIfood = new BigDecimal("15.20");

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxaNoventaNove = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant atualizadoEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RegimeTributario regimeTributario = RegimeTributario.MEI;

    /** Teto anual do MEI (R$ 81 mil hoje). Configurável porque a lei muda. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal limiteFaturamentoAnual = new BigDecimal("81000.00");

    /**
     * Contas primeiro: a sobra de cada venda (depois da taxa e da reposição) vai toda para as contas fixas
     * até o mês estar pago; só então vira lucro. Desligado, cada venda guarda percentualContasFixas.
     */
    @Column(nullable = false)
    private boolean contasPrimeiro;

    /** Dias da semana em que a loja abre (MONDAY,TUESDAY...); nulo = todos. Divide as contas em cota diária. */
    private String diasTrabalho;

    protected ConfiguracaoFinanceira() {
    }

    public ConfiguracaoFinanceira(BigDecimal percentualContasFixas, BigDecimal percentualProLabore,
                                  BigDecimal taxaPix, BigDecimal taxaDebito, BigDecimal taxaCredito, Instant agora) {
        this.id = ID_UNICO;
        atualizar(percentualContasFixas, percentualProLabore, taxaPix, taxaDebito, taxaCredito, agora);
    }

    public void atualizar(BigDecimal percentualContasFixas, BigDecimal percentualProLabore,
                          BigDecimal taxaPix, BigDecimal taxaDebito, BigDecimal taxaCredito, Instant agora) {
        this.percentualContasFixas = entre0e100(percentualContasFixas, "Percentual de contas fixas");
        this.percentualProLabore = entre0e100(percentualProLabore, "Percentual de pró-labore");
        this.taxaPix = entre0e100(taxaPix, "Taxa do PIX");
        this.taxaDebito = entre0e100(taxaDebito, "Taxa do débito");
        this.taxaCredito = entre0e100(taxaCredito, "Taxa do crédito");
        this.atualizadoEm = agora;
    }

    /** Forma nula: comanda ainda não paga. Taxa provisória zero, recalculada no fechamento. */
    public BigDecimal taxaPara(FormaPagamento forma) {
        if (forma == null) {
            return BigDecimal.ZERO;
        }
        return switch (forma) {
            case DINHEIRO -> BigDecimal.ZERO;
            case PIX -> taxaPix;
            case DEBITO -> taxaDebito;
            case CREDITO -> taxaCredito;
            case PAGO_NO_APP -> BigDecimal.ZERO; // taxa informada em reais no lançamento
            case CORTESIA -> BigDecimal.ZERO;
            case DIVIDIDO -> BigDecimal.ZERO; // a taxa é a soma das partes (Pedido.taxaDasPartes)
        };
    }

    public void definirContasPrimeiro(boolean contasPrimeiro) {
        this.contasPrimeiro = contasPrimeiro;
    }

    public boolean isContasPrimeiro() {
        return contasPrimeiro;
    }

    public void definirDiasTrabalho(Set<DayOfWeek> dias) {
        if (dias.isEmpty()) {
            throw new RegraDeNegocioException("Marque ao menos um dia de trabalho.");
        }
        this.diasTrabalho = dias.size() == 7 ? null
                : dias.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    public Set<DayOfWeek> getDiasTrabalho() {
        if (diasTrabalho == null || diasTrabalho.isBlank()) {
            return EnumSet.allOf(DayOfWeek.class);
        }
        return Arrays.stream(diasTrabalho.split(",")).map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    /** Dias de trabalho do mês, pelos dias da semana configurados. */
    public int diasDeTrabalho(YearMonth mes) {
        return diasDeTrabalhoEntre(mes.atDay(1), mes.plusMonths(1).atDay(1));
    }

    /**
     * Quanto das contas do mês já devia estar guardado ao fim do dia: uma cota por dia trabalhado.
     * O dia de hoje conta mesmo fora da escala (abriu, trabalhou); o último dia de trabalho fecha o total.
     */
    public BigDecimal contasAte(BigDecimal contasDoMes, LocalDate dia) {
        int total = diasDeTrabalho(YearMonth.from(dia));
        int ate = Math.min(diasDeTrabalhoEntre(dia.withDayOfMonth(1), dia) + 1, total);
        if (total == 0 || ate >= total) {
            return contasDoMes;
        }
        return contasDoMes.multiply(BigDecimal.valueOf(ate)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private int diasDeTrabalhoEntre(LocalDate inicio, LocalDate fimExclusivo) {
        Set<DayOfWeek> dias = getDiasTrabalho();
        int n = 0;
        for (LocalDate d = inicio; d.isBefore(fimExclusivo); d = d.plusDays(1)) {
            if (dias.contains(d.getDayOfWeek())) {
                n++;
            }
        }
        return n;
    }

    public void atualizarTaxasPlataformas(BigDecimal ifood, BigDecimal noventaNove) {
        this.taxaIfood = entre0e100(ifood, "Taxa do iFood");
        this.taxaNoventaNove = entre0e100(noventaNove, "Taxa da 99Food");
    }

    public void definirRegime(RegimeTributario regime, BigDecimal limiteAnual, Instant agora) {
        if (regime == null) {
            throw new RegraDeNegocioException("Informe o regime (MEI ou ME).");
        }
        if (limiteAnual == null || limiteAnual.signum() <= 0) {
            throw new RegraDeNegocioException("Informe o limite de faturamento anual.");
        }
        this.regimeTributario = regime;
        this.limiteFaturamentoAnual = Dinheiro.centavos(limiteAnual);
        this.atualizadoEm = agora;
    }

    public RegimeTributario getRegimeTributario() {
        return regimeTributario;
    }

    public BigDecimal getLimiteFaturamentoAnual() {
        return limiteFaturamentoAnual;
    }

    public BigDecimal getTaxaIfood() {
        return taxaIfood;
    }

    public BigDecimal getTaxaNoventaNove() {
        return taxaNoventaNove;
    }

    public BigDecimal getPercentualReserva() {
        return CEM.subtract(percentualProLabore);
    }

    private static BigDecimal entre0e100(BigDecimal valor, String campo) {
        if (valor == null || valor.signum() < 0 || valor.compareTo(CEM) > 0) {
            throw new RegraDeNegocioException(campo + " deve estar entre 0 e 100.");
        }
        return valor;
    }

    public BigDecimal getPercentualContasFixas() {
        return percentualContasFixas;
    }

    public BigDecimal getPercentualProLabore() {
        return percentualProLabore;
    }

    public BigDecimal getTaxaPix() {
        return taxaPix;
    }

    public BigDecimal getTaxaDebito() {
        return taxaDebito;
    }

    public BigDecimal getTaxaCredito() {
        return taxaCredito;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }
}
