-- Grupos, bebidas por marca/tamanho e fornecedores de exemplo (perfil "demo").
UPDATE insumo SET grupo = 'Carnes'           WHERE nome IN ('Isca de frango', 'Carne para espeto');
UPDATE insumo SET grupo = 'Congelados'       WHERE nome IN ('Batata palito congelada', 'Anel de cebola congelado');
UPDATE insumo SET grupo = 'Óleos e molhos'   WHERE nome IN ('Óleo de fritura', 'Molho da casa', 'Tempero / sal');
UPDATE insumo SET grupo = 'Embalagens'       WHERE nome IN ('Embalagem porção', 'Espeto de bambu');
UPDATE insumo SET grupo = 'Refrigerantes', marca = 'Variadas' WHERE nome = 'Refrigerante lata';

INSERT INTO insumo (nome, unidade, estoque_atual, estoque_minimo, custo_unitario, grupo, marca) VALUES
('Coca-Cola 2 L',          'UNIDADE', 12, 4, 9.50, 'Refrigerantes', 'Coca-Cola'),
('Coca-Cola Zero 2 L',     'UNIDADE',  6, 2, 9.50, 'Refrigerantes', 'Coca-Cola'),
('Coca-Cola 600 ml',       'UNIDADE', 24, 6, 4.20, 'Refrigerantes', 'Coca-Cola'),
('Guaraná Antarctica 2 L', 'UNIDADE', 10, 4, 7.80, 'Refrigerantes', 'Antarctica'),
('Heineken long neck',     'UNIDADE', 24, 12, 5.90, 'Cervejas',     'Heineken'),
('Brahma lata 350 ml',     'UNIDADE', 36, 12, 3.40, 'Cervejas',     'Brahma'),
('Água sem gás 500 ml',    'UNIDADE', 24, 6, 1.30, 'Águas',         'Crystal');

-- Cada bebida também é um produto do cardápio, com ficha técnica de 1 unidade
INSERT INTO produto (nome, descricao, categoria, preco_venda) VALUES
('Coca-Cola 2 L',          NULL, 'BEBIDA', 16.00),
('Coca-Cola Zero 2 L',     NULL, 'BEBIDA', 16.00),
('Coca-Cola 600 ml',       NULL, 'BEBIDA',  8.00),
('Guaraná Antarctica 2 L', NULL, 'BEBIDA', 14.00),
('Heineken long neck',     NULL, 'BEBIDA', 12.00),
('Brahma lata 350 ml',     NULL, 'BEBIDA',  7.00),
('Água sem gás 500 ml',    NULL, 'BEBIDA',  4.00);

INSERT INTO ficha_tecnica_item (produto_id, insumo_id, modo, quantidade_informada, quantidade)
SELECT p.id, i.id, 'UNIDADE_BASE', 1, 1
FROM produto p JOIN insumo i ON i.nome = p.nome
WHERE p.categoria = 'BEBIDA' AND i.grupo IN ('Refrigerantes', 'Cervejas', 'Águas') AND i.nome <> 'Refrigerante lata';

INSERT INTO fornecedor (nome, fornece, telefone) VALUES
('Atacadão',             'mercado: bebidas, óleo, embalagens', NULL),
('Distribuidora Frango', 'isca de frango (sassami)',          NULL);
