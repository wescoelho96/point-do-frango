package com.pointdofrango.estoque;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoteValidadeRepository extends JpaRepository<LoteValidade, Long> {

    List<LoteValidade> findAllByOrderByCriadoEmDescIdDesc();
}
