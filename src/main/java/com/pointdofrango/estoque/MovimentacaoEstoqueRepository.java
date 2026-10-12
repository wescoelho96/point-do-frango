package com.pointdofrango.estoque;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MovimentacaoEstoqueRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    List<MovimentacaoEstoque> findTop100ByInsumoIdOrderByCriadoEmDescIdDesc(Long insumoId);

    List<MovimentacaoEstoque> findByPedidoIdAndTipo(Long pedidoId, TipoMovimentacao tipo);

    boolean existsByInsumoIdAndTipoIn(Long insumoId, Collection<TipoMovimentacao> tipos);

    @EntityGraph(attributePaths = "insumo")
    List<MovimentacaoEstoque> findByTipoAndObservacaoInAndCriadoEmBetween(TipoMovimentacao tipo, Collection<String> observacoes,
                                                                          Instant desde, Instant ate);

    Optional<MovimentacaoEstoque> findFirstByInsumoIdAndIdLessThanOrderByIdDesc(Long insumoId, Long id);

    @Query("select m.id from MovimentacaoEstoque m where m.insumo.id = :insumoId")
    List<Long> idsDoInsumo(Long insumoId);

    /** Última entrada de cada insumo, se for recente: o que acabou de chegar. */
    @EntityGraph(attributePaths = "insumo")
    @Query("select m from MovimentacaoEstoque m where m.criadoEm >= :desde and m.id in "
            + "(select max(e.id) from MovimentacaoEstoque e where e.tipo = :tipo group by e.insumo.id)")
    List<MovimentacaoEstoque> ultimasEntradas(TipoMovimentacao tipo, Instant desde);

    /** Entradas da mesma origem depois do último estorno de importação do insumo (as desfeitas não contam). */
    @Query("select coalesce(sum(m.quantidade), 0) from MovimentacaoEstoque m where m.insumo.id = :insumoId "
            + "and m.tipo = com.pointdofrango.estoque.TipoMovimentacao.ENTRADA and m.observacao = :origem and m.criadoEm >= :desde "
            + "and m.id > coalesce((select max(a.id) from MovimentacaoEstoque a where a.insumo.id = :insumoId "
            + "and a.tipo = com.pointdofrango.estoque.TipoMovimentacao.AJUSTE and a.observacao like 'Importação desfeita%'), 0)")
    BigDecimal somaEntradas(Long insumoId, String origem, Instant desde);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MovimentacaoEstoque m where m.insumo.id = :insumoId")
    void apagarDoInsumo(Long insumoId);

    /** Vendas e estornos posteriores à última entrada de cada insumo: [insumoId, quantidade, quantidade × custo]. */
    @Query("select m.insumo.id, sum(m.quantidade), sum(m.quantidade * m.custoUnitario) from MovimentacaoEstoque m "
            + "where m.tipo in :tipos and not exists (select e.id from MovimentacaoEstoque e "
            + "where e.insumo = m.insumo and e.tipo = :entrada and e.id > m.id) group by m.insumo.id")
    List<Object[]> vendasDesdeAUltimaEntrada(Collection<TipoMovimentacao> tipos, TipoMovimentacao entrada);

    @EntityGraph(attributePaths = "insumo")
    @Query("select m from MovimentacaoEstoque m where m.tipo = :tipo and m.criadoEm >= :inicio and m.criadoEm < :fim "
            + "order by m.criadoEm desc, m.id desc")
    List<MovimentacaoEstoque> doTipoNoPeriodo(TipoMovimentacao tipo, Instant inicio, Instant fim);
}
