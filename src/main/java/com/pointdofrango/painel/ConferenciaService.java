package com.pointdofrango.painel;

import com.pointdofrango.caixa.MovimentoCaixaRepository;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.InsumoRepository;
import com.pointdofrango.estoque.MovimentacaoEstoqueRepository;
import com.pointdofrango.estoque.TipoMovimentacao;
import com.pointdofrango.saida.SaidaRepository;
import com.pointdofrango.shared.Dinheiro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Conferência pelo dinheiro, desde a primeira compra: tudo o que foi comprado menos o que as vendas, cortesias e a
 * equipe baixaram e menos o que está no estoque. O que sobra é mercadoria que sumiu (desperdício, porção maior que a
 * ficha, item fora da ficha). Não depende de as contagens terem corrigido lançamentos antigos.
 */
@Service
public class ConferenciaService {

    public record Conferencia(LocalDate desde, BigDecimal compras, BigDecimal baixado, BigDecimal estoque,
                              BigDecimal semExplicacao) {
    }

    private static final List<TipoMovimentacao> BAIXAS =
            List.of(TipoMovimentacao.SAIDA_VENDA, TipoMovimentacao.ESTORNO_VENDA, TipoMovimentacao.CONSUMO_INTERNO);

    private final SaidaRepository saidas;
    private final MovimentoCaixaRepository caixa;
    private final MovimentacaoEstoqueRepository movimentacoes;
    private final InsumoRepository insumos;

    public ConferenciaService(SaidaRepository saidas, MovimentoCaixaRepository caixa, MovimentacaoEstoqueRepository movimentacoes,
                              InsumoRepository insumos) {
        this.saidas = saidas;
        this.caixa = caixa;
        this.movimentacoes = movimentacoes;
        this.insumos = insumos;
    }

    @Transactional(readOnly = true)
    public Conferencia conferir() {
        BigDecimal compras = saidas.totalDeCompras().add(caixa.totalDeCompras());
        BigDecimal baixado = movimentacoes.valorPorTipos(BAIXAS).negate();
        BigDecimal estoque = insumos.findAll().stream().filter(Insumo::isAtivo)
                .filter(i -> i.getEstoqueAtual().signum() > 0)
                .map(i -> i.getEstoqueAtual().multiply(i.getCustoUnitario()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Conferencia(saidas.primeiraCompra(), Dinheiro.centavos(compras), Dinheiro.centavos(baixado),
                Dinheiro.centavos(estoque), Dinheiro.centavos(compras.subtract(baixado).subtract(estoque)));
    }
}
