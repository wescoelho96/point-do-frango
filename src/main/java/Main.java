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

    // Conexão Segura com o Supabase
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
        
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Nossas Rotas
        server.createContext("/", new DashboardHandler());

        server.setExecutor(null);
        System.out.println("=== ERP POINT DO FRANGO INICIADO NA PORTA " + port + " ===");
        server.start();
    }

    // =========================================================================
    // HANDLER DO DASHBOARD (A Nova Interface Profissional)
    // =========================================================================
    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Ignora o "ping" do Render para limpar os logs
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            StringBuilder linhasTabela = new StringBuilder();

            // Busca os dados no Banco
            try (Connection conn = conectarBanco();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM produtos ORDER BY id")) {
                
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String nome = rs.getString("nome");
                    String categoria = rs.getString("categoria");
                    int quantidade = rs.getInt("quantidade");
                    double preco = rs.getDouble("preco_venda");

                    // Monta cada linha da tabela já com os botões de ação
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
            } catch (SQLException e) {
                linhasTabela.append("<tr><td colspan='6' style='color:red;'>Erro no banco: ").append(e.getMessage()).append("</td></tr>");
            }

            // O nosso novo layout completo usando Text Blocks do Java 17
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
                        
                        /* Menu Lateral */
                        .sidebar { width: 250px; background: var(--sidebar); color: white; display: flex; flex-direction: column; }
                        .sidebar h2 { text-align: center; padding: 20px 0; margin: 0; background: #0f172a; font-size: 1.2rem; }
                        .menu-item { padding: 15px 20px; color: #cbd5e1; text-decoration: none; border-bottom: 1px solid #334155; transition: 0.2s; }
                        .menu-item:hover, .menu-item.active { background: var(--primary); color: white; }
                        
                        /* Área Principal */
                        .main-content { flex: 1; padding: 30px; overflow-y: auto; }
                        .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 30px; }
                        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                        
                        /* Tabela e Botões */
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
                    </style>
                </head>
                <body>

                    <!-- Menu Lateral -->
                    <div class="sidebar">
                        <h2>🍗 Point do Frango</h2>
                        <a href="#" class="menu-item">📊 Dashboard Financeiro</a>
                        <a href="#" class="menu-item">🛒 PDV / Caixa</a>
                        <a href="#" class="menu-item active">📦 Controle de Estoque</a>
                        <a href="#" class="menu-item">🛵 Entregadores</a>
                        <a href="#" class="menu-item">⚙️ Configurações</a>
                    </div>

                    <!-- Conteúdo Central -->
                    <div class="main-content">
                        <div class="header">
                            <h2>Gestão de Estoque e Cardápio</h2>
                            <button class="btn btn-primary" onclick="alert('Logo abriremos a tela de Novo Produto!')">➕ Novo Produto</button>
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
                                    %s <!-- Aqui o Java injeta as linhas da tabela automaticamente -->
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <script>
                        function editarProduto(id) {
                            alert("Editando o produto ID: " + id + "\\n\\nNa próxima etapa, isso vai abrir a tela para mudar o nome e o preço!");
                        }
                        function reporEstoque(id) {
                            let qtd = prompt("Quantas unidades chegaram do fornecedor para o produto " + id + "?");
                            if(qtd) alert("Vamos somar " + qtd + " unidades ao estoque. Logo conectaremos isso ao banco!");
                        }
                    </script>
                </body>
                </html>
                """.formatted(linhasTabela.toString());

            byte[] res = htmlCompleto.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, res.length);
            OutputStream os = exchange.getResponseBody();
            os.write(res); 
            os.close();
        }
    }
}
