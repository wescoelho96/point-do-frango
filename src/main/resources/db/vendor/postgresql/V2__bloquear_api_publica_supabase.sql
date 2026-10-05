-- O Supabase publica automaticamente todas as tabelas do schema "public" numa API REST
-- (PostgREST), acessível com a "anon key". Este sistema não usa essa API: ele conecta
-- direto via JDBC com o usuário dono do banco, que ignora RLS.
-- Ligar RLS sem nenhuma policy = ninguém lê nada pela API pública. Defesa em profundidade.
ALTER TABLE usuario                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE insumo                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE produto                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE ficha_tecnica_item      ENABLE ROW LEVEL SECURITY;
ALTER TABLE pedido                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE pedido_item             ENABLE ROW LEVEL SECURITY;
ALTER TABLE movimentacao_estoque    ENABLE ROW LEVEL SECURITY;
ALTER TABLE configuracao_financeira ENABLE ROW LEVEL SECURITY;
ALTER TABLE despesa_fixa            ENABLE ROW LEVEL SECURITY;
