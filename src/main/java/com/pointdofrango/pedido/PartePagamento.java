package com.pointdofrango.pedido;

import com.pointdofrango.financeiro.FormaPagamento;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;

/** Uma parte da conta dividida: ex.: R$ 18,75 no PIX. */
@Embeddable
public class PartePagamento {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FormaPagamento forma;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valor;

    protected PartePagamento() {
    }

    public PartePagamento(FormaPagamento forma, BigDecimal valor) {
        this.forma = forma;
        this.valor = valor;
    }

    public FormaPagamento getForma() {
        return forma;
    }

    public BigDecimal getValor() {
        return valor;
    }
}
