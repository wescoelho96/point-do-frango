package com.pointdofrango.usuario;

import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

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

    /** Tokens emitidos antes disso não valem mais (ver SessaoValida). */
    private Instant sessoesValidasDesde;

    /** Telas liberadas pelo dono, separadas por vírgula; nulo = todas as do perfil. */
    @Column(length = 255)
    private String telas;

    protected Usuario() {
    }

    public Usuario(String nome, String username, String senhaHash, Perfil perfil, Instant criadoEm) {
        this.nome = nome.trim();
        this.username = username.trim().toLowerCase();
        this.senhaHash = senhaHash;
        this.perfil = perfil;
        this.criadoEm = criadoEm;
    }

    public void trocarSenha(String novoHash, Instant agora) {
        this.senhaHash = novoHash;
        encerrarSessoes(agora);
    }

    /** Derruba os logins abertos em todos os aparelhos. O token tem precisão de segundos. */
    public void encerrarSessoes(Instant agora) {
        this.sessoesValidasDesde = agora.truncatedTo(ChronoUnit.SECONDS);
    }

    public Instant getSessoesValidasDesde() {
        return sessoesValidasDesde;
    }

    /** Lista vazia ou com todas as telas do perfil volta ao padrão (tudo que o perfil pode usar). */
    public void definirAcesso(Perfil perfil, List<Tela> escolhidas) {
        List<Tela> doPerfil = Tela.doPerfil(perfil);
        if (escolhidas == null || escolhidas.isEmpty()) {
            throw new RegraDeNegocioException("Marque pelo menos uma tela.");
        }
        escolhidas.stream().filter(t -> !t.permitidaPara(perfil)).findFirst().ifPresent(t -> {
            throw new RegraDeNegocioException(
                    "O perfil " + perfil.name().toLowerCase() + " não pode usar a tela " + t.name().toLowerCase() + ".");
        });
        this.perfil = perfil;
        this.telas = escolhidas.containsAll(doPerfil) ? null
                : String.join(",", doPerfil.stream().filter(escolhidas::contains).map(Tela::name).toList());
    }

    /** As telas que este login vê, na ordem do menu. */
    public List<Tela> telas() {
        if (telas == null || telas.isBlank()) {
            return Tela.doPerfil(perfil);
        }
        List<String> nomes = Arrays.asList(telas.split(","));
        return Tela.doPerfil(perfil).stream().filter(t -> nomes.contains(t.name())).toList();
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
