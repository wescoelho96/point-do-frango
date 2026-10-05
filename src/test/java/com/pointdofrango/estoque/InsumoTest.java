package com.pointdofrango.estoque;

import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsumoTest {

    private Insumo frango() {
        Insumo i = new Insumo("Peito de frango", UnidadeMedida.QUILOGRAMA, bd("1"), bd("20"));
        i.darEntrada(bd("2"), bd("40")); // 2 kg a R$ 20
        return i;
    }

    @Test
    @DisplayName("Entrada recalcula o custo médio ponderado")
    void custoMedioPonderado() {
        Insumo i = frango();

        i.darEntrada(bd("3"), bd("75")); // 3 kg a R$ 25

        assertThat(i.getEstoqueAtual()).isEqualByComparingTo("5");
        assertThat(i.getCustoUnitario()).isEqualByComparingTo("23"); // (40 + 75) / 5
    }

    @Test
    @DisplayName("Baixa reduz o saldo e não mexe no custo")
    void baixa() {
        Insumo i = frango();

        i.baixar(bd("0.300"));

        assertThat(i.getEstoqueAtual()).isEqualByComparingTo("1.700");
        assertThat(i.getCustoUnitario()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("Não deixa o estoque ficar negativo")
    void naoFicaNegativo() {
        Insumo i = frango();

        assertThatThrownBy(() -> i.baixar(bd("2.001"))).isInstanceOf(RegraDeNegocioException.class);
        assertThat(i.getEstoqueAtual()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Contagem física gera a diferença (negativa = perda)")
    void ajuste() {
        Insumo i = frango();

        BigDecimal diferenca = i.ajustarPara(bd("1.5"));

        assertThat(diferenca).isEqualByComparingTo("-0.5");
        assertThat(i.getEstoqueAtual()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("Alerta de reposição quando atinge o mínimo")
    void abaixoDoMinimo() {
        Insumo i = frango();
        assertThat(i.abaixoDoMinimo()).isFalse();

        i.baixar(bd("1"));

        assertThat(i.abaixoDoMinimo()).isTrue();
    }

    @Test
    @DisplayName("Ficha em gramas: 300 g de um insumo em kg = 0,3 kg")
    void subunidade() {
        Insumo batata = new Insumo("Batata", UnidadeMedida.QUILOGRAMA, bd("1"), bd("9"));

        assertThat(batata.converterParaBase(ModoQuantidade.SUBUNIDADE, bd("300"), null)).isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("Unidade de uso: com 12 iscas por kg, 12 iscas = 1 kg e 1 isca = 1/12 kg")
    void unidadeDeUso() {
        Insumo isca = new Insumo("Isca de frango", UnidadeMedida.QUILOGRAMA, bd("1"), bd("30"));
        isca.definirUnidadeUso("Isca", bd("12"));

        assertThat(isca.converterParaBase(ModoQuantidade.UNIDADE_USO, bd("12"), null)).isEqualByComparingTo("1");
        assertThat(isca.converterParaBase(ModoQuantidade.UNIDADE_USO, bd("1"), null)).isEqualByComparingTo("0.0833333333");
        assertThat(isca.getUnidadeUsoNome()).isEqualTo("isca");
    }

    @Test
    @DisplayName("Rendimento: '7 porções por saco de 2 kg' = 2/7 kg por porção, e 7 porções zeram o saco")
    void rendimentoPorEmbalagem() {
        Insumo batata = new Insumo("Batata", UnidadeMedida.QUILOGRAMA, bd("1"), bd("9"));
        batata.definirEmbalagens(java.util.List.of(new Insumo.DadosEmbalagem(null, "Saco 2 kg", bd("2"))));
        EmbalagemCompra saco = batata.getEmbalagens().get(0);

        BigDecimal porPorcao = batata.converterParaBase(ModoQuantidade.RENDIMENTO_EMBALAGEM, bd("7"), saco);

        assertThat(porPorcao).isEqualByComparingTo("0.2857142857");
        assertThat(porPorcao.multiply(bd("7")).setScale(6, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Arredondamento não bloqueia a última porção: 3 porções de 2/3 kg saem de um saco de 2 kg")
    void ultimaPorcaoComArredondamento() {
        Insumo anel = new Insumo("Anel", UnidadeMedida.QUILOGRAMA, bd("0"), bd("30"));
        anel.darEntrada(bd("2"), bd("60"));
        BigDecimal porcao = bd("0.666667"); // 2 ÷ 3 arredondado para 6 casas

        anel.baixar(porcao);
        anel.baixar(porcao);
        assertThat(anel.possui(porcao)).isTrue(); // restam 0,666666: 1 mg a menos
        anel.baixar(porcao);

        assertThat(anel.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Modos inválidos são recusados com mensagem clara")
    void modosInvalidos() {
        Insumo lata = new Insumo("Refrigerante", UnidadeMedida.UNIDADE, bd("1"), bd("3"));

        assertThatThrownBy(() -> lata.converterParaBase(ModoQuantidade.SUBUNIDADE, bd("300"), null))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("subunidade");
        assertThatThrownBy(() -> lata.converterParaBase(ModoQuantidade.UNIDADE_USO, bd("1"), null))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("unidade de uso");
        assertThatThrownBy(() -> lata.converterParaBase(ModoQuantidade.RENDIMENTO_EMBALAGEM, bd("1"), null))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
