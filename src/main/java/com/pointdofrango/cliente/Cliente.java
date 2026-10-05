package com.pointdofrango.cliente;

import com.pointdofrango.entrega.Bairro;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Cliente de entrega (WhatsApp/telefone). Só o que o motoboy precisa: nome, telefone e endereço.
 * Sem CPF, e-mail ou data de nascimento de propósito.
 */
@Entity
@Table(name = "cliente")
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(nullable = false, unique = true, length = 15)
    private String telefone;

    @Column(length = 200)
    private String endereco;

    @Column(length = 150)
    private String referencia;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bairro_id")
    private Bairro bairro;

    @Column(nullable = false)
    private Instant criadoEm;

    @Column(nullable = false)
    private Instant ultimoPedidoEm;

    protected Cliente() {
    }

    Cliente(String telefone, Instant agora) {
        this.telefone = normalizarTelefone(telefone);
        this.criadoEm = agora;
        this.ultimoPedidoEm = agora;
    }

    void atualizar(String nome, String endereco, String referencia, Bairro bairro) {
        if (nome == null || nome.isBlank()) {
            throw new RegraDeNegocioException("Informe o nome do cliente.");
        }
        this.nome = nome.trim();
        this.endereco = vazioParaNulo(endereco);
        this.referencia = vazioParaNulo(referencia);
        this.bairro = bairro;
    }

    void registrarPedido(Instant quando) {
        this.ultimoPedidoEm = quando;
    }

    /**
     * Só dígitos, para "(11) 98765-4321" e "11987654321" serem o mesmo cliente.
     * Aceita DDD + número (10 ou 11 dígitos), com ou sem o 55 na frente.
     */
    public static String normalizarTelefone(String telefone) {
        String digitos = telefone == null ? "" : telefone.replaceAll("\\D", "");
        if (digitos.length() > 11 && digitos.startsWith("55")) {
            digitos = digitos.substring(2);
        }
        if (digitos.length() < 10 || digitos.length() > 11) {
            throw new RegraDeNegocioException("Telefone inválido. Use DDD + número, ex.: (11) 98765-4321.");
        }
        return digitos;
    }

    private static String vazioParaNulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getTelefone() {
        return telefone;
    }

    public String getEndereco() {
        return endereco;
    }

    public String getReferencia() {
        return referencia;
    }

    public Bairro getBairro() {
        return bairro;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getUltimoPedidoEm() {
        return ultimoPedidoEm;
    }
}
