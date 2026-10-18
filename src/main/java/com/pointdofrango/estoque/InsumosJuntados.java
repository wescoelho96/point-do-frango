package com.pointdofrango.estoque;

/** Dois cadastros do mesmo item viraram um: quem guarda o id antigo passa a apontar para o novo. */
public record InsumosJuntados(Long origemId, Long destinoId) {
}
