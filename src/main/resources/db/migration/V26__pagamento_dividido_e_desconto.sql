-- Conta dividida: o pedido fica com forma_pagamento = 'DIVIDIDO' e cada parte (forma + valor) vem aqui.
CREATE TABLE pedido_pagamento (
    pedido_id BIGINT        NOT NULL REFERENCES pedido(id),
    ordem     INT           NOT NULL,
    forma     VARCHAR(20)   NOT NULL,
    valor     NUMERIC(10,2) NOT NULL,
    PRIMARY KEY (pedido_id, ordem)
);

-- Desconto dado no balcão sobre o valor dos itens (a taxa de entrega não entra).
ALTER TABLE pedido ADD COLUMN desconto NUMERIC(10,2);
