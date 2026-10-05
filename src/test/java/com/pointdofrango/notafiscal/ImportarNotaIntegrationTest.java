package com.pointdofrango.notafiscal;

import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.notafiscal.NotaFiscalService.Ligacao;
import com.pointdofrango.notafiscal.NotaFiscalService.Pagamento;
import com.pointdofrango.saida.RelatorioSaidasService;
import com.pointdofrango.saida.SaidaService;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ImportarNotaIntegrationTest {

    @Autowired NotaFiscalService notas;
    @Autowired EstoqueService estoque;
    @Autowired SaidaService saidas;
    @Autowired RelatorioSaidasService relatorio;

    @Test
    @DisplayName("Importa a nota: fardo de 6 vira 12 Cocas, sassami entra em kg, detergente é ignorado e o total vira saída")
    void importar() throws Exception {
        Insumo coca = estoque.criar(new InsumoRequest("Coca 2 L NF", UnidadeMedida.UNIDADE, bd("0"), bd("9"), bd("0")), "t");
        Insumo sassami = estoque.criar(new InsumoRequest("Sassami NF", UnidadeMedida.QUILOGRAMA, bd("0"), bd("17"), bd("0")), "t");
        String xml = LeitorNfeTest.xmlDeExemplo();

        var previa = notas.previa(xml);
        assertThat(previa.jaImportada()).isFalse();
        assertThat(previa.itens()).allSatisfy(i -> assertThat(i.insumoSugerido()).isNull());

        var resultado = notas.importar(xml, List.of(new Ligacao(1, coca.getId(), bd("6")),
                new Ligacao(2, sassami.getId(), bd("1")), new Ligacao(3, null, null)),
                new Pagamento(FormaPagamento.PIX, false), "dono");

        assertThat(resultado.entradas()).isEqualTo(2);
        assertThat(resultado.ignorados()).isEqualTo(1);
        Insumo cocaDepois = estoque.buscar(coca.getId());
        assertThat(cocaDepois.getEstoqueAtual()).isEqualByComparingTo("12");
        assertThat(cocaDepois.getCustoUnitario()).isEqualByComparingTo("9.15"); // 109,80 ÷ 12
        assertThat(estoque.buscar(sassami.getId()).getEstoqueAtual()).isEqualByComparingTo("3");

        var fornecedor = saidas.fornecedorPorCnpj("11222333000181").orElseThrow();
        assertThat(relatorio.lancamentos(YearMonth.of(2026, 10))).anySatisfy(l -> {
            assertThat(l.categoria()).isEqualTo(CategoriaSaida.INSUMOS);
            assertThat(l.fornecedorId()).isEqualTo(fornecedor.getId());
            assertThat(l.valor()).isEqualByComparingTo("164.80");
        });

        // mesma nota de novo: recusada; e os itens agora já vêm ligados
        assertThatThrownBy(() -> notas.importar(xml, List.of(new Ligacao(1, coca.getId(), bd("6"))), null, "dono"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("já foi importada");
        var deNovo = notas.previa(xml);
        assertThat(deNovo.jaImportada()).isTrue();
        assertThat(deNovo.itens().get(0).insumoSugerido()).isEqualTo(coca.getId());
        assertThat(deNovo.itens().get(0).fatorSugerido()).isEqualByComparingTo("6");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
