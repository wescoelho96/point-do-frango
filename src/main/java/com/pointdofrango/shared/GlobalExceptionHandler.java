package com.pointdofrango.shared;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exceções viram Problem Details (RFC 7807). Stack trace nunca vai para o cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(RecursoNaoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, "Não encontrado", e.getMessage());
    }

    @ExceptionHandler(EstoqueInsuficienteException.class)
    ProblemDetail estoqueInsuficiente(EstoqueInsuficienteException e) {
        ProblemDetail p = problema(HttpStatus.UNPROCESSABLE_ENTITY, "Estoque insuficiente", e.getMessage());
        p.setProperty("faltas", e.getFaltas());
        return p;
    }

    @ExceptionHandler(RegraDeNegocioException.class)
    ProblemDetail regraDeNegocio(RegraDeNegocioException e) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Operação não permitida", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacao(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(erro -> campos.putIfAbsent(erro.getField(), erro.getDefaultMessage()));
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Dados inválidos", "Verifique os campos informados.");
        p.setProperty("campos", campos);
        return p;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail requisicaoMalFormada(Exception e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", "Formato de dados inválido.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integridade(DataIntegrityViolationException e) {
        log.warn("Violação de integridade: {}", e.getMostSpecificCause().getMessage());
        return problema(HttpStatus.CONFLICT, "Conflito",
                "Registro duplicado ou em uso por outro cadastro (ex.: nome já existente).");
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    ProblemDetail concorrencia(Exception e) {
        return problema(HttpStatus.CONFLICT, "Conflito", "O registro foi alterado ao mesmo tempo por outra pessoa. Tente de novo.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail semPermissao(AccessDeniedException e) {
        return problema(HttpStatus.FORBIDDEN, "Sem permissão", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail rotaInexistente(NoResourceFoundException e) {
        return problema(HttpStatus.NOT_FOUND, "Não encontrado", "Rota inexistente.");
    }

    /** Navegador fechou a conexão SSE: não é erro e não há para quem responder. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clienteDesconectou() {
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail erroInesperado(Exception e, HttpServletRequest req) {
        // SSE caído (tela apagada, app fechado): não dá para responder JSON em text/event-stream
        // e o navegador reconecta sozinho.
        if (req.getRequestURI().startsWith("/api/v1/eventos") || e instanceof IOException) {
            log.debug("Conexão encerrada pelo cliente: {}", e.getMessage());
            return null;
        }
        log.error("Erro inesperado", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Erro inesperado. Tente novamente.");
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalhe);
        p.setTitle(titulo);
        return p;
    }
}
