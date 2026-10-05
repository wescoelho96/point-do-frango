package com.pointdofrango.pedido;

import com.pointdofrango.financeiro.ConfiguracaoFinanceira;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
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
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conta de mesa. Cada pedido lançado vai para a cozinha e baixa o estoque na hora;
 * o pagamento e a taxa de serviço (opcional para o cliente) só são definidos no fechamento.
 */
@Entity
@Table(name = "comanda")
public class Comanda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String identificacao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusComanda status;

    private String observacao;
    private String abertaPor;

    @Column(nullable = false)
    private Instant abertaEm;

    private String fechadaPor;
    private Instant fechadaEm;

    @Enumerated(EnumType.STRING)
    private FormaPagamento formaPagamento;

    @Column(precision = 5, scale = 2)
    private BigDecimal percentualServico;

    private Boolean cobrarServico;

    @Column(precision = 10, scale = 2)
    private BigDecimal valorItens;

    @Column(precision = 10, scale = 2)
    private BigDecimal valorServico;

    @Column(precision = 10, scale = 2)
    private BigDecimal valorTotal;

    private String motivoCancelamento;

    @Column(precision = 10, scale = 2)
    private BigDecimal valorRecebido;

    /** Lock otimista: se dois aparelhos fecharem a mesma conta, o segundo recebe 409. */
    @Version
    private Long versao;

    /** Set e não List: é carregada junto com os itens dos pedidos (evita MultipleBagFetchException). */
    @OneToMany(mappedBy = "comanda")
    @OrderBy("id")
    private Set<Pedido> pedidos = new LinkedHashSet<>();

    protected Comanda() {
    }

    public Comanda(String identificacao, String observacao, String usuario, Instant agora) {
        if (identificacao == null || identificacao.isBlank()) {
            throw new RegraDeNegocioException("Informe a mesa ou o nome do cliente.");
        }
        this.identificacao = identificacao.trim();
        this.observacao = observacao == null || observacao.isBlank() ? null : observacao.trim();
        this.status = StatusComanda.ABERTA;
        this.abertaPor = usuario;
        this.abertaEm = agora;
    }

    public void garantirAberta() {
        if (status != StatusComanda.ABERTA) {
            throw new RegraDeNegocioException("A comanda \"" + identificacao + "\" já está " + status.name().toLowerCase() + ".");
        }
    }

    public List<Pedido> pedidosValidos() {
        return pedidos.stream().filter(p -> !p.cancelado()).toList();
    }

    public BigDecimal consumo() {
        return pedidosValidos().stream().map(Pedido::getValorTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Incide só sobre os itens. */
    public static BigDecimal servicoSobre(BigDecimal consumo, BigDecimal percentual) {
        return Dinheiro.percentual(consumo, percentual);
    }

    /** Aplica a forma de pagamento a todos os pedidos, o que recalcula a taxa da maquininha no rateio. */
    void fechar(FormaPagamento forma, boolean cobrar, BigDecimal percentual, BigDecimal recebido,
                ConfiguracaoFinanceira config, String usuario, Instant agora) {
        garantirAberta();
        if (forma == null || !forma.movimentaDinheiro()) {
            throw new RegraDeNegocioException("Informe a forma de pagamento (dinheiro, PIX, débito ou crédito).");
        }
        List<Pedido> validos = pedidosValidos();
        if (validos.isEmpty()) {
            throw new RegraDeNegocioException("A comanda não tem consumo. Cancele em vez de fechar.");
        }
        validos.forEach(p -> p.definirPagamento(forma, config, agora));
        this.formaPagamento = forma;
        this.cobrarServico = cobrar;
        this.percentualServico = percentual;
        this.valorItens = Dinheiro.centavos(consumo());
        this.valorServico = cobrar ? servicoSobre(valorItens, percentual) : BigDecimal.ZERO.setScale(2);
        this.valorTotal = valorItens.add(valorServico);
        if (forma == FormaPagamento.DINHEIRO && recebido != null) {
            if (recebido.compareTo(valorTotal) < 0) {
                throw new RegraDeNegocioException("Valor recebido (R$ %s) é menor que o total da conta (R$ %s)."
                        .formatted(Dinheiro.centavos(recebido), valorTotal));
            }
            this.valorRecebido = Dinheiro.centavos(recebido);
        }
        this.status = StatusComanda.FECHADA;
        this.fechadaPor = usuario;
        this.fechadaEm = agora;
    }

    void cancelar(String motivo, String usuario, Instant agora) {
        garantirAberta();
        this.status = StatusComanda.CANCELADA;
        this.motivoCancelamento = motivo;
        this.fechadaPor = usuario;
        this.fechadaEm = agora;
    }

    /** Só para o seeder de demonstração. */
    void retroagirPara(Instant abertura, Instant fechamento) {
        this.abertaEm = abertura;
        this.fechadaEm = fechamento;
        pedidos.stream().filter(p -> !p.cancelado()).forEach(p -> p.registrarComoJaEntregue(abertura.plusSeconds(300)));
    }

    void adicionar(Pedido pedido) {
        garantirAberta();
        pedido.vincularComanda(this);
        pedidos.add(pedido);
    }

    public Long getId() {
        return id;
    }

    public String getIdentificacao() {
        return identificacao;
    }

    public StatusComanda getStatus() {
        return status;
    }

    public String getObservacao() {
        return observacao;
    }

    public String getAbertaPor() {
        return abertaPor;
    }

    public Instant getAbertaEm() {
        return abertaEm;
    }

    public String getFechadaPor() {
        return fechadaPor;
    }

    public Instant getFechadaEm() {
        return fechadaEm;
    }

    public FormaPagamento getFormaPagamento() {
        return formaPagamento;
    }

    public BigDecimal getPercentualServico() {
        return percentualServico;
    }

    public Boolean getCobrarServico() {
        return cobrarServico;
    }

    public BigDecimal getValorItens() {
        return valorItens;
    }

    public BigDecimal getValorServico() {
        return valorServico;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public String getMotivoCancelamento() {
        return motivoCancelamento;
    }

    public BigDecimal getValorRecebido() {
        return valorRecebido;
    }

    public BigDecimal troco() {
        return valorRecebido == null ? null : valorRecebido.subtract(valorTotal);
    }

    public List<Pedido> getPedidos() {
        return List.copyOf(pedidos);
    }
}
