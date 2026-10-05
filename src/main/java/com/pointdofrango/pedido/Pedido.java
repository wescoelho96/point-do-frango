package com.pointdofrango.pedido;

import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.financeiro.ConfiguracaoFinanceira;
import com.pointdofrango.financeiro.DistribuicaoFinanceira;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.financeiro.RateioFinanceiro;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "pedido")
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CanalVenda canal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusPedido status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FormaPagamento formaPagamento;

    private String clienteNome;
    private String clienteTelefone;
    private String enderecoEntrega;
    private String observacao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorTotal;

    /** Potes do rateio, congelados com a configuração vigente no momento da venda. */
    @Embedded
    private DistribuicaoFinanceira distribuicao;

    private String criadoPor;

    @Column(nullable = false)
    private Instant criadoEm;

    @Column(nullable = false)
    private Instant atualizadoEm;

    private String motivoCancelamento;

    /** iFood/99Food: nº do pedido no app (não deixa lançar o mesmo pedido duas vezes). */
    private String codigoExterno;

    /** iFood/99Food: o que o app descontou (comissão + pagamento online). */
    @Column(precision = 10, scale = 2)
    private BigDecimal taxaPlataforma;

    /** iFood/99Food: promoção/cupom bancado pela loja (reduz o que foi vendido). */
    @Column(precision = 10, scale = 2)
    private BigDecimal descontoLoja;

    /** Preenchido quando o pedido é de uma comanda (mesa). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comanda_id")
    private Comanda comanda;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemPedido> itens = new ArrayList<>();

    @Column(name = "cliente_id")
    private Long clienteId;

    // Entrega: a taxa é repassada ao motoboy, então não entra em valorTotal nem no rateio.
    private String bairroEntrega;

    @Column(precision = 10, scale = 2)
    private BigDecimal taxaEntrega;

    /** O que o cliente pagou de taxa. Zero na entrega grátis (aí o motoboy é custo da loja). */
    @Column(precision = 10, scale = 2)
    private BigDecimal taxaCobrada;

    /** O próprio dono entregou: ninguém a pagar, a taxa cobrada é receita da loja. */
    @Column(nullable = false)
    private boolean entregaPeloDono;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entregador_id")
    private Colaborador entregador;

    /** Saída do caixa que pagou esta entrega ao motoboy. Nulo enquanto não for acertada. */
    @Column(name = "acerto_entregador_id")
    private Long acertoEntregadorId;

    /** Para quem / por que foi dado de graça (só na cortesia). */
    private String motivoCortesia;

    /** Pagamento em dinheiro: quanto o cliente entregou (ou "troco para" na entrega). */
    @Column(precision = 10, scale = 2)
    private BigDecimal valorRecebido;

    protected Pedido() {
    }

    public Pedido(CanalVenda canal, FormaPagamento formaPagamento, DadosCliente cliente, String criadoPor, Instant agora) {
        this.canal = canal;
        this.formaPagamento = formaPagamento;
        this.clienteNome = cliente.nome();
        this.clienteTelefone = cliente.telefone();
        this.enderecoEntrega = cliente.endereco();
        this.observacao = cliente.observacao();
        this.status = StatusPedido.EM_PREPARO;
        this.criadoPor = criadoPor;
        this.criadoEm = agora;
        this.atualizadoEm = agora;
        this.valorTotal = BigDecimal.ZERO;
        this.distribuicao = DistribuicaoFinanceira.ZERO;
    }

    public record DadosCliente(String nome, String telefone, String endereco, String observacao) {
    }

    void vincularComanda(Comanda comanda) {
        this.comanda = comanda;
        this.canal = CanalVenda.MESA;
        this.formaPagamento = null;
        if (clienteNome == null) {
            this.clienteNome = comanda.getIdentificacao();
        }
    }

    /**
     * Fechamento da comanda: agora a forma de pagamento é conhecida e a taxa da maquininha
     * pode ser calculada. Refaz os potes com a configuração atual.
     */
    void definirPagamento(FormaPagamento forma, ConfiguracaoFinanceira config, Instant agora) {
        this.formaPagamento = forma;
        this.distribuicao = RateioFinanceiro.calcular(valorTotal,
                itens.stream().map(ItemPedido::custoTotal).reduce(BigDecimal.ZERO, BigDecimal::add), forma, config);
        this.atualizadoEm = agora;
    }

    public boolean cancelado() {
        return status == StatusPedido.CANCELADO;
    }

    public void adicionarItem(Produto produto, int quantidade, BigDecimal custoUnitario) {
        if (!produto.isAtivo()) {
            throw new RegraDeNegocioException("Produto indisponível no cardápio: " + produto.getNome());
        }
        if (quantidade <= 0) {
            throw new RegraDeNegocioException("Quantidade deve ser maior que zero.");
        }
        itens.add(new ItemPedido(this, produto, quantidade, custoUnitario));
    }

    /** Cortesia: todos os itens com preço zero. Chamado antes de fechar os valores. */
    void tornarCortesia(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new RegraDeNegocioException("Na cortesia, informe para quem foi ou o motivo (ex.: cliente fiel).");
        }
        this.motivoCortesia = motivo.strip();
        itens.forEach(ItemPedido::zerarPreco);
    }

    public boolean cortesia() {
        return formaPagamento == FormaPagamento.CORTESIA;
    }

    public String getMotivoCortesia() {
        return motivoCortesia;
    }

    public void adicionarBrinde(Produto produto, int quantidade, BigDecimal custoUnitario, String promocao) {
        itens.add(ItemPedido.brinde(this, produto, quantidade, custoUnitario, promocao));
    }

    /** Fecha o valor e calcula os potes. Chamado uma vez, depois de adicionar os itens. */
    public void fecharValores(ConfiguracaoFinanceira config) {
        if (itens.isEmpty()) {
            throw new RegraDeNegocioException("O pedido precisa de pelo menos um item.");
        }
        this.valorTotal = Dinheiro.centavos(itens.stream().map(ItemPedido::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal cmv = itens.stream().map(ItemPedido::custoTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.distribuicao = RateioFinanceiro.calcular(valorTotal, cmv, formaPagamento, config);
    }

    /**
     * Pedido de app: o vendido é o valor no app menos a promoção bancada pela loja,
     * e o pote de taxas recebe exatamente o que o app descontou.
     */
    void fecharValoresDePlataforma(String codigo, BigDecimal valorNoApp, BigDecimal desconto, BigDecimal taxas,
                                   ConfiguracaoFinanceira config) {
        if (itens.isEmpty()) {
            throw new RegraDeNegocioException("O pedido precisa de pelo menos um item.");
        }
        BigDecimal descontoOuZero = desconto == null ? BigDecimal.ZERO : desconto;
        BigDecimal vendido = Dinheiro.centavos(valorNoApp.subtract(descontoOuZero));
        if (vendido.signum() <= 0) {
            throw new RegraDeNegocioException("A promoção não pode ser maior que o valor do pedido.");
        }
        if (taxas.compareTo(vendido) > 0) {
            throw new RegraDeNegocioException("As taxas do app não podem ser maiores que o valor vendido.");
        }
        this.codigoExterno = codigo == null || codigo.isBlank() ? null : codigo.trim();
        this.descontoLoja = Dinheiro.centavos(descontoOuZero);
        this.taxaPlataforma = Dinheiro.centavos(taxas);
        this.valorTotal = vendido;
        BigDecimal cmv = itens.stream().map(ItemPedido::custoTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.distribuicao = RateioFinanceiro.calcularComTaxa(vendido, cmv, taxas, config);
    }

    void definirEntrega(String bairro, BigDecimal taxa, boolean gratis, Colaborador entregador, boolean peloDono) {
        this.bairroEntrega = bairro;
        this.taxaEntrega = Dinheiro.centavos(taxa);
        this.taxaCobrada = gratis ? BigDecimal.ZERO.setScale(2) : this.taxaEntrega;
        definirQuemEntrega(entregador, peloDono);
    }

    private void definirQuemEntrega(Colaborador motoboy, boolean peloDono) {
        if (peloDono && motoboy != null) {
            throw new RegraDeNegocioException("Escolha o motoboy ou o dono, não os dois.");
        }
        this.entregador = motoboy;
        this.entregaPeloDono = peloDono;
    }

    void vincularCliente(Long clienteId) {
        this.clienteId = clienteId;
    }

    public boolean entrega() {
        return taxaEntrega != null;
    }

    /** O que o cliente paga: itens + taxa de entrega cobrada. */
    public BigDecimal totalACobrar() {
        return entrega() ? valorTotal.add(taxaCobrada()) : valorTotal;
    }

    /** Pedidos antigos (antes da entrega grátis) não têm a coluna preenchida: valia a taxa inteira. */
    public BigDecimal taxaCobrada() {
        return taxaCobrada != null ? taxaCobrada : taxaEntrega;
    }

    public boolean entregaGratis() {
        return entrega() && taxaCobrada().signum() == 0 && taxaEntrega.signum() > 0;
    }

    void registrarValorRecebido(BigDecimal recebido) {
        if (recebido == null || formaPagamento != FormaPagamento.DINHEIRO) {
            this.valorRecebido = null;
            return;
        }
        if (recebido.compareTo(totalACobrar()) < 0) {
            throw new RegraDeNegocioException("Valor recebido (R$ %s) é menor que o total (R$ %s)."
                    .formatted(Dinheiro.centavos(recebido), totalACobrar()));
        }
        this.valorRecebido = Dinheiro.centavos(recebido);
    }

    public BigDecimal troco() {
        return valorRecebido == null ? null : valorRecebido.subtract(totalACobrar());
    }

    public boolean acertadoComEntregador() {
        return acertoEntregadorId != null;
    }

    void atribuirEntregador(Colaborador motoboy, boolean peloDono) {
        if (!entrega()) {
            throw new RegraDeNegocioException("Pedido #" + id + " não é de entrega.");
        }
        if (cancelado()) {
            throw new RegraDeNegocioException("Pedido #" + id + " está cancelado.");
        }
        if (acertadoComEntregador()) {
            throw new RegraDeNegocioException("A entrega do pedido #" + id + " já foi paga ao motoboy.");
        }
        definirQuemEntrega(motoboy, peloDono);
    }

    public void registrarAcerto(Long movimentoCaixaId) {
        if (acertadoComEntregador()) {
            throw new RegraDeNegocioException("A entrega do pedido #" + id + " já foi paga.");
        }
        this.acertoEntregadorId = movimentoCaixaId;
    }

    /** O pagamento ao motoboy foi lançado errado e removido do caixa: a entrega volta a ficar pendente. */
    public void desfazerAcerto() {
        this.acertoEntregadorId = null;
    }

    /** Lançamento posterior (ex.: pedidos do app no fim do dia): já nasce entregue, na hora em que aconteceu. */
    void registrarComoJaEntregue(Instant quando) {
        this.status = StatusPedido.ENTREGUE;
        this.criadoEm = quando;
        this.atualizadoEm = quando;
    }

    /** Soma dos itens a preço de cardápio (no app o preço costuma ser outro). */
    public BigDecimal valorCardapio() {
        return itens.stream().map(ItemPedido::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void mudarStatus(StatusPedido destino, Instant agora) {
        if (destino == StatusPedido.CANCELADO) {
            throw new RegraDeNegocioException("Use a operação de cancelamento (ela devolve o estoque).");
        }
        validarTransicao(destino);
        this.status = destino;
        this.atualizadoEm = agora;
    }

    public void cancelar(String motivo, Instant agora) {
        validarTransicao(StatusPedido.CANCELADO);
        this.status = StatusPedido.CANCELADO;
        this.motivoCancelamento = motivo;
        this.atualizadoEm = agora;
    }

    private void validarTransicao(StatusPedido destino) {
        if (!status.podeIrPara(destino)) {
            throw new RegraDeNegocioException("Pedido #%d não pode ir de %s para %s.".formatted(id, status, destino));
        }
    }

    /** Usado só pelo gerador de dados de demonstração, para espalhar pedidos pelos últimos dias. */
    void retroagirPara(Instant instante) {
        this.criadoEm = instante;
        this.atualizadoEm = instante;
    }

    public Long getId() {
        return id;
    }

    public CanalVenda getCanal() {
        return canal;
    }

    public StatusPedido getStatus() {
        return status;
    }

    public FormaPagamento getFormaPagamento() {
        return formaPagamento;
    }

    public String getClienteNome() {
        return clienteNome;
    }

    public String getClienteTelefone() {
        return clienteTelefone;
    }

    public String getEnderecoEntrega() {
        return enderecoEntrega;
    }

    public String getObservacao() {
        return observacao;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public DistribuicaoFinanceira getDistribuicao() {
        return distribuicao;
    }

    public String getCriadoPor() {
        return criadoPor;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public String getMotivoCancelamento() {
        return motivoCancelamento;
    }

    public String getCodigoExterno() {
        return codigoExterno;
    }

    public BigDecimal getTaxaPlataforma() {
        return taxaPlataforma;
    }

    public BigDecimal getDescontoLoja() {
        return descontoLoja;
    }

    public Comanda getComanda() {
        return comanda;
    }

    public Long getClienteId() {
        return clienteId;
    }

    public String getBairroEntrega() {
        return bairroEntrega;
    }

    public BigDecimal getTaxaEntrega() {
        return taxaEntrega;
    }

    public boolean isEntregaPeloDono() {
        return entregaPeloDono;
    }

    public Colaborador getEntregador() {
        return entregador;
    }

    public Long getAcertoEntregadorId() {
        return acertoEntregadorId;
    }

    public BigDecimal getValorRecebido() {
        return valorRecebido;
    }

    public List<ItemPedido> getItens() {
        return Collections.unmodifiableList(itens);
    }
}
