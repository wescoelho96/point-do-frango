package com.pointdofrango.estoque;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LoteValidadeRepository extends JpaRepository<LoteValidade, Long> {

    List<LoteValidade> findAllByOrderByCriadoEmDescIdDesc();

    @Modifying
    @Query("update LoteValidade l set l.insumoId = :destino where l.insumoId = :origem")
    void transferir(Long origem, Long destino);
}
