package com.pointdofrango.notafiscal;

import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.MovimentacaoEstoque;
import com.pointdofrango.estoque.InsumoExcluido;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.loja.LojaService;
import com.pointdofrango.notafiscal.LeitorNfe.Item;
import com.pointdofrango.notafiscal.LeitorNfe.Nota;
import com.pointdofrango.saida.Fornecedor;
import com.pointdofrango.saida.SaidaService;
import com.pointdofrango.saida.SaidaService.NovaSaida;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Importa NF-e de compra: itens ligados a insumos dão entrada no estoque pelo valor da nota,
 * e o total vira uma saída de insumos do fornecedor.
 */
@Service
public class NotaFiscalService {

    private static final Logger log = LoggerFactory.getLogger(NotaFiscalService.class);

    private final NotaFiscalCompraRepository notas;
    private final ItemFornecedorRepository itensFornecedor;
    private final EstoqueService estoque;
    private final SaidaService saidas;
    private final CaixaService caixa;
    private final LojaService loja;
    private final ConsultaNfce consultaNfce;
    private final Clock clock;
    private final ZoneId zona;

    public NotaFiscalService(NotaFiscalCompraRepository notas, ItemFornecedorRepository itensFornecedor,
                             EstoqueService estoque, SaidaService saidas, CaixaService caixa, LojaService loja,
                             ConsultaNfce consultaNfce, Clock clock, ZoneId zona) {
        this.notas = notas;
        this.itensFornecedor = itensFornecedor;
        this.estoque = estoque;
        this.saidas = saidas;
        this.caixa = caixa;
        this.loja = loja;
        this.consultaNfce = consultaNfce;
        this.clock = clock;
        this.zona = zona;
    }

    /** Sugestões vêm da última importação do mesmo fornecedor. */
    public record ItemPrevia(Item item, Long insumoSugerido, BigDecimal fatorSugerido) {
    }

    public record Previa(Nota nota, boolean jaImportada, boolean paraOutroCnpj, String fornecedor, List<ItemPrevia> itens) {
    }

    /** insumoId nulo = item ignorado (ex.: produto de limpeza). */
    public record Ligacao(int numero, Long insumoId, BigDecimal fator) {
    }

    /** parcelas: só no crédito, 1ª parcela no mês seguinte. */
    public record Pagamento(FormaPagamento forma, boolean doCaixa, @Min(1) @Max(12) Integer parcelas) {

        public Pagamento(FormaPagamento forma, boolean doCaixa) {
            this(forma, doCaixa, null);
        }
    }

    public record Resultado(String chave, int entradas, int ignorados, BigDecimal valorTotal) {
    }

    @Transactional(readOnly = true)
    public boolean jaImportada(String chave) {
        return notas.existsByChave(chave);
    }

    @Transactional(readOnly = true)
    public Previa previa(String xml) {
        return previa(LeitorNfe.ler(xml));
    }

    /** Cupom NFC-e: os itens vêm da página de consulta do QR Code. */
    @Transactional(readOnly = true)
    public Previa previaDoCupom(String enderecoQrCode) {
        return previa(consultaNfce.ler(enderecoQrCode));
    }

    private Previa previa(Nota nota) {
        var fornecedor = saidas.fornecedorPorCnpj(nota.emitenteCnpj());
        Map<String, ItemFornecedor> lembrados = fornecedor.map(f -> itensFornecedor.findByFornecedorId(f.getId()).stream()
                        .collect(Collectors.toMap(ItemFornecedor::getCodigoProduto, Function.identity())))
                .orElse(Map.of());
        List<ItemPrevia> itens = nota.itens().stream().map(i -> {
            ItemFornecedor l = lembrados.get(i.codigo());
            return new ItemPrevia(i, l != null ? l.getInsumoId() : null, l != null ? l.getFator() : null);
        }).toList();
        return new Previa(nota, notas.existsByChave(nota.chave()), paraOutroCnpj(nota),
                fornecedor.map(Fornecedor::getNome).orElse(nota.emitenteNome()), itens);
    }

    @Transactional
    public Resultado importar(String xml, String enderecoQrCode, List<Ligacao> ligacoes, Pagamento pagamento, String usuario) {
        // Relê no servidor (XML ou SEFAZ): valores e quantidades não vêm do navegador.
        Nota nota = xml != null && !xml.isBlank() ? LeitorNfe.ler(xml) : consultaNfce.ler(enderecoQrCode);
        if (notas.existsByChave(nota.chave())) {
            throw new RegraDeNegocioException("A nota nº " + nota.numero() + " de " + nota.emitenteNome() + " já foi importada.");
        }
        Fornecedor fornecedor = saidas.fornecedorDaNota(nota.emitenteCnpj(), nota.emitenteNome());
        Map<Integer, Ligacao> porItem = ligacoes.stream().collect(Collectors.toMap(Ligacao::numero, Function.identity(), (a, b) -> b));

        int entradas = 0;
        for (Item item : nota.itens()) {
            Ligacao l = porItem.get(item.numero());
            if (l == null || l.insumoId() == null) {
                continue;
            }
            if (l.fator() == null || l.fator().signum() <= 0) {
                throw new RegraDeNegocioException("Item " + item.numero() + " (" + item.descricao() + "): informe quantas unidades vêm em cada um.");
            }
            BigDecimal quantidade = item.quantidade().multiply(l.fator());
            estoque.registrarEntrada(l.insumoId(), new EntradaRequest(quantidade, null, null, item.valor(),
                    descricaoDaCompra(nota.chave(), nota.numero(), nota.emitenteNome())), usuario);
            lembrar(fornecedor.getId(), item, l);
            entradas++;
        }
        if (entradas == 0) {
            throw new RegraDeNegocioException("Ligue pelo menos um item da nota a um insumo do estoque.");
        }

        if (pagamento != null) {
            String descricao = descricaoDaCompra(nota.chave(), nota.numero(), nota.emitenteNome());
            if (pagamento.doCaixa()) {
                caixa.registrarSaida(CategoriaSaida.INSUMOS, descricao, nota.valorTotal(), FormaPagamento.DINHEIRO, null,
                        fornecedor.getId(), usuario);
            } else {
                saidas.lancarParcelado(new NovaSaida(nota.emitidaEm().atZoneSameInstant(zona).toLocalDate(), CategoriaSaida.INSUMOS,
                        descricao, nota.valorTotal(), pagamento.forma(), fornecedor.getId(), null, null), pagamento.parcelas(), usuario);
            }
        }
        notas.save(new NotaFiscalCompra(nota, fornecedor.getId(), usuario, Instant.now(clock)));
        log.info("NF-e {} importada por {}: {} entrada(s), total {}", nota.chave(), usuario, entradas, nota.valorTotal());
        return new Resultado(nota.chave(), entradas, nota.itens().size() - entradas, nota.valorTotal());
    }

