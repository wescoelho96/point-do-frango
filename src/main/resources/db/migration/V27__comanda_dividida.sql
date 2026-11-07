-- Fechamento de comanda dividido entre várias pessoas/formas (as partes somam o total com serviço)
CREATE TABLE comanda_pagamento (
    comanda_id BIGINT        NOT NULL REFERENCES comanda (id),
    ordem      INTEGER       NOT NULL,
    forma      VARCHAR(20)   NOT NULL,
    valor      NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (comanda_id, ordem)
);
