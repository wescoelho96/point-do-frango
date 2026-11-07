package com.pointdofrango.pedido;

import com.pointdofrango.financeiro.ConfiguracaoFinanceira;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Conta dividida: o que cada pessoa pagou, somando o total com serviço. */
    @ElementCollection
    @CollectionTable(name = "comanda_pagamento", joinColumns = @JoinColumn(name = "comanda_id"))
    @OrderColumn(name = "ordem")
    private List<PartePagamento> partes = new ArrayList<>();

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

    /**
     * Aplica a forma de pagamento a todos os pedidos, o que recalcula a taxa da maquininha no rateio.
     * Na conta dividida, as partes (que incluem o serviço) são repartidas entre os pedidos na proporção do valor.
     */
    void fechar(FormaPagamento forma, boolean cobrar, BigDecimal percentual, BigDecimal recebido,
                List<PartePagamento> novasPartes, ConfiguracaoFinanceira config, String usuario, Instant agora) {
        garantirAberta();
        boolean dividido = forma == FormaPagamento.DIVIDIDO;
        if (forma == null || !(dividido || forma.parteDeDivisao())) {
            throw new RegraDeNegocioException("Informe a forma de pagamento (dinheiro, PIX, débito, crédito ou dividido).");
        }
        if (!dividido && novasPartes != null && !novasPartes.isEmpty()) {
            throw new RegraDeNegocioException("As partes do pagamento só valem na conta dividida.");
        }
        List<Pedido> validos = pedidosValidos();
        if (validos.isEmpty()) {
            throw new RegraDeNegocioException("A comanda não tem consumo. Cancele em vez de fechar.");
        }
        this.formaPagamento = forma;
        this.cobrarServico = cobrar;
        this.percentualServico = percentual;
        this.valorItens = Dinheiro.centavos(consumo());
        this.valorServico = cobrar ? servicoSobre(valorItens, percentual) : BigDecimal.ZERO.setScale(2);
        this.valorTotal = valorItens.add(valorServico);
        if (dividido) {
            validarPartes(novasPartes);
            repartirEntre(validos, config, agora);
        } else {
            validos.forEach(p -> p.definirPagamento(forma, config, agora));
        }
        BigDecimal emDinheiro = emDinheiro();
        if (emDinheiro != null && recebido != null) {
            if (recebido.compareTo(emDinheiro) < 0) {
                throw new RegraDeNegocioException("Valor recebido (R$ %s) é menor que o %s (R$ %s)."
                        .formatted(Dinheiro.centavos(recebido), dividido ? "que é pago em dinheiro" : "total da conta",
                                emDinheiro));
            }
            this.valorRecebido = Dinheiro.centavos(recebido);
        }
        this.status = StatusComanda.FECHADA;
        this.fechadaPor = usuario;
        this.fechadaEm = agora;
    }

    private void validarPartes(List<PartePagamento> novas) {
        if (novas == null || novas.size() < 2) {
            throw new RegraDeNegocioException("Na conta dividida, informe pelo menos duas partes (forma e valor).");
        }
        BigDecimal soma = BigDecimal.ZERO;
        for (PartePagamento parte : novas) {
            if (parte.getForma() == null || !parte.getForma().parteDeDivisao()) {
                throw new RegraDeNegocioException("Cada parte da conta é paga em PIX, dinheiro, débito ou crédito.");
            }
            if (parte.getValor() == null || parte.getValor().signum() <= 0) {
                throw new RegraDeNegocioException("Cada parte da conta precisa de um valor maior que zero.");
            }
            soma = soma.add(Dinheiro.centavos(parte.getValor()));
        }
        if (soma.compareTo(valorTotal) != 0) {
            throw new RegraDeNegocioException("As partes somam R$ %s, mas o total da conta é R$ %s.".formatted(soma, valorTotal));
        }
        this.partes = new ArrayList<>(novas.stream()
                .map(p -> new PartePagamento(p.getForma(), Dinheiro.centavos(p.getValor()))).toList());
    }

    /**
     * Cada pedido recebe a sua fatia de cada forma, para a taxa da maquininha e os relatórios por forma
     * baterem. O serviço não está nos pedidos, então as formas são reduzidas ao valor dos itens antes.
     */
    private void repartirEntre(List<Pedido> validos, ConfiguracaoFinanceira config, Instant agora) {
        Map<FormaPagamento, BigDecimal> porForma = recebimentos();
        Map<FormaPagamento, BigDecimal> restante = new EnumMap<>(FormaPagamento.class);
        List<FormaPagamento> formas = List.copyOf(porForma.keySet());
        BigDecimal semServico = valorItens;
        for (int i = 0; i < formas.size(); i++) {
            FormaPagamento f = formas.get(i);
            BigDecimal v = i == formas.size() - 1 ? semServico
                    : porForma.get(f).multiply(valorItens).divide(valorTotal, 2, RoundingMode.HALF_UP);
            restante.put(f, v);
            semServico = semServico.subtract(v);
        }
        BigDecimal pedidosRestantes = valorItens;
        for (int i = 0; i < validos.size(); i++) {
            Pedido p = validos.get(i);
            BigDecimal alvo = p.getValorTotal();
            Map<FormaPagamento, BigDecimal> desta = new EnumMap<>(FormaPagamento.class);
            if (i == validos.size() - 1) {
                desta.putAll(restante);
            } else {
                BigDecimal base = pedidosRestantes;
                restante.forEach((f, v) -> desta.put(f, base.signum() == 0 ? BigDecimal.ZERO
                        : v.multiply(alvo).divide(base, 2, RoundingMode.HALF_UP)));
                BigDecimal sobra = alvo.subtract(desta.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
                if (sobra.signum() != 0) {
                    // o centavo do arredondamento vai para a forma que tem folga para absorvê-lo
                    FormaPagamento ajuste = desta.keySet().stream().max(Comparator.comparing((FormaPagamento f) ->
                            sobra.signum() > 0 ? restante.get(f).subtract(desta.get(f)) : desta.get(f))).orElseThrow();
                    desta.merge(ajuste, sobra, BigDecimal::add);
                }
            }
            desta.forEach((f, v) -> restante.merge(f, v.negate(), BigDecimal::add));
            pedidosRestantes = pedidosRestantes.subtract(alvo);
            List<PartePagamento> doPedido = desta.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                    .map(e -> new PartePagamento(e.getKey(), e.getValue())).toList();
            if (doPedido.size() < 2) {
                p.definirPagamento(doPedido.isEmpty() ? formas.get(0) : doPedido.get(0).getForma(), config, agora);
            } else {
                p.definirPagamentoDividido(doPedido, config, agora);
            }
        }
    }

    /** Quanto entrou em cada forma: na conta dividida, as partes; senão, o total na forma da comanda. */
    public Map<FormaPagamento, BigDecimal> recebimentos() {
        Map<FormaPagamento, BigDecimal> porForma = new EnumMap<>(FormaPagamento.class);
        if (formaPagamento == FormaPagamento.DIVIDIDO) {
            partes.forEach(p -> porForma.merge(p.getForma(), p.getValor(), BigDecimal::add));
        } else if (formaPagamento != null && valorTotal != null) {
            porForma.put(formaPagamento, valorTotal);
        }
        return porForma;
    }

    /** A parte paga em dinheiro (a que pode ter troco); nulo se nada foi em dinheiro. */
    public BigDecimal emDinheiro() {
        return recebimentos().get(FormaPagamento.DINHEIRO);
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
        BigDecimal emDinheiro = emDinheiro();
        return valorRecebido == null || emDinheiro == null ? null : valorRecebido.subtract(emDinheiro);
    }

    public List<PartePagamento> getPartes() {
        return List.copyOf(partes);
    }

    public List<Pedido> getPedidos() {
        return List.copyOf(pedidos);
    }
}
