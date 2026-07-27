import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Locale;

public class Main {

    private static Connection conectarBanco() throws SQLException {
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl == null || dbUrl.isEmpty()) {
            throw new SQLException("Variavel de ambiente DATABASE_URL nao encontrada.");
        }
        return DriverManager.getConnection(dbUrl);
    }

    public static void main(String[] args) throws IOException {
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null && !portEnv.isEmpty()) ? Integer.parseInt(portEnv) : 8080;
        
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", new DashboardHandler());
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); 
        
        System.out.println("=== ERP POINT DO FRANGO INICIADO NA PORTA " + port + " ===");
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
                                <button class="btn btn-sm btn-blue" onclick="editarProduto(%d)">✏️ Editar</button>
                                <button class="btn btn-sm btn-green" onclick="reporEstoque(%d)">📦 Repor</button>
                            </td>
                        </tr>
                        """, id, nome, categoria, quantidade, preco, id, id));
                }
            } catch (Exception e) {
                linhasTabela.append("<tr><td colspan='6' style='color:red;'>Erro no banco: ").append(e.getMessage()).append("</td></tr>");
            }

            // O nosso HTML agora está protegido contra o bug da porcentagem!
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
                        
                        .main-content { flex: 1; padding: 30px; overflow-y: auto; }
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
                        .btn-sm { padding: 6px 10px; font-size: 0.85rem; margin-right: 5px; }
                        
                        .user-profile { margin-top: auto; padding: 15px; background: #0f172a; text-align: center; font-size: 0.85rem; color: #10b981; }
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
                            <button class="btn btn-primary" onclick="alert('Logo abriremos a tela para cadastrar Porções, Combos e Bebidas!')">➕ Novo Produto</button>
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
                    <script>
                        function editarProduto(id) {
                            alert("Módulo em construção: Aqui você poderá alterar o nome e o valor de venda do produto ID " + id);
                        }
                        function reporEstoque(id) {
                            let qtd = prompt("Quantas unidades CHEGARAM do fornecedor/mercado para somar ao estoque deste produto?");
                            if(qtd && !isNaN(qtd)) {
                                alert("Excelente! Logo o sistema irá somar " + qtd + " unidades no seu estoque do banco de dados!");
                            }
                        }
                    </script>
                </body>
                </html>
                """.replace("LINHAS_DA_TABELA_AQUI", linhasTabela.toString());

            byte[] res = htmlCompleto.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, res.length);
            OutputStream os = exchange.getResponseBody();
            os.write(res); 
            os.close();
        }
    }
}
