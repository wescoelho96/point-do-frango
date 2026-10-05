package com.pointdofrango.estoque;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface MovimentacaoEstoqueRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    List<MovimentacaoEstoque> findTop100ByInsumoIdOrderByCriadoEmDescIdDesc(Long insumoId);

    List<MovimentacaoEstoque> findByPedidoIdAndTipo(Long pedidoId, TipoMovimentacao tipo);

    @EntityGraph(attributePaths = "insumo")
    @Query("select m from MovimentacaoEstoque m where m.tipo = :tipo and m.criadoEm >= :inicio and m.criadoEm < :fim "
            + "order by m.criadoEm desc, m.id desc")
    List<MovimentacaoEstoque> doTipoNoPeriodo(TipoMovimentacao tipo, Instant inicio, Instant fim);
}
