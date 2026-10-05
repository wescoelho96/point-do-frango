package com.pointdofrango.estoque;

/** kg e L têm subunidade (g, ml) para a ficha poder ser digitada como "300 g". */
public enum UnidadeMedida {
    QUILOGRAMA("kg", "g"),
    GRAMA("g", null),
    LITRO("L", "ml"),
    MILILITRO("ml", null),
    UNIDADE("un", null);

    private final String sigla;
    private final String subunidade;

    UnidadeMedida(String sigla, String subunidade) {
        this.sigla = sigla;
        this.subunidade = subunidade;
    }

    public String getSigla() {
        return sigla;
    }

    /** Sempre 1/1000 da unidade; null quando não há. */
    public String getSubunidade() {
        return subunidade;
    }

    public boolean temSubunidade() {
        return subunidade != null;
    }
}
