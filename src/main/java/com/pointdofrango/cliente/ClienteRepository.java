package com.pointdofrango.cliente;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    @EntityGraph(attributePaths = "bairro")
    Optional<Cliente> findByTelefone(String telefone);

    @EntityGraph(attributePaths = "bairro")
    @Query("select c from Cliente c where lower(c.nome) like lower(concat('%', :termo, '%')) "
            + "or c.telefone like concat('%', :termo, '%') order by c.ultimoPedidoEm desc")
    List<Cliente> buscar(String termo);

    @EntityGraph(attributePaths = "bairro")
    List<Cliente> findTop200ByOrderByUltimoPedidoEmDesc();

    @Modifying
    @Query("delete from Cliente c where c.ultimoPedidoEm < :limite")
    int removerSemPedidoDesde(Instant limite);
}
