-- Mesmo motivo da V2: tabelas novas não podem ficar expostas na API REST pública do Supabase.
-- A tabela cliente tem dado pessoal, então isso aqui é obrigatório.
ALTER TABLE bairro          ENABLE ROW LEVEL SECURITY;
ALTER TABLE cliente         ENABLE ROW LEVEL SECURITY;
ALTER TABLE colaborador     ENABLE ROW LEVEL SECURITY;
ALTER TABLE caixa           ENABLE ROW LEVEL SECURITY;
ALTER TABLE caixa_movimento ENABLE ROW LEVEL SECURITY;
