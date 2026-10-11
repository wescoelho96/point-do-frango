package com.pointdofrango.notafiscal;

import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeitorCupomTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Como vem no QR Code impresso: barra vertical sem codificar. */
    private static final String QR = "https://www.nfce.fazenda.sp.gov.br/NFCeConsultaPublica/Paginas/ConsultaQRCode.aspx?p=";
    private static final String CHAVE;

    static {
        String sem = "3526101122233300018165002000001234100001234";
        CHAVE = sem + LeitorNfe.digitoChave(sem);
    }

    private static String pagina() throws IOException {
        try (InputStream in = LeitorCupomTest.class.getResourceAsStream("/nfe/cupom-exemplo.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static URI qrCode() {
        return LeitorCupom.validarEndereco(QR + CHAVE + "|2|1|1|ABCDEF");
    }

    @Test
    @DisplayName("Lê itens, total, emitente e emissão da página de consulta do QR Code")
    void lerPagina() throws IOException {
        var nota = LeitorCupom.ler(qrCode(), pagina(), SP);

        assertThat(nota.chave()).isEqualTo(CHAVE);
        assertThat(nota.emitenteNome()).isEqualTo("MERCADO EXEMPLO LTDA");
        assertThat(nota.emitenteCnpj()).isEqualTo("11222333000181");
        assertThat(nota.numero()).isEqualTo("1234");
        assertThat(nota.valorTotal()).isEqualByComparingTo("131.80");
        assertThat(nota.emitidaEm().toLocalDateTime().toString()).isEqualTo("2026-10-08T14:28:44");
        assertThat(nota.itens()).hasSize(3);
        var cebola = nota.itens().get(2);
        assertThat(cebola.descricao()).isEqualTo("CEBOLA KG");
        assertThat(cebola.codigo()).isEqualTo("12");
        assertThat(cebola.unidade()).isEqualTo("KG");
        assertThat(cebola.quantidade()).isEqualByComparingTo("1.235");
        assertThat(cebola.valor()).isEqualByComparingTo("6.16");
    }

    @Test
    @DisplayName("Só aceita endereço da SEFAZ (.gov.br) com chave válida no QR Code")
    void enderecoSeguro() {
        assertThat(qrCode().getHost()).isEqualTo("www.nfce.fazenda.sp.gov.br");
        assertThatThrownBy(() -> LeitorCupom.validarEndereco("https://exemplo.com/consulta?p=" + CHAVE))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> LeitorCupom.validarEndereco("http://169.254.169.254/latest?p=" + CHAVE))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> LeitorCupom.validarEndereco("https://www.nfce.fazenda.sp.gov.br/x?p=123"))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Página sem itens (cupom inexistente ou consulta recusada) gera mensagem clara")
    void semItens() {
        assertThatThrownBy(() -> LeitorCupom.ler(qrCode(), "<html><body>NFC-e não encontrada</body></html>", SP))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("Compra sem nota");
    }
}
