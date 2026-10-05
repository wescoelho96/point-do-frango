package com.pointdofrango.produto;

import java.math.BigDecimal;

/** Projeção da ficha técnica: quanto de cada insumo um produto consome. */
public record ConsumoFicha(Long produtoId, Long insumoId, BigDecimal quantidade) {
}
