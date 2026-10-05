package com.pointdofrango.caixa;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaixaRepository extends JpaRepository<Caixa, Long> {

    @EntityGraph(attributePaths = {"movimentos", "movimentos.colaborador"})
    Optional<Caixa> findFirstByStatus(Caixa.Status status);

    @EntityGraph(attributePaths = {"movimentos", "movimentos.colaborador"})
    Optional<Caixa> findWithMovimentosById(Long id);

    @EntityGraph(attributePaths = {"movimentos", "movimentos.colaborador"})
    List<Caixa> findTop30ByStatusOrderByAbertoEmDesc(Caixa.Status status);

    Optional<Caixa> findFirstByStatusOrderByFechadoEmDesc(Caixa.Status status);
}
