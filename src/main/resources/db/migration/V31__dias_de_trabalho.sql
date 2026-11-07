-- Dias da semana em que a loja abre (MONDAY,TUESDAY...). As contas do mês viram uma cota por dia trabalhado;
-- nulo = todos os dias.
ALTER TABLE configuracao_financeira ADD COLUMN dias_trabalho VARCHAR(100);
