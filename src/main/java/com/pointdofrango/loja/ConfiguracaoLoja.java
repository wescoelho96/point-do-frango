package com.pointdofrango.loja;

import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dados da loja (saem no cupom) e regra da taxa de serviço. Tabela de linha única (id = 1).
 */
@Entity
@Table(name = "configuracao_loja")
public class ConfiguracaoLoja {

    public static final long ID_UNICO = 1L;
    private static final BigDecimal LIMITE_SERVICO = new BigDecimal("20");

    @Id
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    private String documento;
    private String endereco;
    private String telefone;
    private String mensagemRodape;

    /** Normalmente 10%. Opcional para o cliente: pode ser retirado no fechamento. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percentualServico;

    /** Se a taxa já vem marcada ao fechar a conta. */
    @Column(nullable = false)
    private boolean servicoMarcado;

    @Column(nullable = false)
    private Instant atualizadoEm;

    /** JSON com os nomes do menu que o dono trocou. Ex.: {"pdv":"Caixa rápido"}. */
    @Column(length = 1000)
    private String nomesMenu;

    /** Ex.: "TUESDAY,WEDNESDAY". Nesses dias o PDV já marca a entrega como grátis. */
    @Column(length = 80)
    private String diasEntregaGratis;

    protected ConfiguracaoLoja() {
    }

    public void atualizar(String nome, String documento, String endereco, String telefone, String mensagemRodape,
                          BigDecimal percentualServico, boolean servicoMarcado, Instant agora) {
        if (percentualServico == null || percentualServico.signum() < 0 || percentualServico.compareTo(LIMITE_SERVICO) > 0) {
            throw new RegraDeNegocioException("Taxa de serviço deve estar entre 0% e 20%.");
        }
        this.nome = nome.trim();
        this.documento = vazioParaNulo(documento);
        this.endereco = vazioParaNulo(endereco);
        this.telefone = vazioParaNulo(telefone);
        this.mensagemRodape = vazioParaNulo(mensagemRodape);
        this.percentualServico = percentualServico;
        this.servicoMarcado = servicoMarcado;
        this.atualizadoEm = agora;
    }

    void definirDiasEntregaGratis(Set<DayOfWeek> dias, Instant agora) {
        this.diasEntregaGratis = dias.isEmpty() ? null
                : dias.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
        this.atualizadoEm = agora;
    }

    public Set<DayOfWeek> getDiasEntregaGratis() {
        if (diasEntregaGratis == null || diasEntregaGratis.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        return Arrays.stream(diasEntregaGratis.split(",")).map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    void definirNomesMenu(String json, Instant agora) {
        this.nomesMenu = json;
        this.atualizadoEm = agora;
    }

    private static String vazioParaNulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public String getNome() {
        return nome;
    }

    public String getDocumento() {
        return documento;
    }

    public String getEndereco() {
        return endereco;
    }

    public String getTelefone() {
        return telefone;
    }

    public String getMensagemRodape() {
        return mensagemRodape;
    }

    public BigDecimal getPercentualServico() {
        return percentualServico;
    }

    public boolean isServicoMarcado() {
        return servicoMarcado;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public String getNomesMenu() {
        return nomesMenu;
    }
}
