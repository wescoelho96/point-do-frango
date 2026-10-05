package com.pointdofrango.produto;

import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Compre N de um produto e ganhe M de outro, nos dias escolhidos. */
@Entity
@Table(name = "promocao")
public class Promocao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_compra_id")
    private Produto produtoCompra;

    @Column(nullable = false)
    private int quantidadeCompra;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_brinde_id")
    private Produto produtoBrinde;

    @Column(nullable = false)
    private int quantidadeBrinde;

    /** Ex.: "FRIDAY,SATURDAY,SUNDAY". */
    @Column(nullable = false, length = 80)
    private String diasSemana;

    @Column(nullable = false)
    private boolean ativa = true;

    protected Promocao() {
    }

    public Promocao(String nome) {
        this.nome = nome.strip();
    }

    void alterar(String nome, Produto compra, int quantidadeCompra, Produto brinde, int quantidadeBrinde,
                 Set<DayOfWeek> dias, boolean ativa) {
        if (dias == null || dias.isEmpty()) {
            throw new RegraDeNegocioException("Escolha pelo menos um dia da semana.");
        }
        if (quantidadeCompra <= 0 || quantidadeBrinde <= 0) {
            throw new RegraDeNegocioException("As quantidades precisam ser maiores que zero.");
        }
        this.nome = nome.strip();
        this.produtoCompra = compra;
        this.quantidadeCompra = quantidadeCompra;
        this.produtoBrinde = brinde;
        this.quantidadeBrinde = quantidadeBrinde;
        this.diasSemana = dias.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
        this.ativa = ativa;
    }

    public boolean valeEm(DayOfWeek dia) {
        return ativa && getDias().contains(dia);
    }

    /** Só conta múltiplos completos de quantidadeCompra. */
    public int brindesPara(int quantidadeComprada) {
        return quantidadeComprada / quantidadeCompra * quantidadeBrinde;
    }

    public Set<DayOfWeek> getDias() {
        return Arrays.stream(diasSemana.split(",")).map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public Produto getProdutoCompra() {
        return produtoCompra;
    }

    public int getQuantidadeCompra() {
        return quantidadeCompra;
    }

    public Produto getProdutoBrinde() {
        return produtoBrinde;
    }

    public int getQuantidadeBrinde() {
        return quantidadeBrinde;
    }

    public boolean isAtiva() {
        return ativa;
    }
}
