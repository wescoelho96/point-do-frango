package com.pointdofrango.produto;

import java.math.BigDecimal;

/** Projeção da ficha técnica: quanto de cada insumo um produto consome. */
/** soParaViagem: caixa e sacola, que não saem quando o cliente come na mesa. */
public record ConsumoFicha(Long produtoId, Long insumoId, BigDecimal quantidade, boolean soParaViagem) {
}
