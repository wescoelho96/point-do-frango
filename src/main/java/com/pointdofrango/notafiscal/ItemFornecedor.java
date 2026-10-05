package com.pointdofrango.notafiscal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Liga o código do produto do fornecedor a um insumo, com o fator de conversão.
 * Gravado na importação e sugerido nas próximas notas do mesmo fornecedor.
 */
@Entity
@Table(name = "item_fornecedor")
public class ItemFornecedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fornecedor_id", nullable = false)
    private Long fornecedorId;

    @Column(nullable = false, length = 60)
    private String codigoProduto;

    @Column(nullable = false, length = 150)
    private String descricao;

    @Column(name = "insumo_id", nullable = false)
    private Long insumoId;

    /** Unidades do insumo por unidade da nota (fardo com 6 = 6). */
    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal fator;

    protected ItemFornecedor() {
    }

    ItemFornecedor(Long fornecedorId, String codigoProduto) {
        this.fornecedorId = fornecedorId;
        this.codigoProduto = codigoProduto;
    }

    void ligar(String descricao, Long insumoId, BigDecimal fator) {
        this.descricao = descricao.length() > 150 ? descricao.substring(0, 150) : descricao;
        this.insumoId = insumoId;
        this.fator = fator;
    }

    public String getCodigoProduto() {
        return codigoProduto;
    }

    public Long getInsumoId() {
        return insumoId;
    }

    public BigDecimal getFator() {
        return fator;
    }
}
