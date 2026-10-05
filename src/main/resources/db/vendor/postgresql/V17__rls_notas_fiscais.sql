-- Mesmo motivo da V2: tabelas novas não podem ficar expostas na API REST pública do Supabase.
ALTER TABLE nota_fiscal_compra ENABLE ROW LEVEL SECURITY;
ALTER TABLE item_fornecedor    ENABLE ROW LEVEL SECURITY;
