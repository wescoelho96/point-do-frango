package com.pointdofrango.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Arredondamento de valores monetários em um único lugar. */
public final class Dinheiro {

    public static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_EVEN;
    private static final BigDecimal CEM = new BigDecimal("100");

    private Dinheiro() {
    }

    public static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(2, ARREDONDAMENTO);
    }

    /** Aplica um percentual (ex.: 20.00 = 20%) e arredonda para centavos. */
    public static BigDecimal percentual(BigDecimal valor, BigDecimal percentual) {
        return centavos(valor.multiply(percentual).divide(CEM, 8, ARREDONDAMENTO));
    }
}
