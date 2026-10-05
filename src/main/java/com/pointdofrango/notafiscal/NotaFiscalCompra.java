package com.pointdofrango.notafiscal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** NF-e já importada. A chave única impede importar duas vezes. */
@Entity
@Table(name = "nota_fiscal_compra")
public class NotaFiscalCompra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 44)
    private String chave;

    @Column(nullable = false, length = 9)
    private String numero;

    @Column(nullable = false, length = 3)
    private String serie;

    @Column(nullable = false, length = 14)
    private String emitenteCnpj;

    @Column(nullable = false, length = 150)
    private String emitenteNome;

    @Column(nullable = false)
    private Instant emitidaEm;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal valorTotal;

    @Column(name = "fornecedor_id")
    private Long fornecedorId;

    @Column(nullable = false)
    private Instant importadaEm;

    @Column(nullable = false, length = 50)
    private String importadaPor;

    protected NotaFiscalCompra() {
    }

    NotaFiscalCompra(LeitorNfe.Nota nota, Long fornecedorId, String usuario, Instant agora) {
        this.chave = nota.chave();
        this.numero = nota.numero();
        this.serie = nota.serie();
        this.emitenteCnpj = nota.emitenteCnpj();
        this.emitenteNome = nota.emitenteNome().length() > 150 ? nota.emitenteNome().substring(0, 150) : nota.emitenteNome();
        this.emitidaEm = nota.emitidaEm().toInstant();
        this.valorTotal = nota.valorTotal();
        this.fornecedorId = fornecedorId;
        this.importadaPor = usuario;
        this.importadaEm = agora;
    }

    public Long getId() {
        return id;
    }

    public String getChave() {
        return chave;
    }

    public String getNumero() {
        return numero;
    }

    public String getEmitenteNome() {
        return emitenteNome;
    }

    public Instant getEmitidaEm() {
        return emitidaEm;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public Instant getImportadaEm() {
        return importadaEm;
    }

    public String getImportadaPor() {
        return importadaPor;
    }
}
