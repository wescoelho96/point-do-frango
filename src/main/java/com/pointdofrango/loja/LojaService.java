package com.pointdofrango.loja;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class LojaService {

    public record DadosLoja(String nome, String documento, String endereco, String telefone, String mensagemRodape,
                            BigDecimal percentualServico, boolean servicoMarcado, Map<String, String> menus,
                            Set<DayOfWeek> diasEntregaGratis) {
    }

    /** Telas que podem ser renomeadas. Qualquer outra chave é recusada. */
    static final Set<String> MENUS = Set.of("pdv", "comandas", "cozinha", "pedidos", "entregas", "caixa", "clientes",
            "estoque", "cardapio", "financeiro", "config");
    private static final int MAX_NOME_MENU = 24;
    private static final Logger log = LoggerFactory.getLogger(LojaService.class);

    private final ConfiguracaoLojaRepository repositorio;
    private final ObjectMapper json;
    private final Clock clock;

    LojaService(ConfiguracaoLojaRepository repositorio, ObjectMapper json, Clock clock) {
        this.repositorio = repositorio;
        this.json = json;
        this.clock = clock;
    }

    public DadosLoja dados(ConfiguracaoLoja c) {
        return new DadosLoja(c.getNome(), c.getDocumento(), c.getEndereco(), c.getTelefone(), c.getMensagemRodape(),
                c.getPercentualServico(), c.isServicoMarcado(), lerMenus(c.getNomesMenu()), c.getDiasEntregaGratis());
    }

    @Transactional
    public ConfiguracaoLoja definirDiasEntregaGratis(Set<DayOfWeek> dias) {
        ConfiguracaoLoja c = obter();
        c.definirDiasEntregaGratis(dias, Instant.now(clock));
        return c;
    }

    /** Grava só o que mudou: nome vazio volta ao padrão da tela. */
    @Transactional
    public ConfiguracaoLoja renomearMenus(Map<String, String> nomes) {
        Map<String, String> validos = new TreeMap<>();
        nomes.forEach((chave, nome) -> {
            if (!MENUS.contains(chave)) {
                throw new RegraDeNegocioException("Menu desconhecido: " + chave);
            }
            String limpo = nome == null ? "" : nome.strip();
            if (limpo.length() > MAX_NOME_MENU || limpo.chars().anyMatch(Character::isISOControl)) {
                throw new RegraDeNegocioException("O nome do menu deve ter até " + MAX_NOME_MENU + " caracteres.");
            }
            if (!limpo.isEmpty()) {
                validos.put(chave, limpo);
            }
        });
        ConfiguracaoLoja c = obter();
        try {
            c.definirNomesMenu(validos.isEmpty() ? null : json.writeValueAsString(validos), Instant.now(clock));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    private Map<String, String> lerMenus(String texto) {
        if (texto == null || texto.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> lido = json.readValue(texto, new TypeReference<LinkedHashMap<String, String>>() { });
            lido.keySet().retainAll(MENUS);
            return lido;
        } catch (JsonProcessingException e) {
            log.warn("Nomes de menu inválidos no banco; usando os padrões");
            return Map.of();
        }
    }

    @Transactional(readOnly = true)
    public ConfiguracaoLoja obter() {
        return repositorio.findById(ConfiguracaoLoja.ID_UNICO)
                .orElseThrow(() -> new IllegalStateException("Configuração da loja ausente (migration V5)."));
    }

    @Transactional
    public ConfiguracaoLoja atualizar(DadosLoja d) {
        ConfiguracaoLoja c = obter();
        c.atualizar(d.nome(), d.documento(), d.endereco(), d.telefone(), d.mensagemRodape(), d.percentualServico(),
                d.servicoMarcado(), Instant.now(clock));
        return c;
    }
}
