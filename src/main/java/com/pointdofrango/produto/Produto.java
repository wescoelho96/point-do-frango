package com.pointdofrango.produto;

import com.pointdofrango.estoque.EmbalagemCompra;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.ModoQuantidade;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Item do cardápio. O custo não é digitado: vem da ficha técnica e do custo médio dos insumos. */
@Entity
@Table(name = "produto")
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nome;

    private String descricao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoriaProduto categoria;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precoVenda;

    @Column(nullable = false)
    private boolean ativo = true;

    /** Pedido só com itens sem preparo (bebidas) não passa pela cozinha. */
    @Column(nullable = false)
    private boolean vaiParaCozinha = true;

    /** Set, não List: no fetch com as embalagens, uma bag duplicaria o item para cada embalagem do insumo. */
    @OneToMany(mappedBy = "produto", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private Set<ItemFichaTecnica> fichaTecnica = new LinkedHashSet<>();

    protected Produto() {
    }

    public Produto(String nome, String descricao, CategoriaProduto categoria, BigDecimal precoVenda) {
        atualizarCadastro(nome, descricao, categoria, precoVenda);
    }

    public void atualizarCadastro(String nome, String descricao, CategoriaProduto categoria, BigDecimal precoVenda) {
        if (precoVenda == null || precoVenda.signum() <= 0) {
            throw new RegraDeNegocioException("Preço de venda deve ser maior que zero.");
        }
        this.nome = nome.trim();
        this.descricao = descricao;
        this.categoria = categoria;
        this.precoVenda = Dinheiro.centavos(precoVenda);
    }

    /** Linha da ficha como foi digitada; embalagem só no modo rendimento. */
    public record Componente(Insumo insumo, ModoQuantidade modo, BigDecimal quantidade, EmbalagemCompra embalagem) {
    }

    /**
     * Atualiza no lugar em vez de limpar e recriar: o Hibernate faz os INSERTs antes dos DELETEs
     * e a UNIQUE(produto, insumo) estouraria.
     */
    public void definirFichaTecnica(List<Componente> componentes) {
        if (componentes.isEmpty()) {
            throw new RegraDeNegocioException("A ficha técnica precisa de pelo menos um insumo (é dela que sai o custo).");
        }
        fichaTecnica.removeIf(item -> componentes.stream()
                .noneMatch(c -> c.insumo().getId().equals(item.getInsumo().getId())));
        for (Componente c : componentes) {
            ItemFichaTecnica item = fichaTecnica.stream()
                    .filter(i -> i.getInsumo().getId().equals(c.insumo().getId()))
                    .findFirst()
                    .orElseGet(() -> {
                        ItemFichaTecnica novo = new ItemFichaTecnica(this, c.insumo());
                        fichaTecnica.add(novo);
                        return novo;
                    });
            item.definir(c.modo(), c.quantidade(), c.embalagem());
        }
    }

    /** CMV unitário sem arredondar; arredonda só na resposta. */
    public BigDecimal custoAtual() {
        return fichaTecnica.stream().map(ItemFichaTecnica::custo).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Limitado pelo insumo mais escasso. */
    public int unidadesDisponiveis() {
        return fichaTecnica.stream()
                .mapToInt(item -> item.getInsumo().getEstoqueAtual().add(Insumo.TOLERANCIA)
                        .divide(item.getQuantidade(), 0, RoundingMode.FLOOR).intValue())
                .min()
                .orElse(0);
    }

    public void definirPreparo(boolean vaiParaCozinha) {
        this.vaiParaCozinha = vaiParaCozinha;
    }

    public boolean isVaiParaCozinha() {
        return vaiParaCozinha;
    }

    public void ativar() {
        this.ativo = true;
    }

    public void desativar() {
        this.ativo = false;
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getDescricao() {
        return descricao;
    }

    public CategoriaProduto getCategoria() {
        return categoria;
    }

    public BigDecimal getPrecoVenda() {
        return precoVenda;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public List<ItemFichaTecnica> getFichaTecnica() {
        return List.copyOf(fichaTecnica);
    }
}
