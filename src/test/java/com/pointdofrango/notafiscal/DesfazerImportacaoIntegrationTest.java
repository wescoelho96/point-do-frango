package com.pointdofrango.notafiscal;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.notafiscal.NotaFiscalService.Ligacao;
import com.pointdofrango.notafiscal.NotaFiscalService.Pagamento;
import com.pointdofrango.saida.RelatorioSaidasService;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DesfazerImportacaoIntegrationTest {

    @Autowired NotaFiscalService notas;
    @Autowired NotaFiscalCompraRepository repositorio;
    @Autowired EstoqueService estoque;
    @Autowired RelatorioSaidasService relatorio;

    /** A nota de exemplo com outra chave, para não colidir com a importada em outro teste. */
    private static String outraNota() throws IOException {
        String sem = "3526101122233300018155001000012399112345678";
        return LeitorNfeTest.xmlDeExemplo().replace(LeitorNfeTest.CHAVE, sem + LeitorNfe.digitoChave(sem))
                .replace("<nNF>12345</nNF>", "<nNF>12399</nNF>");
    }

    @Test
    @DisplayName("Desfazer: estoque e custo voltam, item criado só pela nota some, pagamento sai e a nota pode ser importada de novo")
    void desfazer() throws IOException {
        Insumo coca = estoque.criar(new InsumoRequest("Coca desfazer", UnidadeMedida.UNIDADE, bd("0"), bd("8"), bd("5")), "t");
        Insumo sassami = estoque.criar(new InsumoRequest("Sassami desfazer", UnidadeMedida.QUILOGRAMA, bd("0"), bd("0"), bd("0")), "t");
        String xml = outraNota();
        BigDecimal saidasAntes = totalDeSaidas();

        notas.importar(xml, null, List.of(new Ligacao(1, coca.getId(), bd("6")), new Ligacao(2, sassami.getId(), bd("1"))),
                new Pagamento(FormaPagamento.PIX, false), "dono");
        assertThat(estoque.buscar(coca.getId()).getEstoqueAtual()).isEqualByComparingTo("17");
        assertThat(totalDeSaidas().subtract(saidasAntes)).isEqualByComparingTo("164.80");
        NotaFiscalCompra importada = repositorio.findTop30ByOrderByImportadaEmDesc().stream()
                .filter(n -> n.getNumero().equals("12399")).findFirst().orElseThrow();

        var r = notas.desfazer(importada.getId(), "dono");

        assertThat(r.entradas()).isEqualTo(2);
        assertThat(r.insumosRemovidos()).isEqualTo(1);
        Insumo cocaDepois = estoque.buscar(coca.getId());
        assertThat(cocaDepois.getEstoqueAtual()).isEqualByComparingTo("5");
        assertThat(cocaDepois.getCustoUnitario()).isEqualByComparingTo("8");
        assertThatThrownBy(() -> estoque.buscar(sassami.getId())).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThat(totalDeSaidas()).isEqualByComparingTo(saidasAntes);
        assertThat(notas.previa(xml).jaImportada()).isFalse();
    }

    private BigDecimal totalDeSaidas() {
        return relatorio.lancamentos(YearMonth.of(2026, 10)).stream().map(l -> l.valor()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
