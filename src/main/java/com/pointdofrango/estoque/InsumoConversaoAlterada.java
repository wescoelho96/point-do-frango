package com.pointdofrango.estoque;

import java.util.Set;

/**
 * Mudou unidade, unidade de uso ou embalagens de um insumo. O módulo de produtos recalcula as
 * fichas; via evento, o estoque não depende de produtos.
 */
public record InsumoConversaoAlterada(Long insumoId, Set<Long> embalagensRemovidas) {
}
