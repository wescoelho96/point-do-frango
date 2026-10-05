package com.pointdofrango.pedido;

import org.junit.jupiter.api.Test;

import static com.pointdofrango.pedido.StatusPedido.CANCELADO;
import static com.pointdofrango.pedido.StatusPedido.EM_PREPARO;
import static com.pointdofrango.pedido.StatusPedido.ENTREGUE;
import static com.pointdofrango.pedido.StatusPedido.PRONTO;
import static org.assertj.core.api.Assertions.assertThat;

class StatusPedidoTest {

    @Test
    void fluxoNormal() {
        assertThat(EM_PREPARO.podeIrPara(PRONTO)).isTrue();
        assertThat(PRONTO.podeIrPara(ENTREGUE)).isTrue();
        assertThat(PRONTO.podeIrPara(EM_PREPARO)).isTrue(); // "voltar" na cozinha
    }

    @Test
    void naoPulaEtapas() {
        assertThat(EM_PREPARO.podeIrPara(ENTREGUE)).isFalse();
        assertThat(ENTREGUE.podeIrPara(EM_PREPARO)).isFalse();
    }

    @Test
    void canceladoEhFinal() {
        assertThat(EM_PREPARO.podeIrPara(CANCELADO)).isTrue();
        assertThat(ENTREGUE.podeIrPara(CANCELADO)).isTrue();
        for (StatusPedido s : StatusPedido.values()) {
            assertThat(CANCELADO.podeIrPara(s)).isFalse();
        }
    }
}
