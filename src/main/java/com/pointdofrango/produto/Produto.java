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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    public record Componente(Insumo insumo, ModoQuantidade modo, BigDecimal quantidade, EmbalagemCompra embalagem,
                             boolean soParaViagem, Long alternativaDe) {

        public Componente(Insumo insumo, ModoQuantidade modo, BigDecimal quantidade, EmbalagemCompra embalagem,
                          boolean soParaViagem) {
            this(insumo, modo, quantidade, embalagem, soParaViagem, null);
        }
    }

    /** Quantos dá para fazer e, quando é zero ou pouco, qual insumo segura. */
    public record Disponibilidade(int unidades, String limitadoPor) {
    }

    /** Produto que só usa insumos sem trava (ou nenhum) não tem limite de estoque. */
    public static final int SEM_LIMITE = 999;

    /**
     * Atualiza no lugar em vez de limpar e recriar: o Hibernate faz os INSERTs antes dos DELETEs
     * e a UNIQUE(produto, insumo) estouraria.
     */
    public void definirFichaTecnica(List<Componente> componentes) {
        if (componentes.isEmpty()) {
            throw new RegraDeNegocioException("A ficha técnica precisa de pelo menos um insumo (é dela que sai o custo).");
        }
        Map<Long, Componente> principais = componentes.stream().filter(c -> c.alternativaDe() == null)
                .collect(Collectors.toMap(c -> c.insumo().getId(), c -> c));
        for (Componente c : componentes) {
            if (c.alternativaDe() != null && !principais.containsKey(c.alternativaDe())) {
                throw new RegraDeNegocioException(c.insumo().getNome()
                        + " é alternativa de um insumo que não está na ficha (ou que já é alternativa de outro).");
            }
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
            // A alternativa sai nas mesmas situações da linha que substitui
            boolean soParaViagem = c.alternativaDe() != null ? principais.get(c.alternativaDe()).soParaViagem() : c.soParaViagem();
            item.definir(c.modo(), c.quantidade(), c.embalagem(), soParaViagem, c.alternativaDe());
        }
    }

    /**
     * CMV unitário sem arredondar; arredonda só na resposta. Linha com alternativas entra pela
     * opção que a próxima venda usaria (a primeira com saldo).
     */
    public BigDecimal custoAtual() {
        return opcoesPorLinha().stream()
                .map(opcoes -> opcoes.stream().filter(o -> o.getInsumo().vezesQueCabe(o.getQuantidade()) > 0)
                        .findFirst().orElse(opcoes.get(0)).custo())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int unidadesDisponiveis() {
        return disponibilidade().unidades();
    }

    /** Limitado pelo insumo mais escasso; as alternativas de uma linha somam (dá para misturar caixas). */
    public Disponibilidade disponibilidade() {
        long menor = SEM_LIMITE;
        String limitadoPor = null;
        for (List<ItemFichaTecnica> opcoes : opcoesPorLinha()) {
            long cabe = 0;
            for (ItemFichaTecnica o : opcoes) {
                cabe = Math.min(SEM_LIMITE, cabe + Math.min(SEM_LIMITE, o.getInsumo().vezesQueCabe(o.getQuantidade())));
            }
            if (cabe < menor) {
                menor = cabe;
                limitadoPor = opcoes.stream().map(o -> o.getInsumo().getNome()).collect(Collectors.joining(" ou "));
            }
        }
        return new Disponibilidade((int) menor, limitadoPor);
    }

    /** Cada linha normal com as alternativas dela, na ordem da ficha. */
    private List<List<ItemFichaTecnica>> opcoesPorLinha() {
        return fichaTecnica.stream().filter(i -> !i.isAlternativa())
                .map(principal -> Stream.concat(Stream.of(principal), fichaTecnica.stream()
                        .filter(a -> principal.getInsumo().getId().equals(a.getAlternativaDe()))).toList())
                .toList();
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
