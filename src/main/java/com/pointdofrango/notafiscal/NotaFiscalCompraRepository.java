package com.pointdofrango.notafiscal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotaFiscalCompraRepository extends JpaRepository<NotaFiscalCompra, Long> {

    boolean existsByChave(String chave);

    List<NotaFiscalCompra> findTop30ByOrderByImportadaEmDesc();
}
