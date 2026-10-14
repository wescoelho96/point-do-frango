package com.pointdofrango.estoque;

import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Quantidade que chegou com uma data de validade. Quanto ainda resta é calculado pelo saldo do insumo. */
@Entity
@Table(name = "insumo_validade")
public class LoteValidade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insumo_id", nullable = false)
    private Long insumoId;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantidade;

    @Column(nullable = false)
    private LocalDate validade;

    @Column(nullable = false)
    private Instant criadoEm;

    @Column(nullable = false, length = 50)
    private String criadoPor;

    protected LoteValidade() {
    }

    LoteValidade(Long insumoId, BigDecimal quantidade, LocalDate validade, String usuario, Instant agora) {
        if (quantidade == null || quantidade.signum() <= 0) {
            throw new RegraDeNegocioException("Informe a quantidade com essa validade.");
        }
        if (validade == null) {
            throw new RegraDeNegocioException("Informe a data de validade.");
        }
        this.insumoId = insumoId;
        this.quantidade = quantidade;
        this.validade = validade;
        this.criadoPor = usuario;
        this.criadoEm = agora;
    }

    public Long getId() {
        return id;
    }

    public Long getInsumoId() {
        return insumoId;
    }

    public BigDecimal getQuantidade() {
        return quantidade;
    }

    public LocalDate getValidade() {
        return validade;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
