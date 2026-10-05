package com.pointdofrango.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formatação de quantidades para textos ("300 g", "6,7 porções"). */
public final class Numeros {

    private Numeros() {
    }

    /** Até {@code casas} decimais, sem zeros à direita e com vírgula (0.300 vira "0,3"). */
    public static String formatar(BigDecimal valor, int casas) {
        BigDecimal v = valor.setScale(casas, RoundingMode.HALF_UP).stripTrailingZeros();
        if (v.scale() < 0) {
            v = v.setScale(0);
        }
        return v.toPlainString().replace('.', ',');
    }
}
