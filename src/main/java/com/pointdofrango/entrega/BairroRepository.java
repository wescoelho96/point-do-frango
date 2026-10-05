package com.pointdofrango.entrega;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BairroRepository extends JpaRepository<Bairro, Long> {

    List<Bairro> findAllByOrderByNomeAsc();

    boolean existsByNomeIgnoreCase(String nome);

    boolean existsByNomeIgnoreCaseAndIdNot(String nome, Long id);
}
