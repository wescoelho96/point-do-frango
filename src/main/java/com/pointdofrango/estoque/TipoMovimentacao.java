package com.pointdofrango.estoque;

public enum TipoMovimentacao {
    /** Compra. Recalcula o custo médio. */
    ENTRADA,
    /** Baixa pela ficha técnica ao lançar o pedido. */
    SAIDA_VENDA,
    /** Pedido cancelado. */
    ESTORNO_VENDA,
    /** Contagem física (perda, quebra, vencimento). */
    AJUSTE,
    /** Consumo da equipe, sem venda. */
    CONSUMO_INTERNO
}
