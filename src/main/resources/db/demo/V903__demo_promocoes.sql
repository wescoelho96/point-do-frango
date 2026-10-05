-- Promoções de exemplo (perfil "demo"): valem de sexta a domingo.
INSERT INTO promocao (nome, produto_compra_id, quantidade_compra, produto_brinde_id, quantidade_brinde, dias_semana)
SELECT 'Combo Especial ganha Coca 2 L', c.id, 1, b.id, 1, 'FRIDAY,SATURDAY,SUNDAY'
FROM produto c, produto b WHERE c.nome = 'Combo Especial (12 iscas)' AND b.nome = 'Coca-Cola 2 L';

UPDATE despesa_fixa SET valor_variavel = TRUE WHERE descricao IN ('Energia', 'Água', 'Gás');
