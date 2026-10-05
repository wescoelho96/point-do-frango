package com.pointdofrango.caixa;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface MovimentoCaixaRepository extends JpaRepository<MovimentoCaixa, Long> {

    /** Saídas da gaveta de todos os caixas do período (sangria não conta: o dinheiro só mudou de lugar). */
    @EntityGraph(attributePaths = "colaborador")
    @Query("select m from MovimentoCaixa m where m.tipo = com.pointdofrango.caixa.MovimentoCaixa.Tipo.SAIDA "
            + "and m.categoria <> com.pointdofrango.financeiro.CategoriaSaida.SANGRIA "
            + "and m.criadoEm >= :inicio and m.criadoEm < :fim order by m.criadoEm desc")
    List<MovimentoCaixa> gastosEntre(Instant inicio, Instant fim);
}
