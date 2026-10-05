package com.pointdofrango.entrega;

import com.pointdofrango.entrega.Colaborador.Funcao;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class EntregaCadastroService {

    private final BairroRepository bairros;
    private final ColaboradorRepository colaboradores;

    public EntregaCadastroService(BairroRepository bairros, ColaboradorRepository colaboradores) {
        this.bairros = bairros;
        this.colaboradores = colaboradores;
    }

    @Transactional(readOnly = true)
    public List<Bairro> listarBairros(boolean apenasAtivos) {
        return bairros.findAllByOrderByNomeAsc().stream().filter(b -> !apenasAtivos || b.isAtivo()).toList();
    }

    @Transactional(readOnly = true)
    public Bairro bairro(Long id) {
        return bairros.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Bairro", id));
    }

    @Transactional
    public Bairro criarBairro(String nome, BigDecimal taxa) {
        if (bairros.existsByNomeIgnoreCase(nome.trim())) {
            throw new RegraDeNegocioException("O bairro " + nome.trim() + " já está cadastrado.");
        }
        return bairros.save(new Bairro(nome, taxa));
    }

    @Transactional
    public Bairro alterarBairro(Long id, String nome, BigDecimal taxa, boolean ativo) {
        if (bairros.existsByNomeIgnoreCaseAndIdNot(nome.trim(), id)) {
            throw new RegraDeNegocioException("O bairro " + nome.trim() + " já está cadastrado.");
        }
        Bairro b = bairro(id);
        b.alterar(nome, taxa, ativo);
        return b;
    }

    @Transactional(readOnly = true)
    public List<Colaborador> listarColaboradores() {
        return colaboradores.findAllByOrderByAtivoDescNomeAsc();
    }

    @Transactional(readOnly = true)
    public Colaborador colaborador(Long id) {
        return colaboradores.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Colaborador", id));
    }

    @Transactional(readOnly = true)
    public Colaborador motoboyAtivo(Long id) {
        Colaborador c = colaborador(id);
        if (!c.isAtivo() || !c.motoboy()) {
            throw new RegraDeNegocioException(c.getNome() + " não é um motoboy ativo.");
        }
        return c;
    }

    @Transactional
    public Colaborador criarColaborador(String nome, Funcao funcao, BigDecimal diaria) {
        return colaboradores.save(new Colaborador(nome, funcao, diaria));
    }

    @Transactional
    public Colaborador alterarColaborador(Long id, String nome, Funcao funcao, BigDecimal diaria, boolean ativo) {
        Colaborador c = colaborador(id);
        c.alterar(nome, funcao, diaria, ativo);
        return c;
    }
}
