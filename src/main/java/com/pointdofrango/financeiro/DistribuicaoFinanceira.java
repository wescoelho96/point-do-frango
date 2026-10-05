package com.pointdofrango.financeiro;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * Potes de uma venda, gravados junto do pedido. Lucro positivo se divide em pró-labore e reserva.
 * O valor bruto é derivado da soma dos potes, assim eles sempre fecham com o total.
 */
@Embeddable
public record DistribuicaoFinanceira(
        BigDecimal taxaPagamento,
        BigDecimal reposicaoEstoque,
        BigDecimal contasFixas,
        BigDecimal lucroLiquido,
        BigDecimal proLabore,
        BigDecimal reservaEmergencia) {

    public static final DistribuicaoFinanceira ZERO = new DistribuicaoFinanceira(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public BigDecimal valorBruto() {
        return taxaPagamento.add(reposicaoEstoque).add(contasFixas).add(lucroLiquido);
    }

    public boolean prejuizo() {
        return lucroLiquido.signum() < 0;
    }

    public DistribuicaoFinanceira somar(DistribuicaoFinanceira outra) {
        return new DistribuicaoFinanceira(
                taxaPagamento.add(outra.taxaPagamento),
                reposicaoEstoque.add(outra.reposicaoEstoque),
                contasFixas.add(outra.contasFixas),
                lucroLiquido.add(outra.lucroLiquido),
                proLabore.add(outra.proLabore),
                reservaEmergencia.add(outra.reservaEmergencia));
    }
}
