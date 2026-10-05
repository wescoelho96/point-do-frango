package com.pointdofrango.cliente;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/clientes")
@Tag(name = "Clientes", description = "Clientes de entrega. Busca por telefone no PDV; listagem e exclusão só para o dono")
public class ClienteController {

    public record ClienteRequest(@NotBlank @Size(max = 100) String nome, @Size(max = 200) String endereco,
                                 @Size(max = 150) String referencia, Long bairroId) {
    }

    public record ClienteResponse(Long id, String nome, String telefone, String endereco, String referencia,
                                  Long bairroId, String bairro, Instant criadoEm, Instant ultimoPedidoEm) {
        static ClienteResponse de(Cliente c) {
            var b = c.getBairro();
            return new ClienteResponse(c.getId(), c.getNome(), c.getTelefone(), c.getEndereco(), c.getReferencia(),
                    b != null ? b.getId() : null, b != null ? b.getNome() : null, c.getCriadoEm(), c.getUltimoPedidoEm());
        }
    }

    private final ClienteService service;

    public ClienteController(ClienteService service) {
        this.service = service;
    }

    @GetMapping("/busca")
    @Operation(summary = "Cliente pelo telefone exato (204 se não tiver cadastro)")
    public ResponseEntity<ClienteResponse> porTelefone(@RequestParam String telefone) {
        return service.porTelefone(telefone).map(ClienteResponse::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    public List<ClienteResponse> listar(@RequestParam(required = false) String termo) {
        return service.listar(termo).stream().map(ClienteResponse::de).toList();
    }

    @PutMapping("/{id}")
    public ClienteResponse alterar(@PathVariable Long id, @Valid @RequestBody ClienteRequest req) {
        return ClienteResponse.de(service.alterar(id, req.nome(), req.endereco(), req.referencia(), req.bairroId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga o cliente e anonimiza os pedidos dele (pedido do titular, LGPD art. 18)")
    public void esquecer(@PathVariable Long id, Authentication auth) {
        service.esquecer(id, auth.getName());
    }
}
