package com.pointdofrango.estoque;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Linha do extrato do estoque. Nunca é editada nem apagada, para o saldo sempre ter explicação.
 * Quantidade positiva entrou, negativa saiu.
 */
@Entity
@Table(name = "movimentacao_estoque")
public class MovimentacaoEstoque {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoMovimentacao tipo;

    @Column(nullable = false, precision = 16, scale = 6)
    private BigDecimal quantidade;

    @Column(nullable = false, precision = 16, scale = 6)
    private BigDecimal saldoApos;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal custoUnitario;

    /** Só o id, para o estoque não depender do módulo de pedidos. */
    @Column(name = "pedido_id")
    private Long pedidoId;

    private String observacao;

    private String usuario;

    @Column(nullable = false)
    private Instant criadoEm;

    protected MovimentacaoEstoque() {
    }

    MovimentacaoEstoque(Insumo insumo, TipoMovimentacao tipo, BigDecimal quantidade, Long pedidoId,
                        String observacao, String usuario, Instant criadoEm) {
        this.insumo = insumo;
        this.tipo = tipo;
        this.quantidade = quantidade;
        this.saldoApos = insumo.getEstoqueAtual();
        this.custoUnitario = insumo.getCustoUnitario();
        this.pedidoId = pedidoId;
        this.observacao = observacao;
        this.usuario = usuario;
        this.criadoEm = criadoEm;
    }

    public Long getId() {
        return id;
    }

    public Insumo getInsumo() {
        return insumo;
    }

    public TipoMovimentacao getTipo() {
        return tipo;
    }

    public BigDecimal getQuantidade() {
        return quantidade;
    }

    public BigDecimal getSaldoApos() {
        return saldoApos;
    }

    public BigDecimal getCustoUnitario() {
        return custoUnitario;
    }

    public Long getPedidoId() {
        return pedidoId;
    }

    public String getObservacao() {
        return observacao;
    }

    public String getUsuario() {
        return usuario;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
