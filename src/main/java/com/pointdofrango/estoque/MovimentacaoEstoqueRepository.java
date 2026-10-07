package com.pointdofrango.estoque;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MovimentacaoEstoqueRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    List<MovimentacaoEstoque> findTop100ByInsumoIdOrderByCriadoEmDescIdDesc(Long insumoId);

    List<MovimentacaoEstoque> findByPedidoIdAndTipo(Long pedidoId, TipoMovimentacao tipo);

    boolean existsByInsumoIdAndTipoIn(Long insumoId, Collection<TipoMovimentacao> tipos);

    @Modifying
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
