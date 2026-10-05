-- Tabelas da primeira versão do sistema (antes do Flyway) que ainda podem existir no Supabase.
-- Ficam sem uso, mas não podem continuar expostas na API REST pública.
ALTER TABLE IF EXISTS produtos    ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS vendas      ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS itens_venda ENABLE ROW LEVEL SECURITY;
