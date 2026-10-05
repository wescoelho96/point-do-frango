package com.pointdofrango.pedido;

import java.util.Set;

/**
 * O estoque é baixado no lançamento (o pedido já nasce EM_PREPARO), pois é quando a cozinha usa os insumos.
 * Cancelar, de qualquer status, estorna o estoque e tira o pedido do financeiro.
 */
public enum StatusPedido {
    EM_PREPARO,
    PRONTO,
    ENTREGUE,
    CANCELADO;

    public boolean podeIrPara(StatusPedido destino) {
        return proximos().contains(destino);
    }

    private Set<StatusPedido> proximos() {
        return switch (this) {
            case EM_PREPARO -> Set.of(PRONTO, CANCELADO);
            case PRONTO -> Set.of(ENTREGUE, EM_PREPARO, CANCELADO);
            case ENTREGUE -> Set.of(CANCELADO);
            case CANCELADO -> Set.of();
        };
    }
}
