package com.pointdofrango.produto;

public enum CategoriaProduto {
    PORCAO("Porções"),
    ESPETINHO("Espetinhos"),
    COMBO("Combos"),
    BEBIDA("Bebidas"),
    OUTRO("Outros");

    private final String rotulo;

    CategoriaProduto(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }
}
