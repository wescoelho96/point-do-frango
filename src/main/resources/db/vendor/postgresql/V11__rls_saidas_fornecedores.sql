-- Mesmo motivo da V2: tabelas novas não podem ficar expostas na API REST pública do Supabase.
ALTER TABLE fornecedor ENABLE ROW LEVEL SECURITY;
ALTER TABLE saida      ENABLE ROW LEVEL SECURITY;
