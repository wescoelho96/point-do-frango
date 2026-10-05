package com.pointdofrango.estoque;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InsumoRepository extends JpaRepository<Insumo, Long> {

    @EntityGraph(attributePaths = "embalagens")
    List<Insumo> findAllByOrderByNomeAsc();

    @EntityGraph(attributePaths = "embalagens")
    Optional<Insumo> findWithEmbalagensById(Long id);

    /** Com embalagens: a resposta do produto mostra o rendimento por embalagem. */
    @EntityGraph(attributePaths = "embalagens")
    List<Insumo> findByIdIn(Collection<Long> ids);

    boolean existsByNomeIgnoreCase(String nome);

    /**
     * Lock pessimista: balcão e WhatsApp vendendo a última porção ao mesmo tempo, o segundo
     * espera e já vê o saldo baixado. Ordem por id para evitar deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Insumo i where i.id in :ids order by i.id")
    List<Insumo> travarPorIds(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Insumo i where i.id = :id")
    Optional<Insumo> travarPorId(Long id);
}
