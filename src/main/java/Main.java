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

    // Gerenciador de Conexão com o Banco de Dados (Supabase/PostgreSQL)
    // A senha não fica no código por segurança. É puxada do servidor (Variável de Ambiente).
    private static Connection conectarBanco() throws SQLException {
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl == null || dbUrl.isEmpty()) {
            throw new SQLException("Erro Crítico: Variavel de ambiente DATABASE_URL nao encontrada.");
        }
        return DriverManager.getConnection(dbUrl);
    }

    public static void main(String[] args) throws IOException {
        // Inicializa o Servidor Web na porta definida pelo host (ou 8080 local)
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null && !portEnv.isEmpty()) ? Integer.parseInt(portEnv) : 8080;
        
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Rotas do Sistema
        server.createContext("/", new DashboardHandler());
        server.createContext("/api/teste-conexao", new TesteConexaoHandler());

        server.setExecutor(null);
        System.out.println("=== ERP POINT DO FRANGO INICIADO NA PORTA " + port + " ===");
        server.start();
    }

    // =========================================================================
    // HANDLERS (Controladores de Rotas)
    // =========================================================================

    // Handler para validar se a comunicação com o PostgreSQL está funcionando
    static class TesteConexaoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String resposta = "";
            int statusCode = 200;

            try (Connection conn = conectarBanco()) {
                if (conn.isValid(5)) {
                    resposta = "{\"status\":\"sucesso\", \"mensagem\":\"Conectado ao Supabase com sucesso!\"}";
                }
            } catch (SQLException e) {
                statusCode = 500;
                resposta = "{\"status\":\"erro\", \"mensagem\":\"Falha ao conectar: " + e.getMessage() + "\"}";
            }

            enviarRespostaJson(exchange, statusCode, resposta);
        }
    }

    // Handler do Dashboard Principal
    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>");
            html.append("<title>ERP - Point do Frango</title>");
            html.append("<style>");
            html.append("body { font-family: 'Segoe UI', Tahoma, sans-serif; background: #f0f2f5; margin: 0; }");
            html.append(".navbar { background: #b91c1c; color: white; padding: 15px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }");
            html.append(".container { padding: 20px; max-width: 1200px; margin: auto; }");
            html.append(".card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); margin-bottom: 20px;}");
            html.append("table { width: 100%; border-collapse: collapse; margin-top: 10px; }");
            html.append("th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }");
            html.append("th { background: #f8fafc; }");
            html.append("</style></head><body>");
            
            html.append("<div class='navbar'><h2>🍗 Point do Frango - ERP Integrado</h2></div>");
            html.append("<div class='container'>");
            
            html.append("<div class='card'><h3>📦 Estoque Atual (Direto da Nuvem)</h3>");
            html.append("<table><thead><tr><th>ID</th><th>Produto</th><th>Categoria</th><th>Estoque</th><th>Valor Venda</th></tr></thead><tbody>");

            // Busca os produtos direto do banco de dados oficial
            try (Connection conn = conectarBanco();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM produtos ORDER BY id")) {
                
                while (rs.next()) {
                    html.append("<tr>");
                    html.append("<td>").append(rs.getInt("id")).append("</td>");
                    html.append("<td>").append(rs.getString("nome")).append("</td>");
                    html.append("<td>").append(rs.getString("categoria")).append("</td>");
                    html.append("<td>").append(rs.getInt("quantidade")).append(" un</td>");
                    html.append("<td>R$ ").append(String.format(Locale.US, "%.2f", rs.getDouble("preco_venda"))).append("</td>");
                    html.append("</tr>");
                }
            } catch (SQLException e) {
                html.append("<tr><td colspan='5' style='color:red;'>Erro ao ler o banco de dados. Configure a DATABASE_URL no Render. Erro: ").append(e.getMessage()).append("</td></tr>");
            }

            html.append("</tbody></table></div>");
            html.append("</div></body></html>");

            byte[] res = html.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, res.length);
            OutputStream os = exchange.getResponseBody();
            os.write(res); 
            os.close();
        }
    }

    // Função utilitária para centralizar o envio de JSON
    private static void enviarRespostaJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}
