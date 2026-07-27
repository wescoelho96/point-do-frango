import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Main {

    private static Connection conectarBanco() throws SQLException {
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl == null || dbUrl.isEmpty()) {
            throw new SQLException("Variavel de ambiente DATABASE_URL nao encontrada.");
        }
        return DriverManager.getConnection(dbUrl);
    }

    private static void responderErro(HttpExchange exchange, int codigoErro, String mensagem) throws IOException {
        byte[] bytes = mensagem.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(codigoErro, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    public static void main(String[] args) throws IOException {
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null && !portEnv.isEmpty()) ? Integer.parseInt(portEnv) : 8080;
        
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        
        server.createContext("/", new DashboardHandler()); 
        server.createContext("/repor", new ReporEstoqueHandler()); 
        server.createContext("/novo", new NovoProdutoHandler()); 
        server.createContext("/editar", new EditarProdutoHandler()); 
        
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); 
        server.start();
    }

    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            StringBuilder linhasTabela = new StringBuilder();

            try (Connection conn = conectarBanco();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM produtos ORDER BY id")) {
                
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String nome = rs.getString("nome");
                    String categoria = rs.getString("categoria");
                    int quantidade = rs.getInt("quantidade");
                    double preco = rs.getDouble("preco_venda");

                    linhasTabela.append(String.format(Locale.US, """
                        <tr>
                            <td>%d</td>
                            <td><strong>%s</strong></td>
                            <td><span class="badge">%s</span></td>
                            <td>%d un</td>
                            <td>R$ %.2f</td>
                            <td>
                                <button class="btn btn-sm btn-blue" 
                                        data-id="%d" 
                                        data-nome="%s" 
                                        data-categoria="%s" 
                                        data-preco="%.2f" 
                                        onclick="abrirModalEditar(this)">✏️ Editar</button>
                                <button class="btn btn-sm btn-green" onclick="reporEstoque(%d)">📦 Repor</button>
                            </td>
                        </tr>
                        """, id, nome, categoria, quantidade, preco, id, nome.replace("\"", "&quot;"), categoria, preco, id));
                }
            } catch (Exception e) {
                linhasTabela.append("<tr><td colspan='6' style='color:red;'>Erro: ").append(e.getMessage()).append("</td></tr>");
            }

            String htmlCompleto = """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Point do Frango - ERP</title>
                    <style>
                        :root { --primary: #b91c1c; --sidebar: #1e293b; --bg: #f8fafc; --text: #334155; }
                        body { font-family: 'Segoe UI', Tahoma, sans-serif; background: var(--bg); color: var(--text); margin: 0; display: flex; height: 100vh; }
                        .sidebar { width: 260px; background: var(--sidebar); color: white; display: flex; flex-direction: column; }
                        .sidebar h2 { text-align: center; padding: 20px 0; margin: 0; background: #0f172a; font-size: 1.2rem; }
                        .menu-category { font-size: 0.75rem; color: #94a3b8; text-transform: uppercase; padding: 15px 20px 5px; font-weight: bold; letter-spacing: 0.5px;}
                        .menu-item { padding: 12px 20px; color: #cbd5e1; text-decoration: none; border-bottom: 1px solid #334155; transition: 0.2s; font-size: 0.95rem; }
                        .menu-item:hover, .menu-item.active { background: var(--primary); color: white; }
                        .main-content { flex: 1; padding: 30px; overflow-y: auto; position: relative; }
                        .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 30px; }
                        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                        table { width: 100%; border-collapse: collapse; margin-top: 15px; }
                        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #e2e8f0; }
                        th { background: #f1f5f9; font-weight: 600; }
                        .badge { background: #e2e8f0; padding: 4px 8px; border-radius: 12px; font-size: 0.8rem; }
                        .btn { padding: 8px 16px; border: none; border-radius: 5px; cursor: pointer; color: white; font-weight: bold; transition: 0.2s; }
                        .btn-primary { background: var(--primary); }
                        .btn-primary:hover { background: #991b1b; }
                        .btn-blue { background: #3b82f6; }
                        .btn-green { background: #10b981; }
                        .btn-gray { background: #64748b; }
                        .btn-sm { padding: 6px 10px; font-size: 0.85rem; margin-right: 5px; }
                        .user-profile { margin-top: auto; padding: 15px; background: #0f172a; text-align: center; font-size: 0.85rem; color: #10b981; }
                        
                        .modal-overlay { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.5); justify-content: center; align-items: center; z-index: 1000; }
                        .modal-box { background: white; padding: 25px; border-radius: 8px; width: 400px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); max-height: 90vh; overflow-y: auto; }
                        .modal-box h3 { margin-top: 0; margin-bottom: 20px; color: var(--text); }
                        .form-group { margin-bottom: 12px; }
                        .form-group label { display: block; margin-bottom: 5px; font-size: 0.9rem; font-weight: bold; }
                        .form-group input, .form-group select { width: 100%; padding: 8px; border: 1px solid #cbd5e1; border-radius: 4px; box-sizing: border-box; }
                        .modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
                    </style>
                </head>
                <body>
                    <div class="sidebar">
                        <h2>🍗 Point do Frango</h2>
                        <div class="menu-category">Operação & Vendas</div>
                        <a href="#" class="menu-item">🛒 Caixa</a>
                        <a href="#" class="menu-item">🖨️ Pedidos Cozinha / Mesas</a>
                        
                        <div class="menu-category">Estoque & Produtos</div>
                        <a href="#" class="menu-item active">📦 Controle de Estoque</a>
                        
                        <div class="menu-category">Financeiro & Despesas</div>
                        <a href="#" class="menu-item">📊 Dashboard de Lucro</a>
                        <a href="#" class="menu-item">💸 Acerto Motoboy / Taxas</a>
                        <a href="#" class="menu-item">🤝 Diária Freelancers</a>
                        
                        <div class="menu-category">Integrações & Sistema</div>
                        <a href="#" class="menu-item">📱 iFood / 99Food / WhatsApp</a>
                        <a href="#" class="menu-item">⚙️ Acesso e Permissões</a>
                        
                        <div class="user-profile">
                            👤 Acesso logado: <b>PROPRIETÁRIO</b>
                        </div>
                    </div>
                    <div class="main-content">
                        <div class="header">
                            <div>
                                <h2 style="margin: 0;">Gestão de Estoque e Cardápio</h2>
                                <p style="color: #64748b; margin-top: 5px;">Módulo 1 - Estrutura Base</p>
                            </div>
                            <button class="btn btn-primary" onclick="abrirModal()">➕ Novo Produto</button>
                        </div>
                        <div class="card">
                            <table>
                                <thead>
                                    <tr>
                                        <th>ID</th>
                                        <th>Produto</th>
                                        <th>Categoria</th>
                                        <th>Estoque Atual</th>
                                        <th>Valor Venda</th>
                                        <th>Ações Rápidas</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    LINHAS_DA_TABELA_AQUI
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <div class="modal-overlay" id="modalNovoProduto">
                        <div class="modal-box">
                            <h3>Cadastrar Novo Produto</h3>
                            <div class="form-group">
                                <label>Nome do Produto</label>
                                <input type="text" id="novoNome">
                            </div>
                            <div class="form-group">
                                <label>Categoria</label>
                                <select id="novaCategoria">
                                    <option value="Porção">Porção</option>
                                    <option value="Frango">Frango</option>
                                    <option value="Bebida">Bebida</option>
                                    <option value="Combo">Combo</option>
                                </select>
                            </div>
                            <div class="form-group">
                                <label>Quantidade em Estoque (Unidades)</label>
                                <input type="number" id="novaQtd">
                            </div>
                            <div class="form-group">
                                <label>Preço de Custo (R$)</label>
                                <input type="number" id="novoCusto" step="0.01">
                            </div>
                            <div class="form-group">
                                <label>Preço de Venda (R$)</label>
                                <input type="number" id="novoPreco" step="0.01">
                            </div>
                            <div class="modal-actions">
                                <button class="btn btn-gray" onclick="fecharModal()">Cancelar</button>
                                <button class="btn btn-primary" onclick="salvarNovoProduto()">Salvar Produto</button>
                            </div>
                        </div>
                    </div>

                    <div class="modal-overlay" id="modalEditarProduto">
                        <div class="modal-box">
                            <h3>Editar Produto</h3>
                            <input type="hidden" id="editId">
                            <div class="form-group">
                                <label>Nome do Produto</label>
                                <input type="text" id="editNome">
                            </div>
                            <div class="form-group">
                                <label>Categoria</label>
                                <select id="editCategoria">
                                    <option value="Porção">Porção</option>
                                    <option value="Frango">Frango</option>
                                    <option value="Bebida">Bebida</option>
                                    <option value="Combo">Combo</option>
                                </select>
                            </div>
                            <div class="form-group">
                                <label>Preço de Venda (R$)</label>
                                <input type="number" id="editPreco" step="0.01">
                            </div>
                            <div class="modal-actions">
                                <button class="btn btn-gray" onclick="fecharModalEditar()">Cancelar</button>
                                <button class="btn btn-blue" onclick="salvarEdicaoProduto()">Atualizar Produto</button>
                            </div>
                        </div>
                    </div>

                    <script>
                        function abrirModal() { document.getElementById('modalNovoProduto').style.display = 'flex'; }
                        function fecharModal() { document.getElementById('modalNovoProduto').style.display = 'none'; }
                        function fecharModalEditar() { document.getElementById('modalEditarProduto').style.display = 'none'; }

                        function abrirModalEditar(btn) {
                            document.getElementById('editId').value = btn.getAttribute('data-id');
                            document.getElementById('editNome').value = btn.getAttribute('data-nome');
                            document.getElementById('editCategoria').value = btn.getAttribute('data-categoria');
                            document.getElementById('editPreco').value = btn.getAttribute('data-preco');
                            document.getElementById('modalEditarProduto').style.display = 'flex';
                        }
                        
                        function reporEstoque(id) {
                            let qtd = prompt("Quantas unidades chegaram?");
                            if (qtd && !isNaN(qtd) && parseInt(qtd) > 0) {
                                fetch('/repor?id=' + id + '&qtd=' + parseInt(qtd), { method: 'POST' })
                                .then(response => { if(response.ok) { window.location.reload(); } });
                            }
                        }

                        function salvarNovoProduto() {
                            let nome = document.getElementById('novoNome').value;
                            let categoria = document.getElementById('novaCategoria').value;
                            let qtd = document.getElementById('novaQtd').value;
                            let custo = document.getElementById('novoCusto').value;
                            let preco = document.getElementById('novoPreco').value;

                            if(!nome || !qtd || !preco || !custo) return;

                            let dados = new URLSearchParams({
                                'nome': nome, 'categoria': categoria, 'quantidade': qtd, 'custo': custo, 'preco': preco
                            });

                            fetch('/novo', { method: 'POST', body: dados.toString() })
                            .then(async response => {
                                if(response.ok) window.location.reload();
                                else alert(await response.text());
                            });
                        }

                        function salvarEdicaoProduto() {
                            let id = document.getElementById('editId').value;
                            let nome = document.getElementById('editNome').value;
                            let categoria = document.getElementById('editCategoria').value;
                            let preco = document.getElementById('editPreco').value;

                            if(!nome || !preco) return;

                            let dados = new URLSearchParams({
                                'id': id, 'nome': nome, 'categoria': categoria, 'preco': preco
                            });

                            fetch('/editar', { method: 'POST', body: dados.toString() })
                            .then(async response => {
                                if(response.ok) window.location.reload();
                                else alert(await response.text());
                            });
                        }
                    </script>
                </body>
                </html>
                """.replace("LINHAS_DA_TABELA_AQUI", linhasTabela.toString());

            byte[] res = htmlCompleto.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.sendResponseHeaders(200, res.length);
            OutputStream os = exchange.getResponseBody();
            os.write(res); 
            os.close();
        }
    }

    static class ReporEstoqueHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                int id = -1; int qtd = 0;
                try {
                    if (query != null) {
                        for (String par : query.split("&")) {
                            String[] valores = par.split("=");
                            if (valores.length == 2) {
                                if (valores[0].equals("id")) id = Integer.parseInt(valores[1]);
                                if (valores[0].equals("qtd")) qtd = Integer.parseInt(valores[1]);
                            }
                        }
                    }
                } catch (Exception e) {}

                if (id != -1 && qtd > 0) {
                    try (Connection conn = conectarBanco(); Statement stmt = conn.createStatement()) {
                        String sql = "UPDATE produtos SET quantidade = quantidade + " + qtd + " WHERE id = " + id;
                        if (stmt.executeUpdate(sql) > 0) exchange.sendResponseHeaders(200, -1);
                        else exchange.sendResponseHeaders(404, -1);
                    } catch (SQLException e) { exchange.sendResponseHeaders(500, -1); }
                } else { exchange.sendResponseHeaders(400, -1); }
            } else { exchange.sendResponseHeaders(405, -1); }
            exchange.close();
        }
    }

    static class NovoProdutoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, String> param = new HashMap<>();
                    for (String p : body.split("&")) {
                        String[] pair = p.split("=");
                        if (pair.length > 1) param.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    }

                    if (param.get("nome") == null || param.get("categoria") == null || param.get("quantidade") == null || param.get("preco") == null || param.get("custo") == null) {
                        responderErro(exchange, 400, "Dados incompletos."); return;
                    }

                    int qtd = Integer.parseInt(param.get("quantidade"));
                    double custo = Double.parseDouble(param.get("custo").replace(",", "."));
                    double preco = Double.parseDouble(param.get("preco").replace(",", "."));

                    String sql = "INSERT INTO produtos (nome, categoria, quantidade, preco_custo, preco_venda) VALUES (?, ?, ?, ?, ?)";
                    try (Connection conn = conectarBanco(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setString(1, param.get("nome"));
                        pstmt.setString(2, param.get("categoria"));
                        pstmt.setInt(3, qtd);
                        pstmt.setDouble(4, custo);
                        pstmt.setDouble(5, preco);
                        pstmt.executeUpdate();
                        exchange.sendResponseHeaders(200, -1);
                    } catch (SQLException e) { responderErro(exchange, 500, e.getMessage()); return; }
                } catch (Exception e) { responderErro(exchange, 500, e.getMessage()); }
            } else { responderErro(exchange, 405, "Metodo nao permitido."); }
            exchange.close();
        }
    }

    static class EditarProdutoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, String> param = new HashMap<>();
                    for (String p : body.split("&")) {
                        String[] pair = p.split("=");
                        if (pair.length > 1) param.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    }

                    if (param.get("id") == null || param.get("nome") == null || param.get("categoria") == null || param.get("preco") == null) {
                        responderErro(exchange, 400, "Dados incompletos."); return;
                    }

                    int id = Integer.parseInt(param.get("id"));
                    double preco = Double.parseDouble(param.get("preco").replace(",", "."));

                    String sql = "UPDATE produtos SET nome = ?, categoria = ?, preco_venda = ? WHERE id = ?";
                    try (Connection conn = conectarBanco(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setString(1, param.get("nome"));
                        pstmt.setString(2, param.get("categoria"));
                        pstmt.setDouble(3, preco);
                        pstmt.setInt(4, id);
                        pstmt.executeUpdate();
                        exchange.sendResponseHeaders(200, -1);
                    } catch (SQLException e) { responderErro(exchange, 500, e.getMessage()); return; }
                } catch (Exception e) { responderErro(exchange, 500, e.getMessage()); }
            } else { responderErro(exchange, 405, "Metodo nao permitido."); }
            exchange.close();
        }
    }
}
