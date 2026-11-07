-- Rateio "contas primeiro": a sobra de cada venda paga as contas fixas do mês e, depois de cobertas, vira lucro.
-- Substitui o percentual fixo de contas por venda, que dava prejuízo em toda venda enquanto o movimento é baixo.
ALTER TABLE configuracao_financeira ADD COLUMN contas_primeiro BOOLEAN NOT NULL DEFAULT TRUE;
