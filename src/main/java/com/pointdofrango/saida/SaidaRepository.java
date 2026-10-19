package com.pointdofrango.saida;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface SaidaRepository extends JpaRepository<Saida, Long> {

    List<Saida> findByGrupoParcelas(String grupoParcelas);

    /** Tudo o que já foi pago ou parcelado em compra de mercadoria, por fora do caixa. */
    @Query("select coalesce(sum(s.valor), 0) from Saida s where s.categoria = com.pointdofrango.financeiro.CategoriaSaida.INSUMOS")
    BigDecimal totalDeCompras();

    @Query("select min(s.data) from Saida s where s.categoria = com.pointdofrango.financeiro.CategoriaSaida.INSUMOS")
    LocalDate primeiraCompra();

    List<Saida> findByCriadoEmBetween(Instant desde, Instant ate);

    /** Datas inclusivas. */
    @EntityGraph(attributePaths = {"fornecedor", "colaborador"})
    List<Saida> findByDataBetweenOrderByDataDescIdDesc(LocalDate inicio, LocalDate fim);
}
