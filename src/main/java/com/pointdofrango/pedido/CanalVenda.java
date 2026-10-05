package com.pointdofrango.pedido;

/**
 * Origem do pedido. O canal é gravado detalhado para a operação; o financeiro agrupa por {@link Grupo}.
 */
public enum CanalVenda {
    BALCAO(Grupo.LOJA),
    /** Consumo no local, lançado numa comanda (paga no fechamento). */
    MESA(Grupo.LOJA),
    WHATSAPP(Grupo.WHATSAPP_TELEFONE),
    TELEFONE(Grupo.WHATSAPP_TELEFONE),
    IFOOD(Grupo.IFOOD),
    NOVENTA_NOVE_FOOD(Grupo.NOVENTA_NOVE_FOOD);

    public enum Grupo {
        LOJA("Balcão / Mesa"),
        WHATSAPP_TELEFONE("WhatsApp / Telefone"),
        IFOOD("iFood"),
        NOVENTA_NOVE_FOOD("99Food");

        private final String rotulo;

        Grupo(String rotulo) {
            this.rotulo = rotulo;
        }

        public String getRotulo() {
            return rotulo;
        }
    }

    private final Grupo grupo;

    CanalVenda(Grupo grupo) {
        this.grupo = grupo;
    }

    public Grupo getGrupo() {
        return grupo;
    }

    /** iFood e 99Food: o app cobra do cliente e repassa descontando comissão e taxas. */
    public boolean plataforma() {
        return this == IFOOD || this == NOVENTA_NOVE_FOOD;
    }
}
