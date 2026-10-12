package com.pointdofrango.notafiscal;

import com.pointdofrango.notafiscal.LeitorNfe.Item;
import com.pointdofrango.notafiscal.LeitorNfe.Nota;
import com.pointdofrango.shared.RegraDeNegocioException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o cupom NFC-e a partir da página de consulta do QR Code (layout padrão dos portais das SEFAZ).
 * Número, série e CNPJ do emitente vêm da chave, que tem dígito verificador; da página saem itens, nome e total.
 */
public final class LeitorCupom {

    private static final Pattern CHAVE_NA_URL = Pattern.compile("(?:^|[?&])p=(\\d{44})");
    private static final Pattern ITEM = Pattern.compile(
            "^(.+?)\\s*\\(\\s*C\\S{1,2}digo:\\s*([^)]*?)\\s*\\)\\s*Qtde\\.?:\\s*([\\d.,]+)\\s*UN:\\s*(\\S+)\\s*"
                    + "Vl\\.\\s*Unit\\.?:\\s*([\\d.,]+)\\s*Vl\\.\\s*Total\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL = Pattern.compile("Valor a pagar R\\$:?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSAO = Pattern.compile("Emiss\\S{1,2}o:\\s*(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern EMITENTE = Pattern.compile("id=\"u20\"[^>]*>([^<]+)<");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private LeitorCupom() {
    }

    /** Só aceita os portais oficiais (.gov.br): o servidor não deve buscar endereços arbitrários. */
    public static URI validarEndereco(String endereco) {
        URI uri;
        try {
            // O QR Code traz "p=CHAVE|2|1|1|HASH" com a barra vertical sem codificar.
            uri = URI.create(endereco == null ? "" : endereco.trim().replace("|", "%7C").replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocioException("O QR Code não tem um endereço válido de consulta da NFC-e.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean https = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        if (!https || !host.endsWith(".gov.br") || uri.getUserInfo() != null) {
            throw new RegraDeNegocioException("Este QR Code não é de um cupom fiscal (NFC-e) da SEFAZ.");
        }
        chaveDaUrl(uri);
        return uri;
    }

    static String chaveDaUrl(URI uri) {
        String consulta = uri.getRawQuery() == null ? "" : URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        Matcher m = CHAVE_NA_URL.matcher(consulta);
        if (!m.find()) {
            throw new RegraDeNegocioException("O QR Code não traz a chave de acesso do cupom.");
        }
        return LeitorNfe.lerChave(m.group(1)).chave();
    }

    public static Nota ler(URI origem, String html, ZoneId zona) {
        LeitorNfe.Chave chave = LeitorNfe.lerChave(chaveDaUrl(origem));
        List<Item> itens = new ArrayList<>();
        String[] blocos = html.split("(?i)<tr[^>]*id=\"Item");
        for (int i = 1; i < blocos.length; i++) {
            String bloco = blocos[i];
            int fim = bloco.toLowerCase().indexOf("</tr>");
            Matcher m = ITEM.matcher(texto(fim < 0 ? bloco : bloco.substring(0, fim)).replaceFirst("^[^>]*>", "").trim());
            if (m.find()) {
                itens.add(new Item(itens.size() + 1, m.group(2), null, m.group(1).trim(), null, m.group(4),
                        numero(m.group(3)), numero(m.group(5)), numero(m.group(6))));
            }
        }
        if (itens.isEmpty()) {
            throw new RegraDeNegocioException("Não foi possível ler os itens do cupom na página da SEFAZ. "
                    + "Confira se o QR Code é de um cupom autorizado ou lance pela Compra sem nota.");
        }
        String pagina = texto(html);
        Matcher total = TOTAL.matcher(pagina);
        BigDecimal valorTotal = total.find() ? numero(total.group(1))
                : itens.stream().map(Item::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        itens = comDescontoRateado(itens, valorTotal);
        Matcher emissao = EMISSAO.matcher(pagina);
        OffsetDateTime emitidaEm = emissao.find()
                ? LocalDateTime.parse(emissao.group(1).replaceAll("\\s+", " "), DATA).atZone(zona).toOffsetDateTime()
                : OffsetDateTime.now(zona);
        Matcher emitente = EMITENTE.matcher(html);
        String nome = emitente.find() ? limpar(emitente.group(1)) : "CNPJ " + chave.emitenteCnpj();
        return new Nota(chave.chave(), chave.numero(), chave.serie(), chave.emitenteCnpj(), nome, null, emitidaEm,
                valorTotal, itens);
    }

    /**
     * A página mostra o valor cheio de cada item e só o desconto total do cupom. O desconto é rateado
     * pelo valor dos itens, para o custo do estoque bater com o que foi pago; o último item absorve os centavos.
     */
    static List<Item> comDescontoRateado(List<Item> itens, BigDecimal valorPago) {
        BigDecimal soma = itens.stream().map(Item::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (soma.signum() == 0 || soma.compareTo(valorPago) == 0) {
            return itens;
        }
        List<Item> ajustados = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        for (int i = 0; i < itens.size(); i++) {
            Item item = itens.get(i);
            BigDecimal valor = i == itens.size() - 1 ? valorPago.subtract(acumulado)
                    : item.valor().multiply(valorPago).divide(soma, 2, RoundingMode.HALF_EVEN);
            acumulado = acumulado.add(valor);
            ajustados.add(new Item(item.numero(), item.codigo(), item.ean(), item.descricao(), item.ncm(), item.unidade(),
                    item.quantidade(), item.valorUnitario(), valor));
        }
        return ajustados;
    }

    private static String texto(String html) {
        return limpar(html.replaceAll("(?s)<script.*?</script>", " ").replaceAll("<[^>]+>", " "));
    }

    private static String limpar(String texto) {
        return texto.replace("&nbsp;", " ").replace('\u00A0', ' ').replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
    }

    /** As SEFAZ respondem em UTF-8 ou ISO-8859-1, nem sempre declarado no cabeçalho. */
    public static String decodificar(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** "1.234,56" ou "0,732" no formato brasileiro. */
    private static BigDecimal numero(String valor) {
        return new BigDecimal(valor.replace(".", "").replace(",", "."));
    }
}
