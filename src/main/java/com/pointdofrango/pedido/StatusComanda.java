package com.pointdofrango.pedido;

/** Só uma comanda ABERTA pode ser fechada ou cancelada; cancelar estorna o estoque de todos os pedidos. */
public enum StatusComanda {
    ABERTA,
    FECHADA,
    CANCELADA
}
