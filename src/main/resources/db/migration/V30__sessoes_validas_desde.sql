-- Tokens emitidos antes deste instante deixam de valer (senha trocada, usuário desativado ou perfil alterado).
ALTER TABLE usuario ADD COLUMN sessoes_validas_desde TIMESTAMP WITH TIME ZONE;
