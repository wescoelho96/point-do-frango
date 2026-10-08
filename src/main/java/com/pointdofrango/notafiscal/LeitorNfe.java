package com.pointdofrango.notafiscal;

import com.pointdofrango.shared.RegraDeNegocioException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Lê o XML de NF-e (modelo 55) de compra. DOCTYPE e entidades externas bloqueados contra XXE. */
public final class LeitorNfe {

    private static final String NS = "http://www.portalfiscal.inf.br/nfe";

    public record Item(int numero, String codigo, String ean, String descricao, String ncm, String unidade,
                       BigDecimal quantidade, BigDecimal valorUnitario, BigDecimal valor) {
    }

    public record Nota(String chave, String numero, String serie, String emitenteCnpj, String emitenteNome,
                       String destinatarioCnpj, OffsetDateTime emitidaEm, BigDecimal valorTotal, List<Item> itens) {
    }

    /** Campos extraídos da chave de 44 dígitos da DANFE. */
    public record Chave(String chave, String uf, YearMonth emissao, String emitenteCnpj, String modelo, String serie,
                        String numero) {
    }

    private LeitorNfe() {
    }

    public static Chave lerChave(String entrada) {
        String c = entrada == null ? "" : entrada.replaceAll("\\D", "");
        if (c.length() != 44) {
            throw new RegraDeNegocioException("A chave de acesso tem 44 números (fica embaixo do código de barras da DANFE).");
        }
        if (digitoChave(c.substring(0, 43)) != c.charAt(43) - '0') {
            throw new RegraDeNegocioException("Chave de acesso inválida: confira os números.");
        }
        int mes = Integer.parseInt(c.substring(4, 6));
        if (mes < 1 || mes > 12) {
            throw new RegraDeNegocioException("Chave de acesso inválida: confira os números.");
        }
        return new Chave(c, c.substring(0, 2), YearMonth.of(2000 + Integer.parseInt(c.substring(2, 4)), mes),
                c.substring(6, 20), c.substring(20, 22),
                c.substring(22, 25).replaceFirst("^0+(?!$)", ""), c.substring(25, 34).replaceFirst("^0+(?!$)", ""));
    }

    public static Nota ler(String xml) {
        Document doc = parse(xml);
        Element inf = primeiro(doc.getDocumentElement(), "infNFe");
        if (inf == null) {
            throw new RegraDeNegocioException("Este arquivo não é o XML de uma NF-e.");
        }
        String chave = lerChave(inf.getAttribute("Id").replace("NFe", "")).chave();
        Element ide = primeiro(inf, "ide");
        Element emit = primeiro(inf, "emit");
        Element dest = primeiro(inf, "dest");
        // 55 = NF-e; 65 = NFC-e, o cupom do caixa do mercado (em geral sem destinatário).
        String modelo = texto(ide, "mod");
        if (!"55".equals(modelo) && !"65".equals(modelo)) {
            throw new RegraDeNegocioException("Só NF-e (modelo 55) ou cupom NFC-e (modelo 65) podem ser importados.");
        }
        List<Item> itens = new ArrayList<>();
        NodeList dets = inf.getElementsByTagNameNS(NS, "det");
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = primeiro(det, "prod");
            BigDecimal bruto = numero(prod, "vProd");
            BigDecimal desconto = numeroOuZero(prod, "vDesc");
            itens.add(new Item(numeroDoItem(det, i + 1), texto(prod, "cProd"), texto(prod, "cEAN"),
                    texto(prod, "xProd"), texto(prod, "NCM"), texto(prod, "uCom"), numero(prod, "qCom"),
                    numero(prod, "vUnCom"), bruto.subtract(desconto)));
        }
        if (itens.isEmpty()) {
            throw new RegraDeNegocioException("A nota não tem itens.");
        }
        // Redes costumam usar o código da filial como nome fantasia ("337 BARUERI"): aí a razão social diz mais.
        String fantasia = texto(emit, "xFant");
        String emitenteNome = fantasia != null && !fantasia.isBlank() && !Character.isDigit(fantasia.charAt(0))
                ? fantasia : texto(emit, "xNome");
        return new Nota(chave, texto(ide, "nNF"), texto(ide, "serie"), texto(emit, "CNPJ"), emitenteNome,
                dest != null ? texto(dest, "CNPJ") : null, OffsetDateTime.parse(texto(ide, "dhEmi")),
                numero(primeiro(primeiro(inf, "total"), "ICMSTot"), "vNF"), itens);
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RegraDeNegocioException("Não foi possível ler o arquivo: ele não é um XML de NF-e válido.");
        }
    }

    /** Módulo 11 com pesos 2 a 9 (manual da NF-e). */
    static int digitoChave(String chave43) {
        int soma = 0;
        int peso = 2;
        for (int i = chave43.length() - 1; i >= 0; i--) {
            soma += (chave43.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static Element primeiro(Node pai, String nome) {
        if (pai == null) {
            return null;
        }
        NodeList lista = pai instanceof Document d ? d.getElementsByTagNameNS(NS, nome)
                : ((Element) pai).getElementsByTagNameNS(NS, nome);
        return lista.getLength() == 0 ? null : (Element) lista.item(0);
    }

    /** Padrão da SEFAZ é o atributo nItem; XMLs regravados por portais de consulta trazem como elemento. */
    private static int numeroDoItem(Element det, int posicao) {
        String n = det.hasAttribute("nItem") ? det.getAttribute("nItem") : texto(det, "nItem");
        return n != null && n.strip().matches("\\d{1,3}") ? Integer.parseInt(n.strip()) : posicao;
    }

    private static String texto(Element pai, String nome) {
        Element e = primeiro(pai, nome);
        return e == null ? null : e.getTextContent().strip();
    }

    private static BigDecimal numero(Element pai, String nome) {
        String t = texto(pai, nome);
        if (t == null) {
            throw new RegraDeNegocioException("XML da NF-e incompleto: falta o campo " + nome + ".");
        }
        return new BigDecimal(t);
    }

    private static BigDecimal numeroOuZero(Element pai, String nome) {
        String t = texto(pai, nome);
        return t == null ? BigDecimal.ZERO : new BigDecimal(t);
    }
}
