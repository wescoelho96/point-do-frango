package com.pointdofrango.produto;

import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PromocaoService {

    public record Brinde(String promocao, Long produtoId, int quantidade) {
    }

    private final PromocaoRepository promocoes;
    private final ProdutoRepository produtos;

    public PromocaoService(PromocaoRepository promocoes, ProdutoRepository produtos) {
        this.promocoes = promocoes;
        this.produtos = produtos;
    }

    @Transactional(readOnly = true)
    public List<Promocao> listar() {
        return promocoes.findAllByOrderByAtivaDescNomeAsc();
    }

    @Transactional
    public Promocao salvar(Long id, String nome, Long produtoCompraId, int quantidadeCompra, Long produtoBrindeId,
                           int quantidadeBrinde, Set<DayOfWeek> dias, boolean ativa) {
        if (produtoCompraId.equals(produtoBrindeId)) {
            throw new RegraDeNegocioException("O brinde precisa ser um produto diferente do comprado.");
        }
        Promocao p = id == null ? new Promocao(nome)
                : promocoes.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Promoção", id));
        p.alterar(nome, produto(produtoCompraId), quantidadeCompra, produto(produtoBrindeId), quantidadeBrinde, dias, ativa);
        return promocoes.save(p);
    }

    @Transactional
    public void remover(Long id) {
        promocoes.deleteById(id);
    }

    /** Brindes do dia; quantidades = quantidade comprada por produtoId. */
    @Transactional(readOnly = true)
    public List<Brinde> brindesPara(Map<Long, Integer> quantidades, DayOfWeek dia) {
        List<Brinde> brindes = new ArrayList<>();
        for (Promocao p : promocoes.findAllByOrderByAtivaDescNomeAsc()) {
            Integer comprado = quantidades.get(p.getProdutoCompra().getId());
            if (comprado != null && p.valeEm(dia) && p.brindesPara(comprado) > 0) {
                brindes.add(new Brinde(p.getNome(), p.getProdutoBrinde().getId(), p.brindesPara(comprado)));
            }
        }
        return brindes;
    }

    private Produto produto(Long id) {
        return produtos.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Produto", id));
    }
}
