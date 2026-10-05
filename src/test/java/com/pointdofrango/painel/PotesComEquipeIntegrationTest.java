package com.pointdofrango.painel;

import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.entrega.Colaborador.Funcao;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.financeiro.DistribuicaoFinanceira;
import com.pointdofrango.financeiro.FormaPagamento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** O que foi pago à equipe e ao motoboy sai do lucro antes de dividir pró-labore e reserva. */
@SpringBootTest
@ActiveProfiles("test")
class PotesComEquipeIntegrationTest {

    @Autowired PainelService painel;
    @Autowired CaixaService caixa;
    @Autowired EntregaCadastroService cadastros;

    @Test
    @DisplayName("Lucro R$ 300 − R$ 130 de equipe/entregas = R$ 170 → pró-labore 80% = R$ 136, reserva R$ 34")
    void descontoAntesDaDivisao() {
        var potes = new DistribuicaoFinanceira(bd("10"), bd("200"), bd("90"), bd("300"), bd("240"), bd("60"));

        DistribuicaoFinanceira finais = PainelService.descontarDoLucro(potes, bd("130"), bd("80"));

        assertThat(finais.lucroLiquido()).isEqualByComparingTo("170");
        assertThat(finais.proLabore()).isEqualByComparingTo("136.00");
        assertThat(finais.reservaEmergencia()).isEqualByComparingTo("34.00");
        assertThat(finais.reposicaoEstoque()).isEqualByComparingTo("200"); // os outros potes não mudam
    }

    @Test
    @DisplayName("Se a equipe custou mais que o lucro, não sobra pró-labore nem reserva (e o prejuízo aparece)")
    void prejuizo() {
        var potes = new DistribuicaoFinanceira(bd("0"), bd("50"), bd("20"), bd("100"), bd("80"), bd("20"));

        DistribuicaoFinanceira finais = PainelService.descontarDoLucro(potes, bd("130"), bd("80"));

        assertThat(finais.lucroLiquido()).isEqualByComparingTo("-30");
        assertThat(finais.proLabore()).isEqualByComparingTo("0");
        assertThat(finais.reservaEmergencia()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("No painel do dia, a diária paga no caixa entra em 'equipe' e reduz o lucro final")
    void diariaNoPainel() {
        LocalDate hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        var antes = painel.resumo(hoje, hoje).equipeEEntregas().equipe();
        var ajudante = cadastros.criarColaborador("Ajudante Painel", Funcao.FUNCIONARIO, bd("80"));
        if (caixa.atual().isEmpty()) {
            caixa.abrir(BigDecimal.ZERO, "teste");
        }
        caixa.pagarDiaria(ajudante.getId(), null, FormaPagamento.DINHEIRO, "caixa");

        var r = painel.resumo(hoje, hoje);

        assertThat(r.equipeEEntregas().equipe().subtract(antes)).isEqualByComparingTo("80.00");
        assertThat(r.potesFinais().lucroLiquido())
                .isEqualByComparingTo(r.potes().lucroLiquido().subtract(r.equipeEEntregas().descontadoDoLucro()));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
