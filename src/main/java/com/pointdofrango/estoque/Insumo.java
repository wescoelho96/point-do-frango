package com.pointdofrango.estoque;

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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Matéria-prima (frango, batata, óleo, embalagem). Saldo e custo só mudam pelos métodos de negócio. */
@Entity
@Table(name = "insumo")
public class Insumo {

    private static final int ESCALA_QTD = 6;
    /** 10 casas na ficha para 12 x (1/12 kg) voltar a dar exatamente 1 kg. */
    public static final int ESCALA_FICHA = 10;
    private static final BigDecimal MIL = new BigDecimal("1000");
    /**
     * Frações como 2/7 kg não fecham com 6 casas e deixam sobra ou falta de 0,000001 no saco.
     * Diferenças abaixo de 0,00001 (10 mg / 0,01 ml) contam como zero para a última porção sair.
     */
    public static final BigDecimal TOLERANCIA = new BigDecimal("0.00001");
    private static final int ESCALA_CUSTO = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UnidadeMedida unidade;

    @Column(nullable = false, precision = 16, scale = 6)
    private BigDecimal estoqueAtual = BigDecimal.ZERO;

    @Column(nullable = false, precision = 16, scale = 6)
    private BigDecimal estoqueMinimo = BigDecimal.ZERO;

    /** Custo de 1 kg, 1 L ou 1 un, por custo médio ponderado. */
    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal custoUnitario = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean ativo = true;

    /** Como a cozinha conta o insumo, ex.: "isca", 12 por kg. */
    @Column(length = 30)
    private String unidadeUsoNome;

    @Column(precision = 14, scale = 6)
    private BigDecimal unidadeUsoPorUnidade;

    @Column(length = 40)
    private String grupo;

    @Column(length = 50)
    private String marca;

