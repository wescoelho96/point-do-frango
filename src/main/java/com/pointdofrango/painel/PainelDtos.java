package com.pointdofrango.painel;

import com.pointdofrango.financeiro.DistribuicaoFinanceira;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public final class PainelDtos {

    private PainelDtos() {
    }

    public record Resumo(LocalDate inicio, LocalDate fim, int quantidadePedidos, int pedidosCancelados,
                         BigDecimal faturamentoBruto, BigDecimal ticketMedio, DistribuicaoFinanceira potes,
                         BigDecimal margemLiquidaPercentual,
                         /* 10% das comandas: é da equipe, fica fora dos potes */
                         BigDecimal taxaServico, List<Agrupamento> porCanal,
                         List<Agrupamento> porFormaPagamento, List<MaisVendido> maisVendidos, List<Dia> porDia,
                         ContasDoMes contasDoMes, List<AlertaEstoque> alertasEstoque,
                         EquipeEEntregas equipeEEntregas, DistribuicaoFinanceira potesFinais, Cortesias cortesias) {
    }

    /** custo: insumos consumidos pelas cortesias, já descontado do lucro. */
    public record Cortesias(int quantidade, BigDecimal custo) {
    }

    /**
     * Pagamentos à equipe no período, descontados do lucro antes de dividir pró-labore e reserva.
     * descontadoDoLucro = equipe + motoboys + outras - taxasEntregaRecebidas; negativo aumenta o lucro.
     */
    public record EquipeEEntregas(BigDecimal equipe, BigDecimal motoboys, BigDecimal outras,
                                  BigDecimal taxasEntregaRecebidas, BigDecimal descontadoDoLucro) {
    }

    /** taxas: maquininha na venda direta; comissão e pagamento online no iFood/99Food. */
    public record Agrupamento(String chave, String rotulo, int pedidos, BigDecimal valor, BigDecimal taxas) {
    }

    public record MaisVendido(Long produtoId, String produto, int quantidade, BigDecimal valor) {
    }

    public record Dia(LocalDate data, int pedidos, BigDecimal faturamento, BigDecimal lucroLiquido) {
    }

    /** percentualSugerido = contas do mês / faturamento dos últimos 30 dias, o percentual que cobriria as contas. */
    public record ContasDoMes(YearMonth mes, BigDecimal totalContas, BigDecimal separadoNoMes, BigDecimal faltaSeparar,
                              BigDecimal percentualCoberto, BigDecimal percentualConfigurado,
                              BigDecimal percentualSugerido) {
    }

    public record AlertaEstoque(Long insumoId, String insumo, BigDecimal estoqueAtual, BigDecimal estoqueMinimo,
                                String sigla) {
    }
}
