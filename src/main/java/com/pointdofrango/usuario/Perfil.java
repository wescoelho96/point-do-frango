package com.pointdofrango.usuario;

/**
 * ADMIN: dono. Vê tudo, inclusive custos, lucro e pró-labore; cadastra; cancela pedidos.
 * ATENDENTE: lança pedidos, abre e fecha comandas, opera a cozinha. Não vê o financeiro e não
 * cancela (evita "cancelar a venda em dinheiro e ficar com o valor").
 * COZINHA: só a fila da cozinha (PC ou celular que fica na cozinha).
 */
public enum Perfil {
    ADMIN,
    ATENDENTE,
    COZINHA
}
