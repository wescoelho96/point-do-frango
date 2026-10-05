package com.pointdofrango.produto;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface ItemFichaTecnicaRepository extends JpaRepository<ItemFichaTecnica, Long> {

    /**
     * Flush mode COMMIT: sem ele, remover uma embalagem em uso estoura a FK no flush antes da
     * validação que dá a mensagem amigável.
     */
    @EntityGraph(attributePaths = {"produto", "embalagem"})
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    List<ItemFichaTecnica> findByInsumoIdOrderByProdutoNomeAsc(Long insumoId);
}