    @OneToMany(mappedBy = "insumo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("conteudo DESC")
    private Set<EmbalagemCompra> embalagens = new LinkedHashSet<>();

    /** Lock otimista: duas edições simultâneas não se sobrescrevem em silêncio. */
    @Version
    private Long versao;

    protected Insumo() {
    }

    public Insumo(String nome, UnidadeMedida unidade, BigDecimal estoqueMinimo, BigDecimal custoUnitario) {
        atualizarCadastro(nome, unidade, estoqueMinimo, custoUnitario);
    }

    public void atualizarCadastro(String nome, UnidadeMedida unidade, BigDecimal estoqueMinimo, BigDecimal custoUnitario) {
        this.nome = nome.trim();
        this.unidade = unidade;
        this.estoqueMinimo = naoNegativo(estoqueMinimo, "Estoque mínimo").setScale(ESCALA_QTD, RoundingMode.HALF_UP);
        this.custoUnitario = naoNegativo(custoUnitario, "Custo").setScale(ESCALA_CUSTO, RoundingMode.HALF_UP);
    }

    /** Nome em branco remove a unidade de uso. ("isca", 12) = 12 iscas por kg. */
    public void definirUnidadeUso(String nome, BigDecimal porUnidade) {
        if (nome == null || nome.isBlank()) {
            this.unidadeUsoNome = null;
            this.unidadeUsoPorUnidade = null;
            return;
        }
        if (porUnidade == null || porUnidade.signum() <= 0) {
            throw new RegraDeNegocioException("Informe quantas " + nome.trim() + " vêm em 1 " + unidade.getSigla() + ".");
        }
        this.unidadeUsoNome = nome.trim().toLowerCase();
        this.unidadeUsoPorUnidade = porUnidade;
    }

    public void classificar(String grupo, String marca) {
        this.grupo = vazioParaNulo(grupo);
        this.marca = vazioParaNulo(marca);
    }

    private static String vazioParaNulo(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    public record DadosEmbalagem(Long id, String nome, BigDecimal conteudo) {
    }

    /** Sincroniza as embalagens e devolve os ids removidos, para produtos conferir se alguma ficha usa. */
    public Set<Long> definirEmbalagens(List<DadosEmbalagem> dados) {
        Set<String> nomes = new HashSet<>();
        for (DadosEmbalagem d : dados) {
            if (d.nome() == null || d.nome().isBlank()) {
                throw new RegraDeNegocioException("Informe o nome da embalagem (ex.: Saco 2 kg).");
            }
            if (d.conteudo() == null || d.conteudo().signum() <= 0) {
                throw new RegraDeNegocioException("Conteúdo da embalagem \"" + d.nome() + "\" deve ser maior que zero.");
            }
            if (!nomes.add(d.nome().trim().toLowerCase())) {
                throw new RegraDeNegocioException("Embalagem repetida: " + d.nome());
            }
        }
        Set<Long> idsMantidos = new HashSet<>();
        dados.stream().map(DadosEmbalagem::id).filter(Objects::nonNull).forEach(idsMantidos::add);

        Set<Long> removidas = new HashSet<>();
        embalagens.removeIf(e -> {
            boolean remover = e.getId() == null || !idsMantidos.contains(e.getId());
            if (remover && e.getId() != null) {
                removidas.add(e.getId());
            }
            return remover;
        });
        for (DadosEmbalagem d : dados) {
            if (d.id() == null) {
                embalagens.add(new EmbalagemCompra(this, d.nome(), d.conteudo()));
            } else {
                embalagem(d.id()).alterar(d.nome(), d.conteudo());
            }
        }
        return removidas;
    }

    public EmbalagemCompra embalagem(Long embalagemId) {
        return embalagens.stream().filter(e -> e.getId() != null && e.getId().equals(embalagemId)).findFirst()
                .orElseThrow(() -> new RegraDeNegocioException("Embalagem " + embalagemId + " não pertence a " + nome + "."));
    }

    /** Converte a quantidade digitada na ficha para a unidade do insumo. */
    public BigDecimal converterParaBase(ModoQuantidade modo, BigDecimal informada, EmbalagemCompra embalagem) {
        if (informada == null || informada.signum() <= 0) {
            throw new RegraDeNegocioException("Quantidade de " + nome + " deve ser maior que zero.");
        }
        return switch (modo) {
            case UNIDADE_BASE -> informada.setScale(ESCALA_FICHA, RoundingMode.HALF_UP);
            case SUBUNIDADE -> {
                if (!unidade.temSubunidade()) {
                    throw new RegraDeNegocioException(nome + " é controlado em " + unidade.getSigla()
                            + " e não tem subunidade (g/ml).");
                }
                yield informada.divide(MIL, ESCALA_FICHA, RoundingMode.HALF_UP);
            }
            case UNIDADE_USO -> {
                if (unidadeUsoPorUnidade == null) {
                    throw new RegraDeNegocioException(nome + " não tem unidade de uso cadastrada (ex.: isca).");
                }
                yield informada.divide(unidadeUsoPorUnidade, ESCALA_FICHA, RoundingMode.HALF_UP);
            }
            case RENDIMENTO_EMBALAGEM -> {
                if (embalagem == null || embalagens.stream().noneMatch(e -> e == embalagem
                        || (e.getId() != null && e.getId().equals(embalagem.getId())))) {
                    throw new RegraDeNegocioException("Escolha uma embalagem de " + nome + " para informar o rendimento.");
                }
                // 7 porções por saco de 2 kg: cada porção usa 2/7 kg
                yield embalagem.getConteudo().divide(informada, ESCALA_FICHA, RoundingMode.HALF_UP);
            }
        };
    }

    public boolean possui(BigDecimal quantidade) {
        return estoqueAtual.add(TOLERANCIA).compareTo(quantidade) >= 0;
    }

    /** Quem chama já conferiu o saldo (EstoqueService.travarEConferir). */
    void baixar(BigDecimal quantidade) {
        positivo(quantidade);
        if (!possui(quantidade)) {
            throw new RegraDeNegocioException("Estoque insuficiente de " + nome);
        }
        estoqueAtual = estoqueAtual.subtract(quantidade);
        if (estoqueAtual.abs().compareTo(TOLERANCIA) < 0) {
            estoqueAtual = BigDecimal.ZERO.setScale(ESCALA_QTD); // resto de arredondamento: o saco acabou
        }
    }

    void estornar(BigDecimal quantidade) {
        positivo(quantidade);
        estoqueAtual = estoqueAtual.add(quantidade);
    }

    /**
     * Custo médio ponderado: 2 kg a R$ 20 + 3 kg por R$ 75 = R$ 115 / 5 kg = R$ 23/kg.
     */
    void darEntrada(BigDecimal quantidade, BigDecimal valorTotalCompra) {
        positivo(quantidade);
        naoNegativo(valorTotalCompra, "Valor da compra");
        BigDecimal saldoAnterior = estoqueAtual.max(BigDecimal.ZERO);
        BigDecimal valorEmEstoque = saldoAnterior.multiply(custoUnitario);
        BigDecimal novoSaldo = saldoAnterior.add(quantidade);
        custoUnitario = valorEmEstoque.add(valorTotalCompra)
                .divide(novoSaldo, ESCALA_CUSTO, Dinheiro.ARREDONDAMENTO);
        estoqueAtual = estoqueAtual.add(quantidade);
    }

    /** Inventário. Retorna a diferença para o saldo anterior (negativa = perda). */
    BigDecimal ajustarPara(BigDecimal quantidadeContada) {
        naoNegativo(quantidadeContada, "Quantidade contada");
        BigDecimal diferenca = quantidadeContada.subtract(estoqueAtual);
        estoqueAtual = quantidadeContada.setScale(ESCALA_QTD, RoundingMode.HALF_UP);
        return diferenca;
    }

    public boolean abaixoDoMinimo() {
        return estoqueAtual.compareTo(estoqueMinimo) <= 0;
    }

    public void desativar() {
        this.ativo = false;
    }

    public void ativar() {
        this.ativo = true;
    }

    private static BigDecimal naoNegativo(BigDecimal valor, String campo) {
        if (valor == null || valor.signum() < 0) {
            throw new RegraDeNegocioException(campo + " não pode ser negativo.");
        }
        return valor;
    }

    private static void positivo(BigDecimal quantidade) {
        if (quantidade == null || quantidade.signum() <= 0) {
            throw new RegraDeNegocioException("Quantidade deve ser maior que zero.");
        }
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public UnidadeMedida getUnidade() {
        return unidade;
    }

    public BigDecimal getEstoqueAtual() {
        return estoqueAtual;
    }

    public BigDecimal getEstoqueMinimo() {
        return estoqueMinimo;
    }

    public BigDecimal getCustoUnitario() {
        return custoUnitario;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public String getGrupo() {
        return grupo;
    }

    public String getMarca() {
        return marca;
    }

    public String getUnidadeUsoNome() {
        return unidadeUsoNome;
    }

    public BigDecimal getUnidadeUsoPorUnidade() {
        return unidadeUsoPorUnidade;
    }

    /** Da maior para a menor; a primeira é usada para mostrar o saldo em sacos. */
    public List<EmbalagemCompra> getEmbalagens() {
        return List.copyOf(embalagens);
    }
}
