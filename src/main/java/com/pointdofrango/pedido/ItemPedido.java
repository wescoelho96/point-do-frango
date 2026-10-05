package com.pointdofrango.pedido;

import com.pointdofrango.produto.Produto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Nome, preço e custo são copiados no momento da venda, para que mudanças no cardápio
 * não alterem o histórico.
 */
@Entity
@Table(name = "pedido_item")
public class ItemPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id")
    private Pedido pedido;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_id")
    private Produto produto;

    @Column(nullable = false, length = 100)
    private String nomeProduto;

    @Column(nullable = false)
    private int quantidade;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precoUnitario;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal custoUnitario;

    /** Brinde de promoção: preço zero, mas o custo entra no CMV. */
    @Column(nullable = false)
    private boolean brinde;

    private String promocao;

    protected ItemPedido() {
    }

    ItemPedido(Pedido pedido, Produto produto, int quantidade, BigDecimal custoUnitario) {
        this.pedido = pedido;
        this.produto = produto;
        this.nomeProduto = produto.getNome();
        this.quantidade = quantidade;
        this.precoUnitario = produto.getPrecoVenda();
        this.custoUnitario = custoUnitario;
    }

    static ItemPedido brinde(Pedido pedido, Produto produto, int quantidade, BigDecimal custoUnitario, String promocao) {
        ItemPedido item = new ItemPedido(pedido, produto, quantidade, custoUnitario);
        item.precoUnitario = BigDecimal.ZERO.setScale(2);
        item.brinde = true;
        item.promocao = promocao;
        return item;
    }

    /** Cortesia: o estoque e o custo continuam contando. */
    void zerarPreco() {
        this.precoUnitario = BigDecimal.ZERO.setScale(2);
    }

    public boolean isBrinde() {
        return brinde;
    }

    public String getPromocao() {
        return promocao;
    }

    public BigDecimal subtotal() {
        return precoUnitario.multiply(BigDecimal.valueOf(quantidade));
    }

    public BigDecimal custoTotal() {
        return custoUnitario.multiply(BigDecimal.valueOf(quantidade));
    }

    public Long getId() {
        return id;
    }

    public Long getProdutoId() {
        return produto.getId();
    }

    public String getNomeProduto() {
        return nomeProduto;
    }

    public int getQuantidade() {
        return quantidade;
    }

    public BigDecimal getPrecoUnitario() {
        return precoUnitario;
    }

    public BigDecimal getCustoUnitario() {
        return custoUnitario;
    }
}
