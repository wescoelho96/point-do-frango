package com.pointdofrango.produto;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    @EntityGraph(attributePaths = {"fichaTecnica", "fichaTecnica.insumo", "fichaTecnica.insumo.embalagens", "fichaTecnica.embalagem"})
    List<Produto> findAllByOrderByCategoriaAscNomeAsc();

    @EntityGraph(attributePaths = {"fichaTecnica", "fichaTecnica.insumo", "fichaTecnica.insumo.embalagens", "fichaTecnica.embalagem"})
    Optional<Produto> findWithFichaById(Long id);

    boolean existsByNomeIgnoreCase(String nome);

    /** Vendido em algum pedido ou usado em promoção. */
    @Query(value = """
            select case when exists (select 1 from pedido_item where produto_id = :id)
                          or exists (select 1 from promocao where produto_compra_id = :id or produto_brinde_id = :id)
                   then true else false end
            """, nativeQuery = true)
    boolean temHistorico(Long id);

    boolean existsByNomeIgnoreCaseAndIdNot(String nome, Long id);

    /**
     * Projeção de propósito: se os Insumo ficassem no contexto de persistência, o FOR UPDATE
     * seguinte devolveria essas instâncias com o saldo antigo.
     */
    @Query("""
            select new com.pointdofrango.produto.ConsumoFicha(f.produto.id, f.insumo.id, f.quantidade)
            from ItemFichaTecnica f where f.produto.id in :produtoIds
            """)
    List<ConsumoFicha> consumoDosProdutos(Collection<Long> produtoIds);
}
