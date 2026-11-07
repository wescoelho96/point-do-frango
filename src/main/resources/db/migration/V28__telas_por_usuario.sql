-- Telas que cada login vê, escolhidas pelo dono (nulo = todas as do perfil)
ALTER TABLE usuario ADD COLUMN telas VARCHAR(255);
