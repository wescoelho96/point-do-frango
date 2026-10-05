package com.pointdofrango.estoque;

/** Como a quantidade foi digitada na ficha técnica. Ver Insumo.converterParaBase. */
public enum ModoQuantidade {
    UNIDADE_BASE,
    /** g ou ml, só para insumos em kg ou L. */
    SUBUNIDADE,
    /** 12 iscas com 12 iscas por kg = 1 kg. */
    UNIDADE_USO,
    /** 7 porções por "Saco 2 kg" = 2/7 kg por porção. */
    RENDIMENTO_EMBALAGEM
}
