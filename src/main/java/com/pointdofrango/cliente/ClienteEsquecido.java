package com.pointdofrango.cliente;

/**
 * Publicado quando o cliente pede para ser apagado (LGPD, art. 18). Quem guarda cópia dos
 * dados dele (os pedidos) escuta e anonimiza, na mesma transação.
 */
public record ClienteEsquecido(Long clienteId, String telefone) {
}
