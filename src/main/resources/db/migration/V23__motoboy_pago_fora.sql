-- Entrega de dia anterior lançada depois: o motoboy já recebeu por fora do caixa (PIX lançado em Saídas).
ALTER TABLE pedido ADD COLUMN motoboy_pago_fora BOOLEAN NOT NULL DEFAULT FALSE;
