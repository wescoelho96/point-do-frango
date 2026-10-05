package com.pointdofrango.usuario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regras de acesso ponta a ponta (filtros do Spring Security + controllers). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SegurancaIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsuarioService usuarios;

    @BeforeEach
    void atendente() {
        usuarios.garantirUsuario("Caixa", "caixa-teste", "senha-do-caixa", Perfil.ATENDENTE);
    }

    private String token(String username, String senha) throws Exception {
        String corpo = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"senha\":\"%s\"}".formatted(username, senha)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode no = json.readTree(corpo);
        return no.get("token").asText();
    }

    @Test
    @DisplayName("Sem token: 401")
    void semToken() throws Exception {
        mvc.perform(get("/api/v1/pedidos/cozinha")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Admin criado pelas variáveis de ambiente acessa o painel")
    void adminAcessaPainel() throws Exception {
        String t = token("dono", "senha-do-dono");
        mvc.perform(get("/api/v1/painel/resumo").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.potes").exists());
    }

    @Test
    @DisplayName("Atendente opera a cozinha, mas não vê o financeiro nem cancela pedidos")
    void atendenteLimitado() throws Exception {
        String t = token("caixa-teste", "senha-do-caixa");
        mvc.perform(get("/api/v1/pedidos/cozinha").header("Authorization", "Bearer " + t)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/painel/resumo").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/financeiro/configuracao").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pedidos/1/cancelar").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Token adulterado é rejeitado")
    void tokenAdulterado() throws Exception {
        String t = token("caixa-teste", "senha-do-caixa");
        String adulterado = t.substring(0, t.length() - 3) + "abc";
        mvc.perform(get("/api/v1/pedidos/cozinha").header("Authorization", "Bearer " + adulterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Senha errada: 401 com mensagem genérica; depois de 5, bloqueia (429)")
    void forcaBruta() throws Exception {
        usuarios.garantirUsuario("Alvo", "alvo", "senha-correta", Perfil.ATENDENTE);
        for (int i = 0; i < TentativasLogin.MAX_FALHAS; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"alvo\",\"senha\":\"errada\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Usuário ou senha inválidos."));
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alvo\",\"senha\":\"senha-correta\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Integração WhatsApp exige X-Api-Key válida")
    void apiKey() throws Exception {
        String corpo = "{\"formaPagamento\":\"PIX\",\"itens\":[{\"produtoId\":999999,\"quantidade\":1}]}";
        mvc.perform(post("/api/v1/integracoes/whatsapp/pedidos").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/integracoes/whatsapp/pedidos").header("X-Api-Key", "errada")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isUnauthorized());
        // chave certa passa pelo filtro; o 404 vem da regra de negócio (produto inexistente)
        mvc.perform(post("/api/v1/integracoes/whatsapp/pedidos").header("X-Api-Key", "chave-de-teste")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Perfil COZINHA: vê e atualiza a fila, mas não lança pedido nem vê cardápio/financeiro")
    void perfilCozinha() throws Exception {
        usuarios.garantirUsuario("Cozinha", "cozinha-teste", "senha-da-cozinha", Perfil.COZINHA);
        String t = token("cozinha-teste", "senha-da-cozinha");
        mvc.perform(get("/api/v1/pedidos/cozinha").header("Authorization", "Bearer " + t)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/produtos").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/painel/resumo").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tempo real (SSE) exige login; dados da loja só o admin altera")
    void sseELoja() throws Exception {
        mvc.perform(get("/api/v1/eventos")).andExpect(status().isUnauthorized());
        String caixa = token("caixa-teste", "senha-do-caixa");
        mvc.perform(get("/api/v1/eventos").header("Authorization", "Bearer " + caixa))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/loja").header("Authorization", "Bearer " + caixa)).andExpect(status().isOk())
                .andExpect(jsonPath("$.percentualServico").value(10.0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/loja")
                .header("Authorization", "Bearer " + caixa).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"X\",\"percentualServico\":10}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Clientes (LGPD): atendente só busca pelo telefone exato; listar e apagar é do dono")
    void dadosDeClientes() throws Exception {
        String caixa = token("caixa-teste", "senha-do-caixa");
        String dono = token("dono", "senha-do-dono");
        mvc.perform(get("/api/v1/clientes/busca?telefone=11900000000").header("Authorization", "Bearer " + caixa))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/clientes").header("Authorization", "Bearer " + caixa)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/clientes/1")
                .header("Authorization", "Bearer " + caixa)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/clientes").header("Authorization", "Bearer " + dono)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/clientes/busca?telefone=abc").header("Authorization", "Bearer " + caixa))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Atendente não vê custo nem margem; bairros e equipe só o dono cadastra")
    void custosEscondidos() throws Exception {
        String caixa = token("caixa-teste", "senha-do-caixa");
        mvc.perform(get("/api/v1/produtos").header("Authorization", "Bearer " + caixa))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].custoAtual").isEmpty());
        mvc.perform(get("/api/v1/insumos").header("Authorization", "Bearer " + caixa))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].custoUnitario").isEmpty());
        mvc.perform(get("/api/v1/bairros").header("Authorization", "Bearer " + caixa)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/bairros").header("Authorization", "Bearer " + caixa)
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"X\",\"taxaEntrega\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/colaboradores").header("Authorization", "Bearer " + caixa)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/caixa/historico").header("Authorization", "Bearer " + caixa)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Criar produto pela API devolve 201 com a ficha (insumo com embalagem carregada)")
    void criarProdutoPelaApi() throws Exception {
        String dono = token("dono", "senha-do-dono");
        String insumo = mvc.perform(post("/api/v1/insumos").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Guaraná API\",\"unidade\":\"UNIDADE\",\"estoqueMinimo\":0,\"custoUnitario\":7.8,"
                                + "\"estoqueInicial\":6,\"grupo\":\"Refrigerantes\",\"embalagens\":[{\"nome\":\"Fardo 6\",\"conteudo\":6}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.grupo").value("Refrigerantes"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(insumo).get("id").asLong();
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Guaraná API\",\"categoria\":\"BEBIDA\",\"precoVenda\":14,"
                                + "\"fichaTecnica\":[{\"insumoId\":%d,\"modo\":\"UNIDADE_BASE\",\"quantidade\":1}]}".formatted(id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fichaTecnica[0].rendimentos[0].rende").value(6.0));
    }

    @Test
    @DisplayName("Cortesia: só o dono lança e só o dono vê a lista")
    void cortesiaSoDono() throws Exception {
        String caixa = token("caixa-teste", "senha-do-caixa");
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + caixa).contentType(MediaType.APPLICATION_JSON)
                .content("{\"canal\":\"BALCAO\",\"formaPagamento\":\"CORTESIA\",\"motivoCortesia\":\"x\",\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pedidos/cortesias").header("Authorization", "Bearer " + caixa)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pedidos/cortesias").header("Authorization", "Bearer " + token("dono", "senha-do-dono")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Nomes do menu: só o dono troca, e só para telas conhecidas")
    void nomesDoMenu() throws Exception {
        String caixa = token("caixa-teste", "senha-do-caixa");
        String dono = token("dono", "senha-do-dono");
        mvc.perform(put("/api/v1/loja/menus").header("Authorization", "Bearer " + caixa).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pdv\":\"Vendas\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/loja/menus")
                        .header("Authorization", "Bearer " + dono).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pdv\":\"  Vendas  \",\"estoque\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.menus.pdv").value("Vendas"))
                .andExpect(jsonPath("$.menus.estoque").doesNotExist());
        mvc.perform(put("/api/v1/loja/menus")
                        .header("Authorization", "Bearer " + dono).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"<script>\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/loja/menus")
                .header("Authorization", "Bearer " + dono).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PWA e Play Store: manifest, service worker e assetlinks são públicos")
    void arquivosDoApp() throws Exception {
        mvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk());
        mvc.perform(get("/sw.js")).andExpect(status().isOk());
        mvc.perform(get("/.well-known/assetlinks.json")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Cabeçalhos de segurança no front")
    void cabecalhos() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }
}
