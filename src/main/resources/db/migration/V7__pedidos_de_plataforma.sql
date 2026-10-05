-- Pedidos que chegam pelo iFood e pela 99Food.
-- Hoje são lançados manualmente (ex.: no fim do dia, a partir do extrato do app); no futuro,
-- a integração automática usa os mesmos campos.

-- Nº do pedido na plataforma: impede lançar o mesmo pedido duas vezes.
ALTER TABLE pedido ADD COLUMN codigo_externo VARCHAR(50);
-- O que o app descontou (comissão + taxa de pagamento online) e promoções bancadas pela loja.
ALTER TABLE pedido ADD COLUMN taxa_plataforma NUMERIC(10,2);
ALTER TABLE pedido ADD COLUMN desconto_loja   NUMERIC(10,2);
ALTER TABLE pedido ADD CONSTRAINT uk_pedido_canal_codigo_externo UNIQUE (canal, codigo_externo);

-- "App delivery" genérico vira iFood (o sistema ainda não estava em produção).
UPDATE pedido SET canal = 'IFOOD' WHERE canal = 'APP_DELIVERY';

-- Taxa padrão de cada plataforma (% sobre o pedido), só para pré-preencher o lançamento.
-- iFood Plano Básico ≈ 12% de comissão + 3,2% de pagamento online. Confira no seu contrato.
ALTER TABLE configuracao_financeira ADD COLUMN taxa_ifood NUMERIC(5,2) DEFAULT 15.20 NOT NULL;
ALTER TABLE configuracao_financeira ADD COLUMN taxa_noventa_nove NUMERIC(5,2) DEFAULT 0 NOT NULL;
