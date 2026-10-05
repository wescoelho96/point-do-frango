package com.pointdofrango.saida;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SaidaRepository extends JpaRepository<Saida, Long> {

    /** Datas inclusivas. */
    @EntityGraph(attributePaths = {"fornecedor", "colaborador"})
    List<Saida> findByDataBetweenOrderByDataDescIdDesc(LocalDate inicio, LocalDate fim);
}
