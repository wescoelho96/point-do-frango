package com.pointdofrango.pedido;

import com.pointdofrango.financeiro.FormaPagamento;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ComandaDtos {

    private ComandaDtos() {
    }

    public record AbrirRequest(@NotBlank @Size(max = 50) String identificacao, @Size(max = 255) String observacao) {
    }

    public record FecharRequest(@NotNull FormaPagamento formaPagamento, boolean cobrarServico,
                                @DecimalMin("0") @DecimalMax("99999") BigDecimal valorRecebido) {
    }

    public record CancelarRequest(@NotBlank @Size(max = 255) String motivo) {
    }

    /** Linha da conta, somando o mesmo produto de pedidos diferentes. */
    public record ItemConta(Long produtoId, String produto, int quantidade, BigDecimal precoUnitario, BigDecimal subtotal,
                            boolean brinde) {
    }

    public record PedidoDaComanda(Long id, StatusPedido status, Instant criadoEm, String criadoPor, BigDecimal valorTotal,
                                  String itens) {
    }

    /** Aberta, usa o percentual de serviço atual da loja; fechada, os valores gravados no fechamento. */
    public record ComandaResponse(Long id, String identificacao, StatusComanda status, String observacao,
                                  String abertaPor, Instant abertaEm, String fechadaPor, Instant fechadaEm,
                                  FormaPagamento formaPagamento, BigDecimal consumo, BigDecimal percentualServico,
                                  BigDecimal valorServico, BigDecimal totalComServico, Boolean cobrarServico,
                                  BigDecimal valorTotal, String motivoCancelamento, List<ItemConta> itens,
                                  List<PedidoDaComanda> pedidos, BigDecimal valorRecebido, BigDecimal troco) {

        public static ComandaResponse de(Comanda c, BigDecimal percentualServicoAtual) {
            boolean aberta = c.getStatus() == StatusComanda.ABERTA;
            BigDecimal consumo = aberta ? c.consumo() : c.getValorItens() != null ? c.getValorItens() : c.consumo();
            BigDecimal percentual = aberta || c.getPercentualServico() == null ? percentualServicoAtual : c.getPercentualServico();
            BigDecimal servico = Comanda.servicoSobre(consumo, percentual);
            return new ComandaResponse(c.getId(), c.getIdentificacao(), c.getStatus(), c.getObservacao(), c.getAbertaPor(),
                    c.getAbertaEm(), c.getFechadaPor(), c.getFechadaEm(), c.getFormaPagamento(), consumo, percentual,
                    aberta ? servico : c.getValorServico(), consumo.add(servico), c.getCobrarServico(),
                    aberta ? null : c.getValorTotal(), c.getMotivoCancelamento(), agrupar(c), pedidos(c),
                    c.getValorRecebido(), c.troco());
        }

        private static List<ItemConta> agrupar(Comanda c) {
            // brinde fica em linha separada do mesmo produto vendido
            Map<String, ItemConta> porProduto = new LinkedHashMap<>();
            c.pedidosValidos().forEach(p -> p.getItens().forEach(i -> porProduto.merge(i.getProdutoId() + ":" + i.isBrinde(),
                    new ItemConta(i.getProdutoId(), i.getNomeProduto(), i.getQuantidade(), i.getPrecoUnitario(), i.subtotal(),
                            i.isBrinde()),
                    (a, b) -> new ItemConta(a.produtoId(), a.produto(), a.quantidade() + b.quantidade(),
                            a.precoUnitario(), a.subtotal().add(b.subtotal()), a.brinde()))));
            return List.copyOf(porProduto.values());
        }

        private static List<PedidoDaComanda> pedidos(Comanda c) {
            return c.getPedidos().stream().map(p -> new PedidoDaComanda(p.getId(), p.getStatus(), p.getCriadoEm(),
                    p.getCriadoPor(), p.getValorTotal(), p.getItens().stream()
                    .map(i -> i.getQuantidade() + "× " + i.getNomeProduto() + (i.isBrinde() ? " (brinde)" : "")).reduce((a, b) -> a + ", " + b).orElse("")))
                    .toList();
        }
    }
}
