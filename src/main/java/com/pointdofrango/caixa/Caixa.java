package com.pointdofrango.caixa;

import com.pointdofrango.caixa.MovimentoCaixa.Tipo;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Caixa do dia e seus movimentos (suprimentos e saídas). As vendas não ficam gravadas aqui:
 * são somadas dos pedidos e comandas pagos no período (ver CaixaService.totais).
 */
@Entity
@Table(name = "caixa")
public class Caixa {

    public enum Status { ABERTO, FECHADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private Instant abertoEm;

    @Column(nullable = false, length = 50)
    private String abertoPor;

    /** Pode ser anterior à abertura, para não perder vendas pagas com o caixa fechado. */
    private Instant vendasDesde;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorAbertura;

    private Instant fechadoEm;
    private String fechadoPor;

    @Column(precision = 10, scale = 2)
    private BigDecimal dinheiroEsperado;

    @Column(precision = 10, scale = 2)
    private BigDecimal dinheiroContado;

    private String observacao;

    @Version
    private Long versao;

    @OneToMany(mappedBy = "caixa", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("criadoEm")
    private List<MovimentoCaixa> movimentos = new ArrayList<>();

    protected Caixa() {
    }

    Caixa(BigDecimal valorAbertura, String usuario, Instant agora, Instant vendasDesde) {
        if (valorAbertura == null || valorAbertura.signum() < 0) {
            throw new RegraDeNegocioException("Informe quanto tem de troco na gaveta (pode ser zero).");
        }
        this.status = Status.ABERTO;
        this.valorAbertura = Dinheiro.centavos(valorAbertura);
        this.abertoPor = usuario;
        this.abertoEm = agora;
        this.vendasDesde = vendasDesde.isAfter(agora) ? agora : vendasDesde;
    }

    MovimentoCaixa registrar(Tipo tipo, CategoriaSaida categoria, String descricao, BigDecimal valor, FormaPagamento forma,
                             Colaborador colaborador, boolean incluiDiaria, String usuario, Instant agora) {
        garantirAberto();
        if (valor == null || valor.signum() <= 0) {
            throw new RegraDeNegocioException("O valor precisa ser maior que zero.");
        }
        if (forma == null || !forma.movimentaDinheiro()) {
            throw new RegraDeNegocioException("Informe se saiu em dinheiro, PIX ou cartão.");
        }
        if (tipo == Tipo.SUPRIMENTO && forma != FormaPagamento.DINHEIRO) {
            throw new RegraDeNegocioException("Suprimento é dinheiro colocado na gaveta.");
        }
        MovimentoCaixa m = new MovimentoCaixa(this, tipo, categoria, descricao.trim(), Dinheiro.centavos(valor), forma,
                colaborador, incluiDiaria, usuario, agora);
        movimentos.add(m);
        return m;
    }

    void remover(MovimentoCaixa movimento) {
        garantirAberto();
        movimentos.remove(movimento);
    }

    /** A diferença entre esperado e contado é a quebra de caixa. */
    void fechar(BigDecimal esperado, BigDecimal contado, String observacao, String usuario, Instant agora) {
        garantirAberto();
        if (contado == null || contado.signum() < 0) {
            throw new RegraDeNegocioException("Informe quanto dinheiro tem na gaveta.");
        }
        this.dinheiroEsperado = Dinheiro.centavos(esperado);
        this.dinheiroContado = Dinheiro.centavos(contado);
        this.observacao = observacao == null || observacao.isBlank() ? null : observacao.trim();
        this.status = Status.FECHADO;
        this.fechadoPor = usuario;
        this.fechadoEm = agora;
    }

    void garantirAberto() {
        if (status != Status.ABERTO) {
            throw new RegraDeNegocioException("Este caixa já foi fechado.");
        }
    }

    public boolean aberto() {
        return status == Status.ABERTO;
    }

    public BigDecimal diferenca() {
        return dinheiroContado == null ? null : dinheiroContado.subtract(dinheiroEsperado);
    }

    BigDecimal somar(Predicate<MovimentoCaixa> filtro) {
        return movimentos.stream().filter(filtro).map(MovimentoCaixa::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Long getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getAbertoEm() {
        return abertoEm;
    }

    public Instant getVendasDesde() {
        return vendasDesde != null ? vendasDesde : abertoEm;
    }

    public String getAbertoPor() {
        return abertoPor;
    }

    public BigDecimal getValorAbertura() {
        return valorAbertura;
    }

    public Instant getFechadoEm() {
        return fechadoEm;
    }

    public String getFechadoPor() {
        return fechadoPor;
    }

    public BigDecimal getDinheiroEsperado() {
        return dinheiroEsperado;
    }

    public BigDecimal getDinheiroContado() {
        return dinheiroContado;
    }

    public String getObservacao() {
        return observacao;
    }

    public List<MovimentoCaixa> getMovimentos() {
        return List.copyOf(movimentos);
    }
}
