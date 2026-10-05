package com.pointdofrango.contador;

import com.pointdofrango.contador.RelatorioContadorService.Resumo;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.Pedido;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Relatórios para o contador. As planilhas não levam dados pessoais de cliente (LGPD, minimização). */
@RestController
@RequestMapping("/api/v1/contador")
@Tag(name = "Contador", description = "Receitas mês a mês (RMRB), resumo anual (DASN-SIMEI), despesas e planilhas CSV")
public class ContadorController {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final FormaPagamento[] FORMAS = FormaPagamento.values();

    private final RelatorioContadorService service;
    private final ZoneId zona;

    public ContadorController(RelatorioContadorService service, ZoneId zona) {
        this.service = service;
        this.zona = zona;
    }

    @GetMapping("/resumo")
    @Operation(summary = "Receitas por mês (RMRB), formas de pagamento, despesas e, no ano fechado, os dados da DASN-SIMEI")
    public Resumo resumo(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth de,
                         @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth ate) {
        return service.resumo(de, ate);
    }

    @GetMapping("/receitas.csv")
    @Operation(summary = "Planilha: receita de cada dia, separada por tipo e forma de pagamento")
    public ResponseEntity<byte[]> receitas(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth de,
                                           @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth ate) {
        List<Object> cabecalho = new ArrayList<>(List.of("Data", "Pedidos", "Revenda de mercadorias",
                "Produtos industrializados", "Serviços (entrega)", "Receita bruta", "Taxas maquininha/apps", "Líquido"));
        for (FormaPagamento f : FORMAS) {
            cabecalho.add("Recebido " + f.name().toLowerCase().replace('_', ' '));
        }
        Csv csv = new Csv().linha(cabecalho.toArray());
        for (var d : service.porDia(de, ate)) {
            var r = d.receita();
            List<Object> linha = new ArrayList<>(List.of(DATA.format(d.dia()), r.pedidos(), r.revendaMercadorias(),
                    r.produtosIndustrializados(), r.servicos(), r.total(), d.taxas(), r.total().subtract(d.taxas())));
            for (FormaPagamento f : FORMAS) {
                linha.add(d.porForma().getOrDefault(f, BigDecimal.ZERO));
            }
            csv.linha(linha.toArray());
        }
        return arquivo("receitas", de, ate, csv);
    }

    @GetMapping("/pedidos.csv")
    @Operation(summary = "Planilha: todos os pedidos do período (sem dados pessoais do cliente)")
    public ResponseEntity<byte[]> pedidos(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth de,
                                          @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth ate) {
        Csv csv = new Csv().linha("Pedido", "Data", "Hora", "Canal", "Situação", "Forma de pagamento", "Valor dos itens",
                "Taxa de entrega cobrada", "Total cobrado", "Taxa maquininha/app", "Nº no app", "Itens");
        for (Pedido p : service.pedidos(de, ate)) {
            LocalDateTime quando = LocalDateTime.ofInstant(p.getCriadoEm(), zona);
            csv.linha(p.getId(), DATA.format(quando), HORA.format(quando), p.getCanal(), p.getStatus(),
                    p.getFormaPagamento() != null ? p.getFormaPagamento() : "comanda aberta", p.getValorTotal(),
                    p.entrega() ? p.taxaCobrada() : BigDecimal.ZERO, p.totalACobrar(), p.getDistribuicao().taxaPagamento(),
                    p.getCodigoExterno(), String.join(", ", p.getItens().stream()
                            .map(i -> i.getQuantidade() + "x " + i.getNomeProduto() + (i.isBrinde() ? " (brinde)" : "")).toList()));
        }
        return arquivo("pedidos", de, ate, csv);
    }

    @GetMapping("/saidas.csv")
    @Operation(summary = "Planilha: todas as saídas (caixa e por fora), com fornecedor e código de barras")
    public ResponseEntity<byte[]> saidas(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth de,
                                         @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth ate) {
        Csv csv = new Csv().linha("Data", "Categoria", "Descrição", "Pessoa", "Fornecedor", "Forma", "Pago de", "Valor");
        csv.linhas(service.saidas(de, ate).stream().map(l -> new Object[]{DATA.format(l.data()), l.categoria().getRotulo(),
                l.descricao(), l.pessoa(), l.fornecedor(), l.forma(), "CAIXA".equals(l.origem()) ? "Gaveta" : "Conta/PIX",
                l.valor()}).toList());
        return arquivo("saidas", de, ate, csv);
    }

    private static ResponseEntity<byte[]> arquivo(String nome, YearMonth de, YearMonth ate, Csv csv) {
        String arquivo = "point-do-frango-%s-%s%s.csv".formatted(nome, de, de.equals(ate) ? "" : "_a_" + ate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(arquivo).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.bytes());
    }
}
