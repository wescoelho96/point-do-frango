package com.pointdofrango.pedido;

import com.pointdofrango.financeiro.DistribuicaoFinanceira;
import com.pointdofrango.financeiro.FormaPagamento;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public final class PedidoDtos {

    private PedidoDtos() {
    }

    /** Sem preço de propósito: o valor sempre vem do cardápio. */
    public record ItemRequest(@NotNull Long produtoId, @Min(1) @Max(99) int quantidade) {
    }

    /** Sem comandaId é venda direta e a forma de pagamento é obrigatória; com comandaId ela fica para o fechamento. */
    public record NovoPedidoRequest(
            @NotNull CanalVenda canal,
            FormaPagamento formaPagamento,
            @Size(max = 100) String clienteNome,
            @Size(max = 30) String clienteTelefone,
            @Size(max = 255) String enderecoEntrega,
            @Size(max = 255) String observacao,
            @NotEmpty(message = "O pedido precisa de pelo menos um item") @Size(max = 50) List<@Valid ItemRequest> itens,
            Long comandaId,
            @Valid EntregaRequest entrega,
            /* ignora os brindes da promoção do dia */
            boolean semPromocao,
            /* dinheiro: quanto o cliente deu, ou o "troco para" na entrega */
            @DecimalMin("0") @DecimalMax("99999") BigDecimal valorRecebido,
            boolean salvarCliente,
            /* obrigatório na cortesia */
            @Size(max = 150) String motivoCortesia) {

        public NovoPedidoRequest(CanalVenda canal, FormaPagamento formaPagamento, String clienteNome,
                                 String clienteTelefone, String enderecoEntrega, String observacao,
                                 List<ItemRequest> itens, Long comandaId) {
            this(canal, formaPagamento, clienteNome, clienteTelefone, enderecoEntrega, observacao, itens, comandaId,
                    null, false, null, false, null);
        }

        public NovoPedidoRequest(CanalVenda canal, FormaPagamento formaPagamento, String clienteNome,
                                 String clienteTelefone, String enderecoEntrega, String observacao,
                                 List<ItemRequest> itens) {
            this(canal, formaPagamento, clienteNome, clienteTelefone, enderecoEntrega, observacao, itens, null);
        }
    }

    /** Sem taxa: ela vem do cadastro do bairro. */
    public record EntregaRequest(@NotNull Long bairroId, @Size(max = 150) String referencia, Long entregadorId,
                                 boolean gratis, boolean peloDono) {

        public EntregaRequest(Long bairroId, String referencia, Long entregadorId) {
            this(bairroId, referencia, entregadorId, false, false);
        }
    }

    /** Motoboy ou o próprio dono; ambos vazios deixa a entrega sem responsável. */
    public record EntregadorRequest(Long entregadorId, boolean dono) {
    }

    /** Pedido de automação do WhatsApp; o canal é definido pelo servidor. */
    public record IntegracaoPedidoRequest(
            @NotNull FormaPagamento formaPagamento,
            @Size(max = 100) String clienteNome,
            @Size(max = 30) String clienteTelefone,
            @Size(max = 255) String enderecoEntrega,
            @Size(max = 255) String observacao,
            @NotEmpty @Size(max = 50) List<@Valid ItemRequest> itens) {

        public NovoPedidoRequest comoPedido(CanalVenda canal) {
            return new NovoPedidoRequest(canal, formaPagamento, clienteNome, clienteTelefone, enderecoEntrega,
                    observacao, itens);
        }
    }

    /**
     * Valores copiados do extrato do app. valorPedido não inclui a entrega; descontoLoja é o cupom
     * bancado pela loja; taxasPlataforma soma comissão e pagamento online.
     */
    public record PedidoPlataformaRequest(
            @NotNull CanalVenda plataforma,
            @Size(max = 50) String codigoExterno,
            /* hora local da loja; nulo usa o momento atual */
            LocalDateTime realizadoEm,
            @Size(max = 100) String clienteNome,
            @Size(max = 255) String observacao,
            @NotEmpty(message = "Informe os itens do pedido") @Size(max = 50) List<@Valid ItemRequest> itens,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal valorPedido,
            @DecimalMin("0") BigDecimal descontoLoja,
            @NotNull @DecimalMin("0") BigDecimal taxasPlataforma,
            boolean enviarParaCozinha) {
    }

    public record StatusRequest(@NotNull StatusPedido status) {
    }

    public record CancelamentoRequest(@NotBlank @Size(max = 255) String motivo) {
    }

    public record ItemResponse(Long produtoId, String produto, int quantidade, BigDecimal precoUnitario,
                               BigDecimal subtotal, boolean brinde, String promocao) {
    }

    /** taxa é o que o motoboy recebe; taxaCobrada, o que o cliente pagou (zero na entrega grátis). */
    public record EntregaResponse(String bairro, BigDecimal taxa, BigDecimal taxaCobrada, boolean gratis,
                                  Long entregadorId, String entregador, boolean peloDono, boolean pagaAoMotoboy) {

        static EntregaResponse de(Pedido p) {
            if (!p.entrega()) {
                return null;
            }
            var motoboy = p.getEntregador();
            return new EntregaResponse(p.getBairroEntrega(), p.getTaxaEntrega(), p.taxaCobrada(), p.entregaGratis(),
                    motoboy != null ? motoboy.getId() : null, motoboy != null ? motoboy.getNome() : null,
                    p.isEntregaPeloDono(), p.acertadoComEntregador());
        }
    }

    /** totalACobrar inclui a taxa de entrega; troco só existe no pagamento em dinheiro. */
    public record PedidoResponse(Long id, CanalVenda canal, StatusPedido status, FormaPagamento formaPagamento,
                                 String clienteNome, String clienteTelefone, String enderecoEntrega,
                                 String observacao, BigDecimal valorTotal, DistribuicaoFinanceira distribuicao,
                                 String criadoPor, Instant criadoEm, Instant atualizadoEm, String motivoCancelamento,
                                 Long comandaId, String comanda, String codigoExterno, BigDecimal taxaPlataforma,
                                 BigDecimal descontoLoja, BigDecimal valorCardapio, List<ItemResponse> itens,
                                 EntregaResponse entrega, BigDecimal totalACobrar, BigDecimal valorRecebido,
                                 BigDecimal troco, String motivoCortesia) {

        public static PedidoResponse de(Pedido p) {
            Comanda c = p.getComanda();
            return new PedidoResponse(p.getId(), p.getCanal(), p.getStatus(), p.getFormaPagamento(),
                    p.getClienteNome(), p.getClienteTelefone(), p.getEnderecoEntrega(), p.getObservacao(),
                    p.getValorTotal(), p.getDistribuicao(), p.getCriadoPor(), p.getCriadoEm(), p.getAtualizadoEm(),
                    p.getMotivoCancelamento(), c != null ? c.getId() : null, c != null ? c.getIdentificacao() : null,
                    p.getCodigoExterno(), p.getTaxaPlataforma(), p.getDescontoLoja(), p.valorCardapio(),
                    p.getItens().stream().map(i -> new ItemResponse(i.getProdutoId(), i.getNomeProduto(),
                            i.getQuantidade(), i.getPrecoUnitario(), i.subtotal(), i.isBrinde(), i.getPromocao())).toList(),
                    EntregaResponse.de(p), p.totalACobrar(), p.getValorRecebido(), p.troco(), p.getMotivoCortesia());
        }

        /** Versão para o atendente: sem custos nem potes financeiros. */
        public PedidoResponse semFinanceiro() {
            return new PedidoResponse(id, canal, status, formaPagamento, clienteNome, clienteTelefone,
                    enderecoEntrega, observacao, valorTotal, null, criadoPor, criadoEm, atualizadoEm,
                    motivoCancelamento, comandaId, comanda, codigoExterno, null, null, null, itens,
                    entrega, totalACobrar, valorRecebido, troco, motivoCortesia);
        }

        /** A cozinha precisa saber o que fazer e se é entrega; o telefone do cliente não. */
        public PedidoResponse paraCozinha() {
            return new PedidoResponse(id, canal, status, formaPagamento, clienteNome, null,
                    enderecoEntrega, observacao, valorTotal, null, criadoPor, criadoEm, atualizadoEm,
                    motivoCancelamento, comandaId, comanda, codigoExterno, null, null, null, itens,
                    entrega, totalACobrar, valorRecebido, troco, motivoCortesia);
        }
    }
}
