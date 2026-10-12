-- Compra no crédito parcelado: cada parcela é uma saída no mês em que é paga.
-- grupo_parcelas liga as parcelas da mesma compra (remover uma remove todas).
ALTER TABLE saida ADD COLUMN parcela SMALLINT;
ALTER TABLE saida ADD COLUMN parcelas SMALLINT;
ALTER TABLE saida ADD COLUMN grupo_parcelas VARCHAR(36);

CREATE INDEX idx_saida_grupo_parcelas ON saida (grupo_parcelas);
