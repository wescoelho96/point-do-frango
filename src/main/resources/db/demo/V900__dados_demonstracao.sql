-- Dados de EXEMPLO (perfil "demo"). Custos e preços são estimativas: ajuste pela tela.
-- O estoque é controlado em kg, L ou un. A ficha técnica pode ser digitada em g/ml,
-- em unidades de uso (iscas) ou por rendimento da embalagem; tudo é convertido.

INSERT INTO insumo (nome, unidade, estoque_atual, estoque_minimo, custo_unitario, unidade_uso_nome, unidade_uso_por_unidade) VALUES
('Isca de frango',           'QUILOGRAMA', 36.000, 6.000, 24.900000, 'isca', 12),
('Batata palito congelada',  'QUILOGRAMA', 16.000, 4.000, 13.500000, NULL, NULL),
('Anel de cebola congelado', 'QUILOGRAMA', 10.000, 2.000, 32.000000, NULL, NULL),
('Óleo de fritura',          'LITRO',      27.000, 4.500,  9.800000, NULL, NULL),
('Embalagem porção',         'UNIDADE',   400.000, 80.000, 0.850000, NULL, NULL),
('Molho da casa',            'QUILOGRAMA',  4.000, 1.000, 25.000000, NULL, NULL),
('Tempero / sal',            'QUILOGRAMA',  2.000, 0.500, 12.000000, NULL, NULL),
('Refrigerante lata',        'UNIDADE',   120.000, 24.000, 3.200000, NULL, NULL),
('Carne para espeto',        'QUILOGRAMA',  0.000, 2.000, 29.900000, NULL, NULL),
('Espeto de bambu',          'UNIDADE',     0.000, 50.000, 0.080000, NULL, NULL);

-- Como cada insumo chega do fornecedor
INSERT INTO insumo_embalagem (insumo_id, nome, conteudo)
SELECT i.id, e.nome, e.conteudo FROM (VALUES
    ('Isca de frango',           'Saco 6 kg',      6.000),
    ('Isca de frango',           'Pacote 1 kg',    1.000),
    ('Batata palito congelada',  'Saco 2 kg',      2.000),
    ('Anel de cebola congelado', 'Pacote 1 kg',    1.000),
    ('Óleo de fritura',          'Lata 900 ml',    0.900),
    ('Embalagem porção',         'Pacote 100 un', 100.000),
    ('Refrigerante lata',        'Fardo 12 un',   12.000),
    ('Espeto de bambu',          'Pacote 100 un', 100.000)
) AS e(insumo, nome, conteudo)
JOIN insumo i ON i.nome = e.insumo;

INSERT INTO produto (nome, descricao, categoria, preco_venda, ativo) VALUES
('Isca de Frango (6 un)',   'Iscas empanadas com molho da casa',           'PORCAO',    38.00, TRUE),
('Batata Frita 300g',       'Batata palito crocante',                      'PORCAO',    22.00, TRUE),
('Anel de Cebola 300g',     'Anéis empanados com molho',                   'PORCAO',    26.00, TRUE),
('Refrigerante Lata',       '350 ml',                                      'BEBIDA',     6.00, TRUE),
('Combo Casal',             '6 iscas + batata 300g + 2 refrigerantes',    'COMBO',     72.00, TRUE),
('Combo Especial (12 iscas)', '12 iscas + batata 300g + anel 300g',        'COMBO',     99.00, TRUE),
('Espetinho de Carne',      'Em breve',                                    'ESPETINHO',  9.00, FALSE);

