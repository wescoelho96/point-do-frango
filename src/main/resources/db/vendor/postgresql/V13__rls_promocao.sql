-- Mesmo motivo da V2: tabelas novas não podem ficar expostas na API REST pública do Supabase.
ALTER TABLE promocao ENABLE ROW LEVEL SECURITY;
