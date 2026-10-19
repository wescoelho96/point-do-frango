package com.pointdofrango.saida;

import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.MovimentacaoEstoque;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Desfaz uma compra lançada pelo estoque (cupom, compra sem nota, entrada com pagamento): estorna as entradas,
 * sem contar como perda, e remove o pagamento com todas as parcelas. Serve para compra lançada em dobro.
 */
@Service
public class DesfazerCompraService {

    private static final Logger log = LoggerFactory.getLogger(DesfazerCompraService.class);
    private static final Pattern PARCELA = Pattern.compile(" \\(\\d+/\\d+\\)$");
    private static final Pattern VARIOS_ITENS = Pattern.compile("^(.*) \\(\\d+ itens?\\)$");
    private static final Pattern UM_ITEM = Pattern.compile("^Compra: ([\\d.]+) \\S+ de (.+)$");

    public record Desfeita(int entradas, List<String> insumos) {
    }

    private final SaidaRepository saidas;
    private final SaidaService saidaService;
    private final EstoqueService estoque;

    public DesfazerCompraService(SaidaRepository saidas, SaidaService saidaService, EstoqueService estoque) {
        this.saidas = saidas;
        this.saidaService = saidaService;
        this.estoque = estoque;
    }

    @Transactional
    public Desfeita desfazer(Long saidaId, String usuario) {
        Saida saida = saidas.findById(saidaId).orElseThrow(() -> new RecursoNaoEncontradoException("Saída", saidaId));
        if (saida.getCategoria() != CategoriaSaida.INSUMOS) {
            throw new RegraDeNegocioException("Só compras de mercadoria podem ser desfeitas.");
        }
        String descricao = PARCELA.matcher(saida.getDescricao()).replaceFirst("");
        // As entradas são gravadas na mesma operação que gera o pagamento.
        Instant desde = saida.getCriadoEm().minus(Duration.ofMinutes(5));
        Instant ate = saida.getCriadoEm().plusSeconds(5);
        List<MovimentacaoEstoque> entradas;
        Matcher varios = VARIOS_ITENS.matcher(descricao);
        Matcher um = UM_ITEM.matcher(descricao);
        if (varios.matches()) {
            entradas = estoque.entradasDaCompra(List.of(varios.group(1)), desde, ate);
        } else if (um.matches()) {
            BigDecimal quantidade = new BigDecimal(um.group(1));
            entradas = estoque.entradasAvulsas(um.group(2), desde, ate).stream()
                    .filter(m -> m.getQuantidade().compareTo(quantidade) == 0).limit(1).toList();
        } else {
            throw new RegraDeNegocioException("Este pagamento não veio de uma compra lançada no estoque; use Remover.");
        }
        if (entradas.isEmpty()) {
            throw new RegraDeNegocioException("As entradas desta compra não estão mais no estoque; use Remover para tirar só o pagamento.");
        }
        String motivo = EstoqueService.ESTORNO_DE_IMPORTACAO + descricao;
        entradas.forEach(e -> estoque.estornarEntrada(e.getId(), motivo, usuario));
        saidaService.remover(saidaId, usuario);
        log.info("Compra \"{}\" desfeita por {}: {} entrada(s)", descricao, usuario, entradas.size());
        return new Desfeita(entradas.size(), entradas.stream().map(e -> e.getInsumo().getNome()).distinct().toList());
    }
}
