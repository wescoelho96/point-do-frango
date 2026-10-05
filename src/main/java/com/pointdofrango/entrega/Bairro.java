package com.pointdofrango.entrega;

import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "bairro")
public class Bairro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String nome;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal taxaEntrega;

    @Column(nullable = false)
    private boolean ativo = true;

    protected Bairro() {
    }

    public Bairro(String nome, BigDecimal taxaEntrega) {
        alterar(nome, taxaEntrega, true);
    }

    public void alterar(String nome, BigDecimal taxaEntrega, boolean ativo) {
        if (taxaEntrega == null || taxaEntrega.signum() < 0) {
            throw new RegraDeNegocioException("Taxa de entrega não pode ser negativa.");
        }
        this.nome = nome.trim();
        this.taxaEntrega = Dinheiro.centavos(taxaEntrega);
        this.ativo = ativo;
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public BigDecimal getTaxaEntrega() {
        return taxaEntrega;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
