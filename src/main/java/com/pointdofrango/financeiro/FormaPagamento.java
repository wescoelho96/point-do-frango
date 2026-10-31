package com.pointdofrango.financeiro;

public enum FormaPagamento {
    DINHEIRO,
    PIX,
    DEBITO,
    CREDITO,
    /** iFood/99Food: o cliente paga no app e as taxas vêm no lançamento. */
    PAGO_NO_APP,
    /** Não entra dinheiro; o custo sai do lucro. */
    CORTESIA,
    /** Conta dividida em mais de uma forma; as partes ficam em pedido_pagamento. */
    DIVIDIDO;

    /** Formas que podem ser parte de uma conta dividida. */
    public boolean parteDeDivisao() {
        return this == DINHEIRO || this == PIX || this == DEBITO || this == CREDITO;
    }

    public boolean movimentaDinheiro() {
        return this != PAGO_NO_APP && this != CORTESIA;
    }
}
