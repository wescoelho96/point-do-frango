package com.pointdofrango.produto;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromocaoRepository extends JpaRepository<Promocao, Long> {

    @EntityGraph(attributePaths = {"produtoCompra", "produtoBrinde"})
    List<Promocao> findAllByOrderByAtivaDescNomeAsc();
}
