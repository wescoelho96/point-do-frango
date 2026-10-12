package com.pointdofrango.notafiscal;

import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
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
    @DisplayName("Página em ISO-8859-1, com espaço não separável e código do produto quebrado em linhas")
    void paginaComoASefazEnvia() throws IOException {
        String html = pagina().replace("&nbsp;", " ").replace("(Código: 12 )", "(Código:\n\t\t\t12\n\t\t\t)");
        byte[] latin1 = html.getBytes(Charset.forName("windows-1252"));

        var nota = LeitorCupom.ler(qrCode(), LeitorCupom.decodificar(latin1), SP);

        assertThat(nota.itens()).hasSize(3);
        assertThat(nota.itens().get(2).codigo()).isEqualTo("12");
        assertThat(nota.itens().get(0).valorUnitario()).isEqualByComparingTo("8.49");
        assertThat(nota.emitidaEm().toLocalDateTime().toString()).isEqualTo("2026-10-08T14:28:44");
    }

    @Test
    @DisplayName("Desconto do cupom é rateado entre os itens e a soma fecha com o valor pago")
    void descontoRateado() throws IOException {
        String comDesconto = pagina().replace("<span class=\"totalNumb txtMax\">131,80</span>",
                "<span class=\"totalNumb txtMax\">121,80</span>");

        var nota = LeitorCupom.ler(qrCode(), comDesconto, SP);

        assertThat(nota.valorTotal()).isEqualByComparingTo("121.80");
        assertThat(nota.itens().stream().map(LeitorNfe.Item::valor).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("121.80");
        assertThat(nota.itens().get(0).valor()).isEqualByComparingTo("47.08"); // 50,94 × 121,80 / 131,80
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
