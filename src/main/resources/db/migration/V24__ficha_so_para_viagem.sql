-- Item da ficha que só sai em entrega e retirada (caixa, sacola); quem come na mesa usa prato.
ALTER TABLE ficha_tecnica_item ADD COLUMN so_para_viagem BOOLEAN NOT NULL DEFAULT FALSE;