-- Ficha técnica: (modo, quantidade digitada). A coluna "quantidade" é a conversão para a
-- unidade do insumo: SUBUNIDADE ÷ 1000 (g→kg, ml→L), UNIDADE_USO ÷ iscas por kg.
INSERT INTO ficha_tecnica_item (produto_id, insumo_id, modo, quantidade_informada, quantidade)
SELECT p.id, i.id, f.modo, CAST(f.qtd AS NUMERIC(16,6)),
       -- CAST antes de dividir: 50 / 1000 entre inteiros daria 0
       CASE f.modo WHEN 'SUBUNIDADE'  THEN CAST(f.qtd AS NUMERIC(20,10)) / 1000
                   WHEN 'UNIDADE_USO' THEN CAST(f.qtd AS NUMERIC(20,10)) / i.unidade_uso_por_unidade
                   ELSE CAST(f.qtd AS NUMERIC(20,10)) END
FROM (VALUES
    ('Isca de Frango (6 un)',     'Isca de frango',           'UNIDADE_USO',   6),
    ('Isca de Frango (6 un)',     'Óleo de fritura',          'SUBUNIDADE',   50),
    ('Isca de Frango (6 un)',     'Molho da casa',            'SUBUNIDADE',   30),
    ('Isca de Frango (6 un)',     'Embalagem porção',         'UNIDADE_BASE',  1),
    ('Batata Frita 300g',         'Batata palito congelada',  'SUBUNIDADE',  300),
    ('Batata Frita 300g',         'Óleo de fritura',          'SUBUNIDADE',   50),
    ('Batata Frita 300g',         'Tempero / sal',            'SUBUNIDADE',    5),
    ('Batata Frita 300g',         'Embalagem porção',         'UNIDADE_BASE',  1),
    ('Anel de Cebola 300g',       'Anel de cebola congelado', 'SUBUNIDADE',  300),
    ('Anel de Cebola 300g',       'Óleo de fritura',          'SUBUNIDADE',   50),
    ('Anel de Cebola 300g',       'Molho da casa',            'SUBUNIDADE',   30),
    ('Anel de Cebola 300g',       'Embalagem porção',         'UNIDADE_BASE',  1),
    ('Refrigerante Lata',         'Refrigerante lata',        'UNIDADE_BASE',  1),
    ('Combo Casal',               'Isca de frango',           'UNIDADE_USO',   6),
    ('Combo Casal',               'Batata palito congelada',  'SUBUNIDADE',  300),
    ('Combo Casal',               'Óleo de fritura',          'SUBUNIDADE',  100),
    ('Combo Casal',               'Molho da casa',            'SUBUNIDADE',   30),
    ('Combo Casal',               'Tempero / sal',            'SUBUNIDADE',    5),
    ('Combo Casal',               'Embalagem porção',         'UNIDADE_BASE',  2),
    ('Combo Casal',               'Refrigerante lata',        'UNIDADE_BASE',  2),
    ('Combo Especial (12 iscas)', 'Isca de frango',           'UNIDADE_USO',  12),
    ('Combo Especial (12 iscas)', 'Batata palito congelada',  'SUBUNIDADE',  300),
    ('Combo Especial (12 iscas)', 'Anel de cebola congelado', 'SUBUNIDADE',  300),
    ('Combo Especial (12 iscas)', 'Óleo de fritura',          'SUBUNIDADE',  150),
    ('Combo Especial (12 iscas)', 'Molho da casa',            'SUBUNIDADE',   60),
    ('Combo Especial (12 iscas)', 'Tempero / sal',            'SUBUNIDADE',    5),
    ('Combo Especial (12 iscas)', 'Embalagem porção',         'UNIDADE_BASE',  3),
    ('Espetinho de Carne',        'Carne para espeto',        'SUBUNIDADE',  120),
    ('Espetinho de Carne',        'Espeto de bambu',          'UNIDADE_BASE',  1)
) AS f(produto, insumo, modo, qtd)
JOIN produto p ON p.nome = f.produto
JOIN insumo  i ON i.nome = f.insumo;

INSERT INTO despesa_fixa (descricao, valor_mensal, dia_vencimento) VALUES
('Aluguel',        1500.00,  5),
('Energia',         350.00, 10),
('Água',            120.00, 10),
('Internet',        100.00, 15),
('Gás',             180.00, 20),
('Contador / MEI',  250.00, 20);
