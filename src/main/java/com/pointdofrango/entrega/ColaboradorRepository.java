package com.pointdofrango.entrega;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ColaboradorRepository extends JpaRepository<Colaborador, Long> {

    List<Colaborador> findAllByOrderByAtivoDescNomeAsc();
}
