package com.pointdofrango.contador;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV para o Excel em pt-BR: separador ";", vírgula decimal e BOM UTF-8 para não quebrar os acentos. */
final class Csv {

    private final StringBuilder texto = new StringBuilder("﻿");

    Csv linha(Object... colunas) {
        for (int i = 0; i < colunas.length; i++) {
            if (i > 0) {
                texto.append(';');
            }
            texto.append(celula(colunas[i]));
        }
        texto.append("\r\n");
        return this;
    }

    Csv linhas(List<Object[]> linhas) {
        linhas.forEach(this::linha);
        return this;
    }

    byte[] bytes() {
        return texto.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String celula(Object valor) {
        if (valor == null) {
            return "";
        }
        if (valor instanceof BigDecimal n) {
            return n.setScale(2, RoundingMode.HALF_EVEN).toPlainString().replace('.', ',');
        }
        String s = valor.toString();
        // Proteção contra injeção de fórmula: o Excel interpreta células iniciadas por = + - @
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
