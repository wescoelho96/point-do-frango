package com.pointdofrango.estoque;

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

/** Como o insumo chega do fornecedor. Ex.: "Saco 2 kg" de batata = 2,000 kg. */
@Entity
@Table(name = "insumo_embalagem")
public class EmbalagemCompra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Column(nullable = false, length = 50)
    private String nome;

    /** Quanto vem dentro, na unidade do insumo. */
    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal conteudo;

    protected EmbalagemCompra() {
    }

    EmbalagemCompra(Insumo insumo, String nome, BigDecimal conteudo) {
        this.insumo = insumo;
        alterar(nome, conteudo);
    }

    void alterar(String nome, BigDecimal conteudo) {
        this.nome = nome.trim();
        this.conteudo = conteudo;
    }

    public Long getId() {
        return id;
    }

    public Insumo getInsumo() {
        return insumo;
    }

    public String getNome() {
        return nome;
    }

    public BigDecimal getConteudo() {
        return conteudo;
    }
}
