package com.pointdofrango.pedido;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ComandaRepository extends JpaRepository<Comanda, Long> {

    @EntityGraph(attributePaths = {"pedidos", "pedidos.itens"})
    Optional<Comanda> findWithPedidosById(Long id);

    @EntityGraph(attributePaths = {"pedidos", "pedidos.itens"})
    List<Comanda> findByStatusOrderByAbertaEmAsc(StatusComanda status);

    @EntityGraph(attributePaths = {"pedidos", "pedidos.itens"})
    @Query("select c from Comanda c where c.status <> com.pointdofrango.pedido.StatusComanda.ABERTA "
            + "and c.fechadaEm >= :inicio and c.fechadaEm < :fim order by c.fechadaEm desc")
    List<Comanda> encerradasNoPeriodo(Instant inicio, Instant fim);

    boolean existsByIdentificacaoIgnoreCaseAndStatus(String identificacao, StatusComanda status);

    /** Total de taxa de serviço recebida no período (repasse à equipe). */
    @Query("select coalesce(sum(c.valorServico), 0) from Comanda c where c.status = com.pointdofrango.pedido.StatusComanda.FECHADA "
            + "and c.fechadaEm >= :inicio and c.fechadaEm < :fim")
    BigDecimal servicoNoPeriodo(Instant inicio, Instant fim);
}
