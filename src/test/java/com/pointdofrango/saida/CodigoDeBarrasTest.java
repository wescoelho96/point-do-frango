package com.pointdofrango.saida;

import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodigoDeBarrasTest {

    /** Monta um código de conta de consumo válido (luz: segmento 3, valor real, módulo 10). */
    private static String contaDeLuz(String valor11) {
        String semDv = "836" + valor11 + "0".repeat(29);
        int dv = CodigoDeBarras.modulo10(semDv);
        return semDv.substring(0, 3) + dv + semDv.substring(3);
    }

    private static String linhaDe(String barra) {
        StringBuilder linha = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String bloco = barra.substring(i * 11, i * 11 + 11);
            linha.append(bloco).append(CodigoDeBarras.modulo10(bloco));
        }
        return linha.toString();
    }

    private static String boleto(String fator, String valor10) {
        String semDv = "0019" + fator + valor10 + "0".repeat(25);
        int dv = CodigoDeBarras.modulo11Boleto(semDv);
        return semDv.substring(0, 4) + dv + semDv.substring(4);
    }

    @Test
    @DisplayName("Conta de luz: o valor sai do código de barras e da linha digitável de 48 números")
    void contaDeConsumo() {
        String barra = contaDeLuz("00000023500"); // R$ 235,00
        assertThat(CodigoDeBarras.ler(barra).valor()).isEqualByComparingTo("235.00");

        String linha = linhaDe(barra);
        String formatada = linha.substring(0, 12) + " " + linha.substring(12, 24) + " " + linha.substring(24, 36) + " "
                + linha.substring(36);
        var leitura = CodigoDeBarras.ler(formatada);
        assertThat(leitura.valor()).isEqualByComparingTo("235.00");
        assertThat(leitura.tipo()).isEqualTo("CONSUMO");
        assertThat(leitura.codigoBarras()).isEqualTo(barra);
    }

    @Test
    @DisplayName("Boleto bancário: valor e vencimento (fator novo, a partir de 22/02/2025)")
    void boletoBancario() {
        String barra = boleto("1590", "0000015000"); // fator 1590 = 22/02/2025 + 590 dias
        var leitura = CodigoDeBarras.ler(barra);
        assertThat(leitura.valor()).isEqualByComparingTo("150.00");
        assertThat(leitura.vencimento()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    @DisplayName("Número digitado errado é recusado (dígito verificador não confere)")
    void digitoErrado() {
        String barra = contaDeLuz("00000023500");
        String errado = barra.substring(0, 10) + (barra.charAt(10) == '9' ? '8' : '9') + barra.substring(11);
        assertThatThrownBy(() -> CodigoDeBarras.ler(errado)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> CodigoDeBarras.ler("123")).isInstanceOf(RegraDeNegocioException.class);
    }
}
