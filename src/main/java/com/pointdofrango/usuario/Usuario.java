package com.pointdofrango.usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** Hash BCrypt; a senha em texto nunca é gravada. */
    @Column(nullable = false, length = 100)
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Perfil perfil;

    @Column(nullable = false)
    private boolean ativo = true;

    @Column(nullable = false)
    private Instant criadoEm;

    protected Usuario() {
    }

    public Usuario(String nome, String username, String senhaHash, Perfil perfil, Instant criadoEm) {
        this.nome = nome.trim();
        this.username = username.trim().toLowerCase();
        this.senhaHash = senhaHash;
        this.perfil = perfil;
        this.criadoEm = criadoEm;
    }

    public void trocarSenha(String novoHash) {
        this.senhaHash = novoHash;
    }

    public void alterarAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getUsername() {
        return username;
    }

    public String getSenhaHash() {
        return senhaHash;
    }

    public Perfil getPerfil() {
        return perfil;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
