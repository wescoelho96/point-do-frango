package com.pointdofrango.saida;

import com.pointdofrango.shared.RegraDeNegocioException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lê valor (e vencimento, no boleto) de código de barras FEBRABAN: conta de consumo (começa com 8, linha de 48)
 * ou boleto bancário (linha de 47). Os dígitos verificadores são conferidos para recusar erro de digitação
 * em vez de lançar um valor errado.
 */
public final class CodigoDeBarras {

    /** Fator de vencimento reiniciado em 1000 a partir de 22/02/2025, quando o contado desde 1997 chegou a 9999. */
    private static final LocalDate BASE_FATOR = LocalDate.of(2025, 2, 22);

    public record Leitura(String codigoBarras, String tipo, BigDecimal valor, LocalDate vencimento) {
    }

    private CodigoDeBarras() {
    }

    public static Leitura ler(String entrada) {
        String d = entrada == null ? "" : entrada.replaceAll("\\D", "");
        String barra = switch (d.length()) {
            case 44 -> d;
            case 48 -> deLinhaDeConsumo(d);
            case 47 -> deLinhaDeBoleto(d);
            default -> throw new RegraDeNegocioException(
                    "Código inválido: use os 44 números do código de barras ou a linha digitável (47 ou 48 números).");
        };
        return barra.charAt(0) == '8' ? lerConsumo(barra) : lerBoleto(barra);
    }

    private static Leitura lerConsumo(String barra) {
        char id = barra.charAt(2);
        boolean modulo10 = id == '6' || id == '7';
        String semDv = barra.substring(0, 3) + barra.substring(4);
        int dv = modulo10 ? modulo10(semDv) : modulo11Consumo(semDv);
        if (dv != barra.charAt(3) - '0') {
            throw new RegraDeNegocioException("Código de barras inválido: confira os números.");
        }
        // 6 e 8 trazem valor em reais; 7 e 9 só valor de referência
        BigDecimal valor = id == '6' || id == '8' ? centavos(barra.substring(4, 15)) : null;
        return new Leitura(barra, "CONSUMO", valor, null);
    }

    private static Leitura lerBoleto(String barra) {
        String semDv = barra.substring(0, 4) + barra.substring(5);
        if (modulo11Boleto(semDv) != barra.charAt(4) - '0') {
            throw new RegraDeNegocioException("Código de barras inválido: confira os números.");
        }
        int fator = Integer.parseInt(barra.substring(5, 9));
        LocalDate vencimento = fator < 1000 ? null : BASE_FATOR.plusDays(fator - 1000L);
        BigDecimal valor = centavos(barra.substring(9, 19));
        return new Leitura(barra, "BOLETO", valor.signum() == 0 ? null : valor, vencimento);
    }

    /** 4 blocos de 11 dígitos, cada um com seu DV. */
    private static String deLinhaDeConsumo(String linha) {
        StringBuilder barra = new StringBuilder();
        boolean modulo10 = linha.charAt(2) == '6' || linha.charAt(2) == '7';
        for (int i = 0; i < 4; i++) {
            String bloco = linha.substring(i * 12, i * 12 + 11);
            int dv = modulo10 ? modulo10(bloco) : modulo11Consumo(bloco);
            if (dv != linha.charAt(i * 12 + 11) - '0') {
                throw new RegraDeNegocioException("Linha digitável inválida: confira o bloco " + (i + 1) + ".");
            }
            barra.append(bloco);
        }
        return barra.toString();
    }

    /** 47 dígitos: campo1(10) campo2(11) campo3(11) DV(1) fator+valor(14). */
    private static String deLinhaDeBoleto(String linha) {
        int[][] campos = {{0, 9}, {10, 20}, {21, 31}};
        for (int[] c : campos) {
            if (modulo10(linha.substring(c[0], c[1])) != linha.charAt(c[1]) - '0') {
                throw new RegraDeNegocioException("Linha digitável inválida: confira os números.");
            }
        }
        return linha.substring(0, 4) + linha.charAt(32) + linha.substring(33, 47)
                + linha.substring(4, 9) + linha.substring(10, 20) + linha.substring(21, 31);
    }

    static int modulo10(String numero) {
        int soma = 0;
        int peso = 2;
        for (int i = numero.length() - 1; i >= 0; i--) {
            int produto = (numero.charAt(i) - '0') * peso;
            soma += produto / 10 + produto % 10;
            peso = peso == 2 ? 1 : 2;
        }
        return (10 - soma % 10) % 10;
    }

    private static int somaPesos2a9(String numero) {
        int soma = 0;
        int peso = 2;
        for (int i = numero.length() - 1; i >= 0; i--) {
            soma += (numero.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        return soma;
    }

    static int modulo11Consumo(String numero) {
        int resto = somaPesos2a9(numero) % 11;
        return resto == 0 || resto == 1 ? 0 : resto == 10 ? 1 : 11 - resto;
    }

    static int modulo11Boleto(String numero) {
        int dv = 11 - somaPesos2a9(numero) % 11;
        return dv == 0 || dv == 10 || dv == 11 ? 1 : dv;
    }

    private static BigDecimal centavos(String digitos) {
        return new BigDecimal(digitos).movePointLeft(2);
    }
}
