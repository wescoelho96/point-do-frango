-- Bloqueia a API REST pública do Supabase na tabela nova (o acesso é só pela aplicação).
ALTER TABLE insumo_validade ENABLE ROW LEVEL SECURITY;
