-- Bebida sai direto do balcão/geladeira: pedido só com bebida não precisa passar pela cozinha.
ALTER TABLE produto ADD COLUMN vai_para_cozinha BOOLEAN DEFAULT TRUE NOT NULL;
UPDATE produto SET vai_para_cozinha = FALSE WHERE categoria = 'BEBIDA';
