-- Mesmo motivo da V2: tabelas novas não podem ficar expostas na API REST pública do Supabase.
ALTER TABLE comanda           ENABLE ROW LEVEL SECURITY;
ALTER TABLE configuracao_loja ENABLE ROW LEVEL SECURITY;
