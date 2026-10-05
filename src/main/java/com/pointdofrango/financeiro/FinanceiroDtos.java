package com.pointdofrango.financeiro;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class FinanceiroDtos {

    private FinanceiroDtos() {
    }

    public record ConfiguracaoRequest(
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal percentualContasFixas,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal percentualProLabore,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxaPix,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxaDebito,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxaCredito,
            @DecimalMin("0") @DecimalMax("100") BigDecimal taxaIfood,
            @DecimalMin("0") @DecimalMax("100") BigDecimal taxaNoventaNove) {
    }

    public record RegimeRequest(@NotNull RegimeTributario regime,
                                @NotNull @DecimalMin("1") @DecimalMax("99999999") BigDecimal limiteFaturamentoAnual) {
    }

    public record ConfiguracaoResponse(BigDecimal percentualContasFixas, BigDecimal percentualProLabore,
                                       BigDecimal percentualReserva, BigDecimal taxaPix, BigDecimal taxaDebito,
                                       BigDecimal taxaCredito, BigDecimal taxaIfood, BigDecimal taxaNoventaNove,
                                       Instant atualizadoEm, RegimeTributario regimeTributario,
                                       BigDecimal limiteFaturamentoAnual) {

        public static ConfiguracaoResponse de(ConfiguracaoFinanceira c) {
            return new ConfiguracaoResponse(c.getPercentualContasFixas(), c.getPercentualProLabore(),
                    c.getPercentualReserva(), c.getTaxaPix(), c.getTaxaDebito(), c.getTaxaCredito(),
                    c.getTaxaIfood(), c.getTaxaNoventaNove(), c.getAtualizadoEm(), c.getRegimeTributario(),
                    c.getLimiteFaturamentoAnual());
        }
    }

    public record DespesaRequest(
            @NotBlank @Size(max = 100) String descricao,
            @NotNull @DecimalMin("0") BigDecimal valorMensal,
            @Min(1) @Max(31) Integer diaVencimento,
            Boolean ativa,
            Boolean valorVariavel) {

        public DespesaRequest(String descricao, BigDecimal valorMensal, Integer diaVencimento, Boolean ativa) {
            this(descricao, valorMensal, diaVencimento, ativa, false);
        }
    }

    public record DespesaResponse(Long id, String descricao, BigDecimal valorMensal, Integer diaVencimento,
                                  boolean ativa, boolean valorVariavel) {

        public static DespesaResponse de(DespesaFixa d) {
            return new DespesaResponse(d.getId(), d.getDescricao(), d.getValorMensal(), d.getDiaVencimento(),
                    d.isAtiva(), d.isValorVariavel());
        }
    }
}
