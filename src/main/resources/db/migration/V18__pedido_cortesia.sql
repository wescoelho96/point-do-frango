-- Cortesia: pedido enviado de graça (cliente fiel, aniversário, divulgação). Baixa o estoque pela ficha
-- técnica, vai para a cozinha e para a entrega como qualquer pedido, mas não tem receita: o custo dos
-- insumos sai do lucro. forma_pagamento = 'CORTESIA' e o motivo fica registrado.
ALTER TABLE pedido ADD COLUMN motivo_cortesia VARCHAR(150);
