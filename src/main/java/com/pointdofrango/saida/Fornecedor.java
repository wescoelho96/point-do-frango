package com.pointdofrango.saida;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "fornecedor")
public class Fornecedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nome;

    @Column(length = 100)
    private String fornece;

    @Column(length = 20)
    private String telefone;

    @Column(length = 20)
    private String documento;

    private String observacao;

    @Column(nullable = false)
    private boolean ativo = true;

    protected Fornecedor() {
    }

    public Fornecedor(String nome) {
        this.nome = nome.strip();
    }

    public void alterar(String nome, String fornece, String telefone, String documento, String observacao, boolean ativo) {
        this.nome = nome.strip();
        this.fornece = limpar(fornece);
        this.telefone = limpar(telefone);
        this.documento = limpar(documento);
        this.observacao = limpar(observacao);
        this.ativo = ativo;
    }

    private static String limpar(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getFornece() {
        return fornece;
    }

    public String getTelefone() {
        return telefone;
    }

    public String getDocumento() {
        return documento;
    }

    public String getObservacao() {
        return observacao;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
