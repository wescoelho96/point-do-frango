-- Mesmo motivo da V2: a tabela nova não pode ficar exposta na API REST pública do Supabase.
ALTER TABLE insumo_embalagem ENABLE ROW LEVEL SECURITY;
