package com.pointdofrango.estoque;

import com.pointdofrango.estoque.EstoqueDtos.PagamentoCompra;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Publicado na entrada de uma compra que já foi paga. O módulo de saídas escuta e lança o
 * pagamento (no caixa ou por fora) na mesma transação: se o caixa estiver fechado, a entrada
 * inteira é desfeita.
 */
public record CompraDeInsumoPaga(String descricao, BigDecimal valor, PagamentoCompra pagamento, String usuario,
                                 LocalDate data) {

    public CompraDeInsumoPaga(String descricao, BigDecimal valor, PagamentoCompra pagamento, String usuario) {
        this(descricao, valor, pagamento, usuario, null);
    }
}
