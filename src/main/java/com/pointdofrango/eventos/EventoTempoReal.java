package com.pointdofrango.eventos;

/**
 * Leva só tipo e id: cada tela busca os dados com o próprio token, então o evento não expõe
 * nada que o perfil de quem está conectado não pode ver.
 */
public record EventoTempoReal(Tipo tipo, Long id) {

    public enum Tipo {
        PEDIDO_NOVO,
        PEDIDO_STATUS,
        PEDIDO_CANCELADO,
        COMANDA_ALTERADA
    }
}
