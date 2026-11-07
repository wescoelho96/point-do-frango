package com.pointdofrango.site;

import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.produto.CategoriaProduto;
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
import java.util.List;

/** O que o cliente vê e manda pelo site. Sem custo, estoque ou qualquer dado interno. */
public final class PedidoOnlineDtos {

    private PedidoOnlineDtos() {
    }

    /** aberto: o caixa da loja está aberto (fora disso o site não aceita pedido). */
    public record Cardapio(String loja, String telefone, String endereco, boolean aberto, boolean entregaGratisHoje,
                           List<ItemCardapio> itens, List<BairroEntrega> bairros) {
    }

    public record ItemCardapio(Long id, String nome, String descricao, CategoriaProduto categoria, String categoriaRotulo,
                               BigDecimal preco, boolean disponivel) {
    }

    public record BairroEntrega(Long id, String nome, BigDecimal taxa) {
    }

    public record Item(@NotNull Long produtoId, @Min(1) @Max(20) int quantidade) {
    }

    /**
     * bairroId nulo = retirada na loja. trocoPara só no dinheiro. site é um campo escondido que pessoas não
     * preenchem: veio preenchido, é robô.
     */
    public record PedidoRequest(
            @NotBlank @Size(max = 100) String nome,
            @NotBlank @Size(max = 30) String telefone,
            Long bairroId,
            @Size(max = 255) String endereco,
            @Size(max = 150) String referencia,
            @NotNull FormaPagamento formaPagamento,
            @DecimalMin("0") @DecimalMax("1000") BigDecimal trocoPara,
            @Size(max = 255) String observacao,
            boolean salvarDados,
            @NotEmpty(message = "Escolha pelo menos um item") @Size(max = 30) List<@Valid Item> itens,
            String site) {
    }

    public record PedidoCriado(Long numero, BigDecimal itens, BigDecimal taxaEntrega, BigDecimal total, boolean entrega,
                               String formaPagamento, BigDecimal troco) {
    }
}
