package com.pointdofrango.lgpd;

import com.pointdofrango.cliente.ClienteService;
import com.pointdofrango.config.AppProperties;
import com.pointdofrango.pedido.PedidoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Retenção (LGPD, art. 15 e 16): toda madrugada, pedidos mais antigos que o prazo perdem nome,
 * telefone e endereço (os valores ficam no financeiro) e clientes sem pedido nesse período são apagados.
 */
@Component
public class RetencaoDadosPessoais {

    private static final Logger log = LoggerFactory.getLogger(RetencaoDadosPessoais.class);

    private final PedidoService pedidos;
    private final ClienteService clientes;
    private final Clock clock;
    private final int dias;

    public RetencaoDadosPessoais(PedidoService pedidos, ClienteService clientes, Clock clock, AppProperties props) {
        this.pedidos = pedidos;
        this.clientes = clientes;
        this.clock = clock;
        this.dias = props.lgpd() != null && props.lgpd().retencaoDias() > 0 ? props.lgpd().retencaoDias() : 365;
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "${app.zona}")
    @Transactional
    public void executar() {
        Instant limite = Instant.now(clock).minus(dias, ChronoUnit.DAYS);
        int anonimizados = pedidos.anonimizarAnterioresA(limite);
        int removidos = clientes.removerSemPedidoDesde(limite);
        if (anonimizados + removidos > 0) {
            log.info("Retenção LGPD ({} dias): {} pedido(s) anonimizado(s), {} cliente(s) removido(s)",
                    dias, anonimizados, removidos);
        }
    }
}
