package com.pointdofrango.financeiro;

import com.pointdofrango.shared.Dinheiro;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Conta que se repete todo mês: aluguel, luz, água, internet, contador... */
@Entity
@Table(name = "despesa_fixa")
public class DespesaFixa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String descricao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorMensal;

    private Integer diaVencimento;

    @Column(nullable = false)
    private boolean ativa = true;

    /** Água, luz, gás: o valor acompanha o último pagamento. */
    @Column(nullable = false)
    private boolean valorVariavel;

    protected DespesaFixa() {
    }

    public DespesaFixa(String descricao, BigDecimal valorMensal, Integer diaVencimento) {
        atualizar(descricao, valorMensal, diaVencimento, true);
    }

    public void atualizar(String descricao, BigDecimal valorMensal, Integer diaVencimento, boolean ativa) {
        this.descricao = descricao.trim();
        this.valorMensal = Dinheiro.centavos(valorMensal);
        this.diaVencimento = diaVencimento;
        this.ativa = ativa;
    }

    public void definirValorVariavel(boolean variavel) {
        this.valorVariavel = variavel;
    }

    /** Em conta de valor variável, o último pagamento vira a referência do rateio e da previsão. */
    public void registrarPagamento(BigDecimal valorPago) {
        if (valorVariavel) {
            this.valorMensal = Dinheiro.centavos(valorPago);
        }
    }

    public boolean isValorVariavel() {
        return valorVariavel;
    }

    public Long getId() {
        return id;
    }

    public String getDescricao() {
        return descricao;
    }

    public BigDecimal getValorMensal() {
        return valorMensal;
    }

    public Integer getDiaVencimento() {
        return diaVencimento;
    }

    public boolean isAtiva() {
        return ativa;
    }
}
