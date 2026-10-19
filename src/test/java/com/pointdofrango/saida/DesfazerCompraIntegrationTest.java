package com.pointdofrango.saida;

import com.pointdofrango.estoque.ControleEstoqueService;
import com.pointdofrango.estoque.EstoqueDtos.CompraRequest;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueDtos.ItemCompra;
import com.pointdofrango.estoque.EstoqueDtos.PagamentoCompra;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compra lançada em dobro: desfazer tira o que entrou no estoque, sem virar perda, e remove o pagamento. */
@SpringBootTest
@ActiveProfiles("test")
class DesfazerCompraIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EstoqueService estoque;
    @Autowired ControleEstoqueService controle;
    @Autowired SaidaRepository saidas;
    @Autowired DesfazerCompraService desfazer;

    @Test
    @DisplayName("Compra com vários itens no crédito: estoque volta ao que era e o pagamento sai")
    void compraComVariosItens() {
        Insumo iscas = estoque.criar(new InsumoRequest("Iscas dobro " + SEQ.incrementAndGet(), UnidadeMedida.QUILOGRAMA,
                bd("0"), bd("20"), bd("4")), "t");
        String observacao = "Alvorada dobro " + SEQ.get();
        estoque.registrarCompra(new CompraRequest(List.of(new ItemCompra(iscas.getId(), bd("1.872"), null, null, bd("46.61"))),
                observacao, new PagamentoCompra(FormaPagamento.CREDITO, false, null, 1)), "dono");
        Saida pagamento = saidas.findAll().stream().filter(s -> s.getDescricao().startsWith(observacao)).findFirst().orElseThrow();

        var r = desfazer.desfazer(pagamento.getId(), "dono");

        assertThat(r.entradas()).isEqualTo(1);
        assertThat(estoque.buscar(iscas.getId()).getEstoqueAtual()).isEqualByComparingTo("4");
        assertThat(saidas.findById(pagamento.getId())).isEmpty();
        assertThat(controle.furo(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)))
                .noneMatch(f -> f.insumoId().equals(iscas.getId()));
    }

    @Test
    @DisplayName("Entrada avulsa parcelada: desfazer remove todas as parcelas; pagamento que não é de compra é recusado")
    void entradaAvulsaParcelada() {
        Insumo farinha = estoque.criar(new InsumoRequest("Farinha dobro " + SEQ.incrementAndGet(), UnidadeMedida.QUILOGRAMA,
                bd("0"), bd("2.7"), bd("0")), "t");
        estoque.registrarEntrada(farinha.getId(), new EntradaRequest(bd("5"), null, null, bd("13.69"), null,
                new PagamentoCompra(FormaPagamento.CREDITO, false, null, 3)), "dono");
        List<Saida> parcelas = saidas.findAll().stream().filter(s -> s.getDescricao().contains(farinha.getNome())).toList();
        assertThat(parcelas).hasSize(3);

        desfazer.desfazer(parcelas.get(1).getId(), "dono");

        assertThat(estoque.buscar(farinha.getId()).getEstoqueAtual()).isEqualByComparingTo("0");
        assertThat(saidas.findAll()).noneMatch(s -> s.getDescricao().contains(farinha.getNome()));
        Saida diaria = saidas.save(new Saida(LocalDate.now(), com.pointdofrango.financeiro.CategoriaSaida.FUNCIONARIO, "Diária teste",
                bd("80"), FormaPagamento.PIX, "dono", java.time.Instant.now()));
        assertThatThrownBy(() -> desfazer.desfazer(diaria.getId(), "dono")).isInstanceOf(RegraDeNegocioException.class);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
