package com.pointdofrango.notafiscal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemFornecedorRepository extends JpaRepository<ItemFornecedor, Long> {

    List<ItemFornecedor> findByFornecedorId(Long fornecedorId);

    Optional<ItemFornecedor> findByFornecedorIdAndCodigoProduto(Long fornecedorId, String codigoProduto);
}
