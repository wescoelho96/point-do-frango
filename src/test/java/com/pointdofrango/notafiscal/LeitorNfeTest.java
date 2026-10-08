package com.pointdofrango.notafiscal;

import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeitorNfeTest {

    static final String CHAVE = "35261011222333000181550010000123451123456784";

    static String xmlDeExemplo() throws IOException {
        try (InputStream in = LeitorNfeTest.class.getResourceAsStream("/nfe/atacado-exemplo.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Lê emitente, número, total e itens (valor do item já com o desconto)")
    void lerXml() throws IOException {
        var nota = LeitorNfe.ler(xmlDeExemplo());

        assertThat(nota.chave()).isEqualTo(CHAVE);
        assertThat(nota.numero()).isEqualTo("12345");
        assertThat(nota.emitenteNome()).isEqualTo("ATACADO EXEMPLO");
        assertThat(nota.emitenteCnpj()).isEqualTo("11222333000181");
        assertThat(nota.valorTotal()).isEqualByComparingTo("164.80");
        assertThat(nota.itens()).hasSize(3);
        assertThat(nota.itens().get(1).valor()).isEqualByComparingTo("50.00");
        assertThat(nota.itens().get(0).unidade()).isEqualTo("FD");
    }

    @Test
    @DisplayName("XML regravado por portal de consulta: nItem como elemento")
    void nItemComoElemento() throws IOException {
        String xml = xmlDeExemplo().replaceAll("<det nItem=\"(\\d+)\">", "<det><nItem>$1</nItem>");

        var nota = LeitorNfe.ler(xml);

        assertThat(nota.itens()).extracting(LeitorNfe.Item::numero).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("Chave da DANFE: CNPJ, número e mês; dígito errado é recusado")
    void chave() {
        var c = LeitorNfe.lerChave("3526 1011 2223 3300 0181 5500 1000 0123 4511 2345 6784");
        assertThat(c.emitenteCnpj()).isEqualTo("11222333000181");
        assertThat(c.numero()).isEqualTo("12345");
        assertThat(c.emissao()).isEqualTo(YearMonth.of(2026, 10));
        assertThatThrownBy(() -> LeitorNfe.lerChave(CHAVE.substring(0, 43) + "5")).isInstanceOf(RegraDeNegocioException.class);
        String mesInvalido = "352613" + CHAVE.substring(6, 43);
        assertThatThrownBy(() -> LeitorNfe.lerChave(mesInvalido + LeitorNfe.digitoChave(mesInvalido)))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("XML com DOCTYPE/entidade externa (ataque XXE) é recusado")
    void xxe() {
        String malicioso = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///C:/Windows/win.ini\">]>"
                + "<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\"><x>&e;</x></nfeProc>";
        assertThatThrownBy(() -> LeitorNfe.ler(malicioso)).isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Cupom NFC-e (modelo 65) é lido como a NF-e; outros modelos são recusados")
    void nfce() throws IOException {
        String nfce = xmlDeExemplo().replace("<mod>55</mod>", "<mod>65</mod>");
        assertThat(LeitorNfe.ler(nfce).itens()).hasSize(3);

        String outro = xmlDeExemplo().replace("<mod>55</mod>", "<mod>59</mod>");
        assertThatThrownBy(() -> LeitorNfe.ler(outro)).isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("modelo 65");
    }
}
