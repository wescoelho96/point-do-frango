package com.pointdofrango.caixa;

import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
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

@Entity
@Table(name = "caixa_movimento")
public class MovimentoCaixa {

    public enum Tipo { SAIDA, SUPRIMENTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "caixa_id")
    private Caixa caixa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Tipo tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoriaSaida categoria;

    @Column(nullable = false, length = 150)
    private String descricao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FormaPagamento forma;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "colaborador_id")
    private Colaborador colaborador;

    @Column(nullable = false)
    private boolean incluiDiaria;

    /** Só o id: o caixa não depende do cadastro de fornecedores. */
    @Column(name = "fornecedor_id")
    private Long fornecedorId;

    @Column(nullable = false)
    private Instant criadoEm;

    @Column(nullable = false, length = 50)
    private String criadoPor;

    protected MovimentoCaixa() {
    }

    MovimentoCaixa(Caixa caixa, Tipo tipo, CategoriaSaida categoria, String descricao, BigDecimal valor,
                   FormaPagamento forma, Colaborador colaborador, boolean incluiDiaria, String usuario, Instant agora) {
        this.caixa = caixa;
        this.tipo = tipo;
        this.categoria = categoria;
        this.descricao = descricao;
        this.valor = valor;
        this.forma = forma;
        this.colaborador = colaborador;
        this.incluiDiaria = incluiDiaria;
        this.criadoPor = usuario;
        this.criadoEm = agora;
    }

    void definirFornecedor(Long fornecedorId) {
        this.fornecedorId = fornecedorId;
    }

    public boolean saida() {
        return tipo == Tipo.SAIDA;
    }

    public boolean emDinheiro() {
        return forma == FormaPagamento.DINHEIRO;
    }

    public Long getId() {
        return id;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public CategoriaSaida getCategoria() {
        return categoria;
    }

    public String getDescricao() {
        return descricao;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public FormaPagamento getForma() {
        return forma;
    }

    public Colaborador getColaborador() {
        return colaborador;
    }

    public Long getFornecedorId() {
        return fornecedorId;
    }

    public boolean isIncluiDiaria() {
        return incluiDiaria;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public String getCriadoPor() {
        return criadoPor;
    }
}
