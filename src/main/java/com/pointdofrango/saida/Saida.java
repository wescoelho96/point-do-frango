package com.pointdofrango.saida;

import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.RegraDeNegocioException;
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
import java.time.Instant;
import java.time.LocalDate;

/** Pagamento feito por fora da gaveta (PIX, boleto, cartão). Saídas da gaveta ficam em MovimentoCaixa. */
@Entity
@Table(name = "saida")
public class Saida {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate data;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoriaSaida categoria;

    @Column(nullable = false, length = 150)
    private String descricao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FormaPagamento forma;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fornecedor_id")
    private Fornecedor fornecedor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "colaborador_id")
    private Colaborador colaborador;

    @Column(name = "despesa_fixa_id")
    private Long despesaFixaId;

    @Column(length = 48)
    private String codigoBarras;

    @Column(nullable = false)
    private Instant criadoEm;

    @Column(nullable = false, length = 50)
    private String criadoPor;

    protected Saida() {
    }

    Saida(LocalDate data, CategoriaSaida categoria, String descricao, BigDecimal valor, FormaPagamento forma,
          String usuario, Instant agora) {
        if (categoria == null || !categoria.gasto()) {
            throw new RegraDeNegocioException("Sangria e suprimento são lançados no caixa.");
        }
        if (valor == null || valor.signum() <= 0) {
            throw new RegraDeNegocioException("O valor precisa ser maior que zero.");
        }
        if (forma == null || !forma.movimentaDinheiro()) {
            throw new RegraDeNegocioException("Informe como foi pago (PIX, débito, crédito ou dinheiro).");
        }
        this.data = data;
        this.categoria = categoria;
        this.descricao = descricao.strip();
        this.valor = Dinheiro.centavos(valor);
        this.forma = forma;
        this.criadoPor = usuario;
        this.criadoEm = agora;
    }

    void vincular(Fornecedor fornecedor, Colaborador colaborador, Long despesaFixaId) {
        this.fornecedor = fornecedor;
        this.colaborador = colaborador;
        this.despesaFixaId = despesaFixaId;
    }

    void guardarCodigoBarras(String codigo) {
        this.codigoBarras = codigo;
    }

    public String getCodigoBarras() {
        return codigoBarras;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getData() {
        return data;
    }

    public CategoriaSaida getCategoria() {
        return categoria;
    }

    public String getDescricao() {
        return descricao;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public FormaPagamento getForma() {
        return forma;
    }

    public Fornecedor getFornecedor() {
        return fornecedor;
    }

    public Colaborador getColaborador() {
        return colaborador;
    }

    public Long getDespesaFixaId() {
        return despesaFixaId;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public String getCriadoPor() {
        return criadoPor;
    }
}
