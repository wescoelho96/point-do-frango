-- Insumo que não bloqueia a venda (óleo reaproveitado por dias): sai do estoque, mas pode ficar negativo.
ALTER TABLE insumo ADD COLUMN trava_venda BOOLEAN NOT NULL DEFAULT TRUE;

-- Linha da ficha usada no lugar de outra quando ela acaba (2 caixas Assaí no lugar de 1 caixa 30x30).
ALTER TABLE ficha_tecnica_item ADD COLUMN alternativa_de_insumo_id BIGINT REFERENCES insumo(id);
