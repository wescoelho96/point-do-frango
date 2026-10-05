package com.pointdofrango.pedido;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    @EntityGraph(attributePaths = {"itens", "comanda", "entregador"})
    Optional<Pedido> findWithItensById(Long id);

    /** Intervalo semiaberto [inicio, fim): meia-noite do dia seguinte não entra. */
    @EntityGraph(attributePaths = {"itens", "comanda", "entregador"})
    @Query("select p from Pedido p where p.criadoEm >= :inicio and p.criadoEm < :fim order by p.criadoEm desc")
    List<Pedido> doPeriodo(Instant inicio, Instant fim);

    @EntityGraph(attributePaths = {"itens", "comanda", "entregador"})
    List<Pedido> findByStatusInOrderByCriadoEmAsc(Collection<StatusPedido> status);

    /** Entregas que o motoboy fez e ainda não recebeu (de qualquer dia). */
    @EntityGraph(attributePaths = {"itens", "comanda", "entregador"})
    @Query("select p from Pedido p where p.entregador is not null and p.acertoEntregadorId is null "
            + "and p.status <> com.pointdofrango.pedido.StatusPedido.CANCELADO order by p.criadoEm")
    List<Pedido> entregasSemAcerto();

    List<Pedido> findByAcertoEntregadorId(Long movimentoId);

    @EntityGraph(attributePaths = {"itens", "comanda", "entregador"})
    @Query("select p from Pedido p where p.formaPagamento = com.pointdofrango.financeiro.FormaPagamento.CORTESIA "
            + "and p.status <> com.pointdofrango.pedido.StatusPedido.CANCELADO and p.criadoEm >= :desde order by p.criadoEm desc")
    List<Pedido> cortesiasDesde(Instant desde);

    @Modifying
    @Query("update Pedido p set p.clienteNome = null, p.clienteTelefone = null, p.enderecoEntrega = null, "
            + "p.clienteId = null where p.clienteId = :clienteId or p.clienteTelefone = :telefone")
    int anonimizarCliente(Long clienteId, String telefone);

    @Modifying
    @Query("update Pedido p set p.clienteNome = null, p.clienteTelefone = null, p.enderecoEntrega = null, "
            + "p.clienteId = null where p.criadoEm < :limite and (p.clienteNome is not null "
            + "or p.clienteTelefone is not null or p.enderecoEntrega is not null or p.clienteId is not null)")
    int anonimizarAnterioresA(Instant limite);

    @Query("select coalesce(sum(p.valorTotal), 0) from Pedido p where p.status <> com.pointdofrango.pedido.StatusPedido.CANCELADO "
            + "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    BigDecimal faturamentoEntre(Instant inicio, Instant fim);

    /** O que os clientes pagaram de taxa (entrega grátis entra como zero). */
    @Query("select coalesce(sum(coalesce(p.taxaCobrada, p.taxaEntrega)), 0) from Pedido p where p.status <> com.pointdofrango.pedido.StatusPedido.CANCELADO "
            + "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    BigDecimal taxasEntregaEntre(Instant inicio, Instant fim);

    boolean existsBy();

    boolean existsByCanalAndCodigoExterno(CanalVenda canal, String codigoExterno);
}
