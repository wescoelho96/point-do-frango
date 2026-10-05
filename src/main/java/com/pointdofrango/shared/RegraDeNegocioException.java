package com.pointdofrango.shared;

/** Operação válida sintaticamente, mas proibida pela regra do negócio. Vira HTTP 422. */
public class RegraDeNegocioException extends RuntimeException {

    public RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }
}
