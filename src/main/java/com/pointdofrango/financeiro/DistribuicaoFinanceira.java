package com.pointdofrango.financeiro;

import com.pointdofrango.shared.Dinheiro;
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

    /** Tira do lucro o que vai para as contas fixas e refaz pró-labore e reserva. */
    public DistribuicaoFinanceira comContas(BigDecimal valor, BigDecimal percentualProLabore) {
        BigDecimal contas = contasFixas.add(valor);
        BigDecimal lucro = lucroLiquido.subtract(valor);
        BigDecimal proLabore = BigDecimal.ZERO.setScale(2);
        BigDecimal reserva = BigDecimal.ZERO.setScale(2);
        if (lucro.signum() > 0) {
            proLabore = Dinheiro.percentual(lucro, percentualProLabore);
            reserva = lucro.subtract(proLabore);
        }
        return new DistribuicaoFinanceira(taxaPagamento, reposicaoEstoque, contas, lucro, proLabore, reserva);
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
