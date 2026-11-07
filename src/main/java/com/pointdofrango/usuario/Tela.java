package com.pointdofrango.usuario;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Telas do app. O dono escolhe quais cada login vê, dentro do que o perfil pode usar: o backend
 * continua bloqueando por perfil o que é sensível (financeiro, custos, cancelamento).
 */
public enum Tela {
    PDV(Perfil.ATENDENTE),
    COMANDAS(Perfil.ATENDENTE),
    COZINHA(Perfil.ATENDENTE, Perfil.COZINHA),
    PEDIDOS(Perfil.ATENDENTE),
    ENTREGAS(Perfil.ATENDENTE),
    CAIXA(Perfil.ATENDENTE),
    CLIENTES,
    ESTOQUE(Perfil.ATENDENTE),
    CARDAPIO,
    FINANCEIRO,
    CONFIG;

    private final Set<Perfil> perfis;

    Tela(Perfil... perfis) {
        this.perfis = EnumSet.of(Perfil.ADMIN, perfis);
    }

    public boolean permitidaPara(Perfil perfil) {
        return perfis.contains(perfil);
    }

    public static List<Tela> doPerfil(Perfil perfil) {
        return EnumSet.allOf(Tela.class).stream().filter(t -> t.permitidaPara(perfil)).toList();
    }
}
