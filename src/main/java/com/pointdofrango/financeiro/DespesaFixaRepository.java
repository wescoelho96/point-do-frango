package com.pointdofrango.financeiro;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DespesaFixaRepository extends JpaRepository<DespesaFixa, Long> {

    List<DespesaFixa> findAllByOrderByDiaVencimentoAscDescricaoAsc();

    List<DespesaFixa> findByAtivaTrue();
}
