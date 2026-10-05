package com.pointdofrango.entrega;

import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Quem recebe por dia trabalhado: motoboy (diária + taxas das entregas) ou funcionário (só diária). */
@Entity
@Table(name = "colaborador")
public class Colaborador {

    public enum Funcao { MOTOBOY, FUNCIONARIO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Funcao funcao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorDiaria;

    @Column(nullable = false)
    private boolean ativo = true;

    protected Colaborador() {
    }

    public Colaborador(String nome, Funcao funcao, BigDecimal valorDiaria) {
        alterar(nome, funcao, valorDiaria, true);
    }

    public void alterar(String nome, Funcao funcao, BigDecimal valorDiaria, boolean ativo) {
        if (valorDiaria == null || valorDiaria.signum() < 0) {
            throw new RegraDeNegocioException("Valor da diária não pode ser negativo.");
        }
        this.nome = nome.trim();
        this.funcao = funcao;
        this.valorDiaria = Dinheiro.centavos(valorDiaria);
        this.ativo = ativo;
    }

    public boolean motoboy() {
        return funcao == Funcao.MOTOBOY;
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public Funcao getFuncao() {
        return funcao;
    }

    public BigDecimal getValorDiaria() {
        return valorDiaria;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
