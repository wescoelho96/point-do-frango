package com.pointdofrango.financeiro;

import com.pointdofrango.shared.Dinheiro;

import java.math.BigDecimal;

/**
 * Divide o valor de uma venda em potes.
 *
 * <pre>
 *  Venda de R$ 100,00 no crédito (taxa 4,98%), CMV R$ 30,00, contas 20%, pró-labore 80% do lucro:
 *  taxa 4,98 | reposição 30,00 | contas 20,00 | lucro 45,02 (pró-labore 36,02, reserva 9,00)
 * </pre>
 *
 * Lucro e reserva saem por subtração, então o arredondamento não perde nem cria centavos.
 */
public final class RateioFinanceiro {

    private RateioFinanceiro() {
    }

    public static DistribuicaoFinanceira calcular(BigDecimal valorBruto, BigDecimal cmv, FormaPagamento forma,
                                                  ConfiguracaoFinanceira config) {
        BigDecimal bruto = Dinheiro.centavos(valorBruto);
        return calcularComTaxa(bruto, cmv, Dinheiro.percentual(bruto, config.taxaPara(forma)), config);
    }

    /** Taxa já em reais: no iFood/99Food o desconto real vem do extrato do app, não de um percentual fixo. */
    public static DistribuicaoFinanceira calcularComTaxa(BigDecimal valorBruto, BigDecimal cmv, BigDecimal taxaEmReais,
                                                         ConfiguracaoFinanceira config) {
        BigDecimal bruto = Dinheiro.centavos(valorBruto);
        BigDecimal taxa = Dinheiro.centavos(taxaEmReais);
        BigDecimal reposicao = Dinheiro.centavos(cmv);
        BigDecimal contas = Dinheiro.percentual(bruto, config.getPercentualContasFixas());
        BigDecimal lucro = bruto.subtract(taxa).subtract(reposicao).subtract(contas);

        BigDecimal proLabore = BigDecimal.ZERO.setScale(2);
        BigDecimal reserva = BigDecimal.ZERO.setScale(2);
        if (lucro.signum() > 0) {
            proLabore = Dinheiro.percentual(lucro, config.getPercentualProLabore());
            reserva = lucro.subtract(proLabore);
        }
        // Lucro negativo não é dividido; o prejuízo fica visível em lucroLiquido.
        return new DistribuicaoFinanceira(taxa, reposicao, contas, lucro, proLabore, reserva);
    }
}
