package com.pointdofrango.pedido;

import com.pointdofrango.financeiro.ConfiguracaoFinanceira;
import com.pointdofrango.financeiro.ConfiguracaoFinanceiraService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;

/**
 * Rateio "contas por dia": as contas fixas do mês viram uma cota por dia trabalhado. A sobra de cada venda
 * (depois da taxa e da reposição dos insumos) paga primeiro a cota do dia, mais o que faltou nos dias
 * anteriores; o que passa disso é lucro do dia (pró-labore e reserva). Assim o dono sabe todo dia quanto
 * ganhou, sem esperar o pote do mês encher. Cancelar devolve a parte daquela venda; as próximas completam.
 */
@Component
class ContasPrimeiro {

    private final PedidoRepository pedidos;
    private final ConfiguracaoFinanceiraService financeiro;
    private final ZoneId zona;

    ContasPrimeiro(PedidoRepository pedidos, ConfiguracaoFinanceiraService financeiro, ZoneId zona) {
        this.pedidos = pedidos;
        this.financeiro = financeiro;
        this.zona = zona;
    }

    /** Chamado depois que a distribuição dos pedidos foi (re)calculada, com o pote de contas zerado. */
    void aplicar(Collection<Pedido> recalculados) {
        ConfiguracaoFinanceira config = financeiro.obter();
        if (!config.isContasPrimeiro()) {
            return;
        }
        BigDecimal contasDoMes = financeiro.totalMensalDespesasAtivas();
        for (Pedido p : recalculados) {
            if (p.cancelado() || p.getDistribuicao() == null) {
                continue;
            }
            LocalDate dia = p.getCriadoEm().atZone(zona).toLocalDate();
            YearMonth mes = YearMonth.from(dia);
            Instant inicio = mes.atDay(1).atStartOfDay(zona).toInstant();
            Instant fim = mes.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant();
            // a consulta faz flush antes: os pedidos já ajustados neste laço entram na soma
            BigDecimal falta = config.contasAte(contasDoMes, dia).subtract(pedidos.contasGuardadasEntre(inicio, fim))
                    .max(BigDecimal.ZERO);
            p.reservarParaContas(falta.min(p.sobraLivre()), config.getPercentualProLabore());
        }
    }
}
