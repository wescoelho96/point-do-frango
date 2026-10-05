-- O caixa soma as vendas pagas a partir deste momento: o fechamento do caixa anterior ou o
-- começo do dia, o que for mais tarde. Assim uma comanda paga minutos antes de abrir o caixa
-- não fica de fora do fechamento.
ALTER TABLE caixa ADD COLUMN vendas_desde TIMESTAMP WITH TIME ZONE;
UPDATE caixa SET vendas_desde = aberto_em;