    /** Insumo apagado: a próxima nota deste fornecedor volta a pedir a ligação do item. */
    @EventListener
    public void aoExcluirInsumo(InsumoExcluido evento) {
        itensFornecedor.deleteAll(itensFornecedor.findByInsumoId(evento.insumoId()));
    }

    /** negativos: itens que já tinham vendido parte do que chegou; voltam ao certo ao importar de novo. */
    public record Desfeita(int entradas, int insumosRemovidos, int saidasRemovidas, List<String> negativos) {
    }

    /** Aparece no extrato e nas saídas; modelo 65 na chave é cupom (NFC-e). */
    static String descricaoDaCompra(String chave, String numero, String emitente) {
        String tipo = chave != null && chave.length() == 44 && "65".equals(chave.substring(20, 22)) ? "Cupom" : "NF-e";
        return tipo + " nº " + numero + " · " + emitente;
    }

    /**
     * Desfaz uma importação: estorna as entradas no estoque, remove o pagamento lançado, apaga os insumos que só
     * existiam por causa dela e esquece as ligações do fornecedor, para importar de novo do zero.
     */
    @Transactional
    public Desfeita desfazer(Long id, String usuario) {
        NotaFiscalCompra nota = notas.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Nota", id));
        // Importações antigas de cupom foram gravadas como "NF-e nº".
        List<String> descricoes = List.of(descricaoDaCompra(nota.getChave(), nota.getNumero(), nota.getEmitenteNome()),
                "NF-e nº " + nota.getNumero() + " · " + nota.getEmitenteNome());
        Instant desde = nota.getImportadaEm().minus(Duration.ofMinutes(30));
        Instant ate = nota.getImportadaEm().plus(Duration.ofMinutes(1));
        String motivo = EstoqueService.ESTORNO_DE_IMPORTACAO + descricoes.get(0);

        Set<Long> movimentacoesDaCompra = new HashSet<>();
        Set<Long> insumosTocados = new LinkedHashSet<>();
        List<MovimentacaoEstoque> entradas = estoque.entradasDaCompra(descricoes, desde, ate);
        for (MovimentacaoEstoque entrada : entradas) {
            movimentacoesDaCompra.add(entrada.getId());
            movimentacoesDaCompra.add(estoque.estornarEntrada(entrada.getId(), motivo, usuario));
            insumosTocados.add(entrada.getInsumo().getId());
        }
        int saidasRemovidas = saidas.removerDaCompra(descricoes, desde, ate, usuario);
        if (nota.getFornecedorId() != null) {
            itensFornecedor.deleteAll(itensFornecedor.findByFornecedorId(nota.getFornecedorId()).stream()
                    .filter(i -> insumosTocados.contains(i.getInsumoId())).toList());
        }
        List<String> negativos = insumosTocados.stream().map(estoque::buscar)
                .filter(i -> i.getEstoqueAtual().signum() < 0).map(i -> i.getNome()).toList();
        int removidos = 0;
        for (Long insumoId : insumosTocados) {
            if (estoque.excluirSeSoDaCompra(insumoId, movimentacoesDaCompra)) {
                removidos++;
            }
        }
        notas.delete(nota);
        log.info("Importação {} desfeita por {}: {} entrada(s), {} insumo(s) removido(s), {} saída(s)", nota.getChave(), usuario,
                entradas.size(), removidos, saidasRemovidas);
        return new Desfeita(entradas.size(), removidos, saidasRemovidas, negativos);
    }

    @Transactional(readOnly = true)
    public List<NotaFiscalCompra> ultimas() {
        return notas.findTop30ByOrderByImportadaEmDesc();
    }

    private void lembrar(Long fornecedorId, Item item, Ligacao l) {
        ItemFornecedor lembrado = itensFornecedor.findByFornecedorIdAndCodigoProduto(fornecedorId, item.codigo())
                .orElseGet(() -> new ItemFornecedor(fornecedorId, item.codigo()));
        lembrado.ligar(item.descricao(), l.insumoId(), l.fator());
        itensFornecedor.save(lembrado);
    }

    /** Ex.: compra pessoal no CPF ou nota de outra empresa. */
    private boolean paraOutroCnpj(Nota nota) {
        String documento = loja.obter().getDocumento();
        String cnpjLoja = documento == null ? "" : documento.replaceAll("\\D", "");
        return !cnpjLoja.isEmpty() && nota.destinatarioCnpj() != null && !cnpjLoja.equals(nota.destinatarioCnpj());
    }
}
