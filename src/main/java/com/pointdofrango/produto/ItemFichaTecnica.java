package com.pointdofrango.produto;

import com.pointdofrango.estoque.EmbalagemCompra;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.ModoQuantidade;
import com.pointdofrango.shared.Numeros;
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

/**
 * Guarda o que foi digitado (modo + quantidadeInformada, ex.: 12 iscas) e o valor convertido
 * para a unidade do insumo (quantidade, ex.: 1 kg), que é o usado na baixa.
 */
@Entity
@Table(name = "ficha_tecnica_item")
public class ItemFichaTecnica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_id")
    private Produto produto;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ModoQuantidade modo;

    @Column(nullable = false, precision = 16, scale = 6)
    private BigDecimal quantidadeInformada;

    /** Só no modo RENDIMENTO_EMBALAGEM. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embalagem_id")
    private EmbalagemCompra embalagem;

    /** Na unidade do insumo, 10 casas (ver Insumo.ESCALA_FICHA). */
    @Column(nullable = false, precision = 20, scale = 10)
    private BigDecimal quantidade;

    protected ItemFichaTecnica() {
    }

    ItemFichaTecnica(Produto produto, Insumo insumo) {
        this.produto = produto;
        this.insumo = insumo;
    }

    void definir(ModoQuantidade modo, BigDecimal informada, EmbalagemCompra embalagem) {
        this.modo = modo;
        this.quantidadeInformada = informada;
        this.embalagem = modo == ModoQuantidade.RENDIMENTO_EMBALAGEM ? embalagem : null;
        recalcular();
    }

    /** Chamado quando a conversão do insumo muda (ex.: de 12 para 14 iscas por kg). */
    void recalcular() {
        this.quantidade = insumo.converterParaBase(modo, quantidadeInformada, embalagem);
    }

    /** Pelo custo médio atual do insumo. */
    public BigDecimal custo() {
        return insumo.getCustoUnitario().multiply(quantidade);
    }

    /** Ex.: "300 g", "12 iscas", "rende 7 por Saco 2 kg". */
    public String descricao() {
        String n = Numeros.formatar(quantidadeInformada, 3);
        return switch (modo) {
            case UNIDADE_BASE -> n + " " + insumo.getUnidade().getSigla();
            case SUBUNIDADE -> n + " " + insumo.getUnidade().getSubunidade();
            case UNIDADE_USO -> n + " " + plural(insumo.getUnidadeUsoNome(), quantidadeInformada);
            case RENDIMENTO_EMBALAGEM -> "rende " + n + " por " + embalagem.getNome();
        };
    }

    private static String plural(String nome, BigDecimal qtd) {
        boolean umOuMenos = qtd.compareTo(BigDecimal.ONE) <= 0;
        boolean terminaEmVogal = nome.matches(".*[aeiou]$");
        return umOuMenos || !terminaEmVogal ? nome : nome + "s";
    }

    public Long getId() {
        return id;
    }

    public Produto getProduto() {
        return produto;
    }

    public Insumo getInsumo() {
        return insumo;
    }

    public ModoQuantidade getModo() {
        return modo;
    }

    public BigDecimal getQuantidadeInformada() {
        return quantidadeInformada;
    }

    public EmbalagemCompra getEmbalagem() {
        return embalagem;
    }

    public BigDecimal getQuantidade() {
        return quantidade;
    }
}
