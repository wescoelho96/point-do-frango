package com.pointdofrango.saida;

import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueDtos.PagamentoCompra;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.saida.SaidaService.NovaSaida;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compra no crédito: entra na fatura do mês seguinte; parcelada, uma saída por mês. */
@SpringBootTest
@ActiveProfiles("test")
class ComprasParceladasIntegrationTest {

    private static final LocalDate COMPRA = LocalDate.of(2026, 1, 31);

    @Autowired SaidaService saidas;
    @Autowired SaidaRepository repositorio;
    @Autowired EstoqueService estoque;

    @Test
    @DisplayName("R$ 100 em 3x: 33,34 + 33,33 + 33,33, de fevereiro a abril, com o número da parcela")
    void tresVezes() {
        List<Saida> parcelas = saidas.lancarParcelado(compra("Fardo de refrigerante", "100", FormaPagamento.CREDITO), 3, "dono");

        assertThat(parcelas).extracting(Saida::getValor).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("33.34"), bd("33.33"), bd("33.33"));
        assertThat(parcelas).extracting(Saida::getData)
                .containsExactly(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
        assertThat(parcelas.get(1).getDescricao()).isEqualTo("Fardo de refrigerante (2/3)");
        assertThat(parcelas).extracting(Saida::getGrupoParcelas).containsOnly(parcelas.get(0).getGrupoParcelas());
    }

    @Test
    @DisplayName("Crédito à vista entra no mês seguinte; PIX fica na data da compra")
    void creditoAVista() {
        assertThat(saidas.lancarParcelado(compra("Óleo", "50", FormaPagamento.CREDITO), 1, "dono").get(0).getData())
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(saidas.lancarParcelado(compra("Óleo", "50", FormaPagamento.PIX), null, "dono").get(0).getData())
                .isEqualTo(COMPRA);
    }

    @Test
    @DisplayName("Remover uma parcela remove a compra inteira; só crédito pode ser parcelado")
    void removerEValidar() {
        List<Saida> parcelas = saidas.lancarParcelado(compra("Freezer", "1200", FormaPagamento.CREDITO), 6, "dono");

        saidas.remover(parcelas.get(3).getId(), "dono");

        assertThat(repositorio.findByGrupoParcelas(parcelas.get(0).getGrupoParcelas())).isEmpty();
        assertThatThrownBy(() -> saidas.lancarParcelado(compra("Freezer", "1200", FormaPagamento.DEBITO), 3, "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("crédito");
        assertThatThrownBy(() -> saidas.lancarParcelado(compra("Freezer", "1200", FormaPagamento.CREDITO), 13, "dono"))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Entrada de estoque paga no crédito em 2x: custo cheio no estoque, duas saídas")
    void entradaParcelada() {
        var insumo = estoque.criar(new InsumoRequest("Batata parcelada", UnidadeMedida.QUILOGRAMA, bd("0"), bd("10"), bd("0")), "dono");
        int antes = repositorio.findAll().size();

        var depois = estoque.registrarEntrada(insumo.getId(), new EntradaRequest(bd("10"), null, null, bd("150"), "Compra",
                new PagamentoCompra(FormaPagamento.CREDITO, false, null, 2)), "dono");

        assertThat(depois.getCustoUnitario()).isEqualByComparingTo("15");
        assertThat(repositorio.findAll()).hasSize(antes + 2);
    }

    private static NovaSaida compra(String descricao, String valor, FormaPagamento forma) {
        return new NovaSaida(COMPRA, CategoriaSaida.INSUMOS, descricao, bd(valor), forma, null, null, null);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
