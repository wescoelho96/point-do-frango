package com.pointdofrango.financeiro;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RateioFinanceiroTest {

    // contas 20% do faturamento, pró-labore 80% do lucro, PIX 0%, débito 1,99%, crédito 4,98%
    private final ConfiguracaoFinanceira config = new ConfiguracaoFinanceira(
            bd("20"), bd("80"), bd("0"), bd("1.99"), bd("4.98"), Instant.EPOCH);

    @Test
    @DisplayName("Exemplo da documentação: R$ 100 no crédito com CMV de R$ 30")
    void exemploDocumentado() {
        DistribuicaoFinanceira d = RateioFinanceiro.calcular(bd("100"), bd("30"), FormaPagamento.CREDITO, config);

        assertThat(d.taxaPagamento()).isEqualByComparingTo("4.98");
        assertThat(d.reposicaoEstoque()).isEqualByComparingTo("30.00");
        assertThat(d.contasFixas()).isEqualByComparingTo("20.00");
        assertThat(d.lucroLiquido()).isEqualByComparingTo("45.02");
        assertThat(d.proLabore()).isEqualByComparingTo("36.02");
        assertThat(d.reservaEmergencia()).isEqualByComparingTo("9.00");
    }

    @Test
    @DisplayName("Venda de R$ 300 no PIX: exemplo da conversa original")
    void vendaDe300NoPix() {
        DistribuicaoFinanceira d = RateioFinanceiro.calcular(bd("300"), bd("105"), FormaPagamento.PIX, config);

        assertThat(d.taxaPagamento()).isEqualByComparingTo("0");
        assertThat(d.reposicaoEstoque()).isEqualByComparingTo("105.00");
        assertThat(d.contasFixas()).isEqualByComparingTo("60.00");
        assertThat(d.proLabore()).isEqualByComparingTo("108.00");
        assertThat(d.reservaEmergencia()).isEqualByComparingTo("27.00");
    }

    @ParameterizedTest(name = "bruto={0} cmv={1} {2}")
    @CsvSource({
            "0.01, 0, CREDITO",
            "33.33, 11.111111, DEBITO",
            "47.77, 13.456789, CREDITO",
            "999.99, 333.333333, PIX",
            "12.00, 20.00, DINHEIRO"
    })
    @DisplayName("Os potes sempre somam exatamente o valor vendido (nenhum centavo some ou aparece)")
    void potesFecham(String bruto, String cmv, FormaPagamento forma) {
        DistribuicaoFinanceira d = RateioFinanceiro.calcular(bd(bruto), bd(cmv), forma, config);

        assertThat(d.valorBruto()).isEqualByComparingTo(bruto);
        if (!d.prejuizo()) {
            assertThat(d.proLabore().add(d.reservaEmergencia())).isEqualByComparingTo(d.lucroLiquido());
        }
    }

    @Test
    @DisplayName("Vendido abaixo do custo: lucro negativo aparece e nada vai para pró-labore/reserva")
    void prejuizo() {
        DistribuicaoFinanceira d = RateioFinanceiro.calcular(bd("10"), bd("9"), FormaPagamento.DINHEIRO, config);

        assertThat(d.prejuizo()).isTrue();
        assertThat(d.lucroLiquido()).isEqualByComparingTo("-1.00");
        assertThat(d.proLabore()).isEqualByComparingTo("0");
        assertThat(d.reservaEmergencia()).isEqualByComparingTo("0");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
