package com.pointdofrango.caixa;

import com.pointdofrango.caixa.CaixaService.Totais;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.cliente.ClienteService;
import com.pointdofrango.entrega.Bairro;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.entrega.Colaborador.Funcao;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueService;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.UnidadeMedida;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.pedido.CanalVenda;
import com.pointdofrango.pedido.Pedido;
import com.pointdofrango.pedido.PedidoDtos.EntregaRequest;
import com.pointdofrango.pedido.PedidoDtos.ItemRequest;
import com.pointdofrango.pedido.PedidoDtos.NovoPedidoRequest;
import com.pointdofrango.pedido.PedidoService;
import com.pointdofrango.produto.CategoriaProduto;
import com.pointdofrango.produto.Produto;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoService;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Combo de R$ 75 entregue no Parque Santana (+ R$ 4), pago em dinheiro, e o fechamento do dia. */
@SpringBootTest
@ActiveProfiles("test")
class EntregaECaixaIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired PedidoService pedidos;
    @Autowired CaixaService caixa;
    @Autowired EntregaCadastroService cadastros;
    @Autowired ClienteService clientes;
    @Autowired EstoqueService estoque;
    @Autowired ProdutoService produtos;

    private int n;
    private Produto combo;
    private Bairro parqueSantana;
    private Colaborador motoboy;
    private Colaborador ajudante;

    @BeforeEach
    void preparar() {
        n = SEQ.incrementAndGet();
        Insumo frango = estoque.criar(new InsumoRequest("Sassami E" + n, UnidadeMedida.QUILOGRAMA, bd("0"), bd("16.90"),
                bd("10")), "teste");
        combo = produtos.criar(new ProdutoRequest("Combo Especial E" + n, null, CategoriaProduto.COMBO, bd("75"),
                List.of(new ItemFichaRequest(frango.getId(), bd("1")))));
        parqueSantana = cadastros.criarBairro("Parque Santana E" + n, bd("4"));
        motoboy = cadastros.criarColaborador("Motoboy E" + n, Funcao.MOTOBOY, bd("50"));
        ajudante = cadastros.criarColaborador("Ajudante E" + n, Funcao.FUNCIONARIO, bd("80"));
        // cada teste começa com um caixa recém-fechado: o próximo só soma o que vier depois
        if (caixa.atual().isEmpty()) {
            caixa.abrir(BigDecimal.ZERO, "teste");
        }
        caixa.fechar(BigDecimal.ZERO, "limpeza do teste", "teste");
    }

    @Test
    @DisplayName("Entrega: taxa vem do bairro, fica fora do faturamento e o troco é calculado sobre itens + taxa")
    void entregaComTroco() {
        Pedido p = pedidos.lancar(entrega("(11) 98765-4321", bd("100")), "caixa");

        assertThat(p.getValorTotal()).isEqualByComparingTo("75.00");
        assertThat(p.getTaxaEntrega()).isEqualByComparingTo("4.00");
        assertThat(p.totalACobrar()).isEqualByComparingTo("79.00");
        assertThat(p.troco()).isEqualByComparingTo("21.00");
        assertThat(p.getBairroEntrega()).isEqualTo(parqueSantana.getNome());
        assertThat(p.getDistribuicao().valorBruto()).isEqualByComparingTo("75.00");
        assertThat(p.getEnderecoEntrega()).isEqualTo("Rua das Flores, 10 (ref.: portão azul)");

        var cliente = clientes.porTelefone("11987654321").orElseThrow();
        assertThat(cliente.getTelefone()).isEqualTo("11987654321");
        assertThat(cliente.getBairro().getId()).isEqualTo(parqueSantana.getId());
        assertThat(p.getClienteId()).isEqualTo(cliente.getId());
    }

    @Test
    @DisplayName("Entrega: recebido menor que o total, balcão com entrega e bairro inativo são recusados")
    void regrasDaEntrega() {
        assertThatThrownBy(() -> pedidos.lancar(entrega("11912345678", bd("70")), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("menor que o total");

        var balcao = new NovoPedidoRequest(CanalVenda.BALCAO, FormaPagamento.PIX, "Ana", "11912345678", "Rua X", null,
                List.of(new ItemRequest(combo.getId(), 1)), null, new EntregaRequest(parqueSantana.getId(), null, null), false, null, false, null);
        assertThatThrownBy(() -> pedidos.lancar(balcao, "caixa")).isInstanceOf(RegraDeNegocioException.class);

        cadastros.alterarBairro(parqueSantana.getId(), parqueSantana.getNome(), bd("4"), false);
        assertThatThrownBy(() -> pedidos.lancar(entrega("11912345678", null), "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("Não estamos entregando");
    }

    @Test
    @DisplayName("Caixa do dia: abertura + vendas − diária − motoboy (R$ 50 + taxas) = dinheiro esperado")
    void fechamentoDoDia() {
        caixa.abrir(bd("100"), "caixa");
        pedidos.lancar(entrega("11911112222", bd("100")), "caixa");
        pedidos.lancar(entrega("11933334444", null), "caixa");

        caixa.pagarDiaria(ajudante.getId(), null, FormaPagamento.DINHEIRO, "caixa");

        var pendencia = caixa.pendenciasMotoboys().stream()
                .filter(x -> x.motoboy().getId().equals(motoboy.getId())).findFirst().orElseThrow();
        assertThat(pendencia.entregas()).hasSize(2);
        assertThat(pendencia.totalComDiaria()).isEqualByComparingTo("58.00");

        Caixa aberto = caixa.acertarMotoboy(motoboy.getId(), true, FormaPagamento.DINHEIRO, "caixa");
        assertThat(aberto.getMovimentos()).extracting(MovimentoCaixa::getCategoria)
                .containsExactly(CategoriaSaida.FUNCIONARIO, CategoriaSaida.MOTOBOY);
        assertThatThrownBy(() -> caixa.acertarMotoboy(motoboy.getId(), true, FormaPagamento.DINHEIRO, "caixa"))
                .isInstanceOf(RegraDeNegocioException.class);

        Totais t = caixa.totais(caixa.atual().orElseThrow());
        assertThat(t.totalRecebido()).isEqualByComparingTo("158.00");
        assertThat(t.taxasEntrega()).isEqualByComparingTo("8.00");
        // 100 de troco + 158 em dinheiro − 80 da diária − 58 do motoboy
        assertThat(t.dinheiroEsperado()).isEqualByComparingTo("120.00");

        Caixa fechado = caixa.fechar(bd("115"), "faltou troco", "caixa");
        assertThat(fechado.diferenca()).isEqualByComparingTo("-5.00");
        assertThatThrownBy(() -> caixa.registrarSaida(CategoriaSaida.OUTRO, "gelo", bd("10"), FormaPagamento.DINHEIRO, null, null, "caixa"))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("fechado");
    }

    @Test
    @DisplayName("Conta fechada segundos antes de abrir o caixa entra no caixa do mesmo jeito")
    void vendaAntesDeAbrirOCaixa() {
        pedidos.lancar(entrega("11966665555", bd("100")), "caixa");

        Caixa aberto = caixa.abrir(bd("100"), "caixa");

        Totais t = caixa.totais(aberto);
        assertThat(t.totalRecebido()).isEqualByComparingTo("79.00");
        assertThat(t.vendas()).singleElement().satisfies(v -> assertThat(v.valor()).isEqualByComparingTo("79.00"));
        assertThat(t.dinheiroEsperado()).isEqualByComparingTo("179.00");
    }

    @Test
    @DisplayName("Remover o pagamento do motoboy devolve as entregas para a lista de pendentes")
    void desfazerAcerto() {
        caixa.abrir(bd("50"), "caixa");
        Pedido p = pedidos.lancar(entrega("11955556666", null), "caixa");
        Caixa c = caixa.acertarMotoboy(motoboy.getId(), false, FormaPagamento.PIX, "caixa");
        assertThat(pedidos.buscar(p.getId()).acertadoComEntregador()).isTrue();

        caixa.removerMovimento(c.getMovimentos().get(0).getId(), "dono");

        assertThat(pedidos.buscar(p.getId()).acertadoComEntregador()).isFalse();
    }

    @Test
    @DisplayName("LGPD: apagar o cliente tira nome, telefone e endereço dos pedidos dele")
    void esquecerCliente() {
        Pedido p = pedidos.lancar(entrega("11977778888", null), "caixa");
        var cliente = clientes.porTelefone("11977778888").orElseThrow();

        clientes.esquecer(cliente.getId(), "dono");

        Pedido depois = pedidos.buscar(p.getId());
        assertThat(depois.getClienteNome()).isNull();
        assertThat(depois.getClienteTelefone()).isNull();
        assertThat(depois.getEnderecoEntrega()).isNull();
        assertThat(depois.getValorTotal()).isEqualByComparingTo("75.00");
        assertThat(clientes.porTelefone("11977778888")).isEmpty();
    }

    private NovoPedidoRequest entrega(String telefone, BigDecimal recebido) {
        return new NovoPedidoRequest(CanalVenda.WHATSAPP, FormaPagamento.DINHEIRO, "Cliente " + n, telefone,
                "Rua das Flores, 10", null, List.of(new ItemRequest(combo.getId(), 1)), null,
                new EntregaRequest(parqueSantana.getId(), "portão azul", motoboy.getId()), false, recebido, true, null);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
