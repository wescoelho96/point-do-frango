package com.pointdofrango.shared;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lista todos os insumos em falta, não só o primeiro, para o caixa saber o que tirar do pedido.
 */
public class EstoqueInsuficienteException extends RegraDeNegocioException {

    public record Falta(String insumo, BigDecimal necessario, BigDecimal disponivel, String unidade) {
    }

    private final transient List<Falta> faltas;

    public EstoqueInsuficienteException(List<Falta> faltas) {
        super("Estoque insuficiente: " + faltas.stream()
                .map(f -> "%s (precisa %s %s, tem %s)".formatted(
                        f.insumo(), f.necessario().stripTrailingZeros().toPlainString(), f.unidade(),
                        f.disponivel().stripTrailingZeros().toPlainString()))
                .collect(Collectors.joining("; ")));
        this.faltas = List.copyOf(faltas);
    }

    public List<Falta> getFaltas() {
        return faltas;
    }
}
