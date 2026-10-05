package com.pointdofrango.financeiro;

public enum FormaPagamento {
    DINHEIRO,
    PIX,
    DEBITO,
    CREDITO,
    /** iFood/99Food: o cliente paga no app e as taxas vêm no lançamento. */
    PAGO_NO_APP,
    /** Não entra dinheiro; o custo sai do lucro. */
    CORTESIA;

    public boolean movimentaDinheiro() {
        return this != PAGO_NO_APP && this != CORTESIA;
    }
}
