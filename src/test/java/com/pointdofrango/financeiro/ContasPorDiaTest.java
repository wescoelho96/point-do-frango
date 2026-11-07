package com.pointdofrango.financeiro;

import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContasPorDiaTest {

    // novembro de 2026: começa num domingo, 30 dias, 5 domingos e 5 segundas
    private static final YearMonth NOVEMBRO = YearMonth.of(2026, 11);
    private static final BigDecimal CONTAS = new BigDecimal("1824");

    private final ConfiguracaoFinanceira config = new ConfiguracaoFinanceira(new BigDecimal("20"), new BigDecimal("80"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.EPOCH);

    @Test
    @DisplayName("Sem escala definida, todo dia é de trabalho")
    void todosOsDias() {
        assertThat(config.getDiasTrabalho()).hasSize(7);
        assertThat(config.diasDeTrabalho(NOVEMBRO)).isEqualTo(30);
        assertThat(config.contasAte(CONTAS, NOVEMBRO.atDay(1))).isEqualByComparingTo("60.80");
        assertThat(config.contasAte(CONTAS, NOVEMBRO.atDay(15))).isEqualByComparingTo("912.00");
        assertThat(config.contasAte(CONTAS, NOVEMBRO.atDay(30))).isEqualByComparingTo("1824");
    }

    @Test
    @DisplayName("Fechado na segunda: a cota cresce só nos dias de trabalho")
    void folgaNaSegunda() {
        config.definirDiasTrabalho(EnumSet.complementOf(EnumSet.of(DayOfWeek.MONDAY)));
        assertThat(config.diasDeTrabalho(NOVEMBRO)).isEqualTo(25);
        // domingo 1º: primeira cota (1824 / 25 = 72,96)
        assertThat(config.contasAte(CONTAS, NOVEMBRO.atDay(1))).isEqualByComparingTo("72.96");
        // terça 3: domingo + terça (a segunda 2 não conta)
        assertThat(config.contasAte(CONTAS, NOVEMBRO.atDay(3))).isEqualByComparingTo("145.92");
        // abriu na segunda 2, fora da escala: o dia trabalhado conta
        assertThat(config.contasAte(CONTAS, NOVEMBRO.atDay(2))).isEqualByComparingTo("145.92");
        // último dia de trabalho fecha o total
        assertThat(config.contasAte(CONTAS, LocalDate.of(2026, 11, 29))).isEqualByComparingTo("1824");
        assertThat(config.contasAte(CONTAS, LocalDate.of(2026, 11, 30))).isEqualByComparingTo("1824");
    }

    @Test
    @DisplayName("A escala precisa de ao menos um dia")
    void semDias() {
        assertThatThrownBy(() -> config.definirDiasTrabalho(EnumSet.noneOf(DayOfWeek.class)))
                .isInstanceOf(RegraDeNegocioException.class);
    }
}
