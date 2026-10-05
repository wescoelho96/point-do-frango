-- As bebidas de exemplo são criadas depois da V15: aplica a mesma regra (bebida sai do balcão).
UPDATE produto SET vai_para_cozinha = FALSE WHERE categoria = 'BEBIDA';
