package com.pointdofrango.estoque;

/** Publicado antes de apagar o insumo: quem guarda referência a ele limpa ou recusa a exclusão. */
public record InsumoExcluido(Long insumoId, String nome) {
}
