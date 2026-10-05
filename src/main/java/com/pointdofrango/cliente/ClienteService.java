package com.pointdofrango.cliente;

import com.pointdofrango.entrega.Bairro;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ClienteService {

    private static final Logger log = LoggerFactory.getLogger(ClienteService.class);

    private final ClienteRepository clientes;
    private final EntregaCadastroService cadastros;
    private final ApplicationEventPublisher eventos;

    public ClienteService(ClienteRepository clientes, EntregaCadastroService cadastros,
                          ApplicationEventPublisher eventos) {
        this.clientes = clientes;
        this.cadastros = cadastros;
        this.eventos = eventos;
    }

    /** Busca exata pelo telefone: o atendente não consegue "listar" a base de clientes. */
    @Transactional(readOnly = true)
    public Optional<Cliente> porTelefone(String telefone) {
        return clientes.findByTelefone(Cliente.normalizarTelefone(telefone));
    }

    /** Cria ou atualiza o cadastro com os dados do pedido que está sendo lançado. */
    @Transactional
    public Cliente registrarDoPedido(String nome, String telefone, String endereco, String referencia, Bairro bairro,
                                     Instant quando) {
        Cliente cliente = clientes.findByTelefone(Cliente.normalizarTelefone(telefone))
                .orElseGet(() -> new Cliente(telefone, quando));
        cliente.atualizar(nome, endereco, referencia, bairro);
        cliente.registrarPedido(quando);
        return clientes.save(cliente);
    }

    @Transactional(readOnly = true)
    public List<Cliente> listar(String termo) {
        if (termo == null || termo.isBlank()) {
            return clientes.findTop200ByOrderByUltimoPedidoEmDesc();
        }
        String t = termo.trim();
        return clientes.buscar(t.length() > 50 ? t.substring(0, 50) : t);
    }

    @Transactional(readOnly = true)
    public Cliente buscar(Long id) {
        return clientes.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", id));
    }

    @Transactional
    public Cliente alterar(Long id, String nome, String endereco, String referencia, Long bairroId) {
        Cliente cliente = buscar(id);
        cliente.atualizar(nome, endereco, referencia, bairroId == null ? null : cadastros.bairro(bairroId));
        return cliente;
    }

    /** Direito de eliminação: apaga o cadastro e tira nome/telefone/endereço dos pedidos antigos. */
    @Transactional
    public void esquecer(Long id, String usuario) {
        Cliente cliente = buscar(id);
        eventos.publishEvent(new ClienteEsquecido(cliente.getId(), cliente.getTelefone()));
        clientes.delete(cliente);
        log.info("Cliente #{} removido a pedido do titular (LGPD) por {}", id, usuario);
    }

    @Transactional
    public int removerSemPedidoDesde(Instant limite) {
        return clientes.removerSemPedidoDesde(limite);
    }
}
