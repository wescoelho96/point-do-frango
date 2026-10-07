package com.pointdofrango.produto;

import com.pointdofrango.estoque.EmbalagemCompra;
import com.pointdofrango.estoque.Insumo;
import com.pointdofrango.estoque.InsumoConversaoAlterada;
import com.pointdofrango.estoque.InsumoExcluido;
import com.pointdofrango.estoque.InsumoRepository;
import com.pointdofrango.estoque.ModoQuantidade;
import com.pointdofrango.produto.ProdutoDtos.ItemFichaRequest;
import com.pointdofrango.produto.ProdutoDtos.ProdutoRequest;
import com.pointdofrango.produto.ProdutoDtos.RendimentoInsumo;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProdutoService {

    private final ProdutoRepository produtos;
    private final ItemFichaTecnicaRepository fichas;
    private final InsumoRepository insumos;

    public ProdutoService(ProdutoRepository produtos, ItemFichaTecnicaRepository fichas, InsumoRepository insumos) {
        this.produtos = produtos;
        this.fichas = fichas;
        this.insumos = insumos;
    }

    @Transactional(readOnly = true)
    public List<Produto> listar(boolean apenasAtivos) {
        return produtos.findAllByOrderByCategoriaAscNomeAsc().stream()
                .filter(p -> !apenasAtivos || p.isAtivo())
                .toList();
    }

    @Transactional(readOnly = true)
    public Produto buscar(Long id) {
        return produtos.findWithFichaById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Produto", id));
    }

    @Transactional
    public Produto criar(ProdutoRequest req) {
        if (produtos.existsByNomeIgnoreCase(req.nome().trim())) {
            throw new RegraDeNegocioException("Já existe um produto com o nome " + req.nome());
        }
        Produto produto = new Produto(req.nome(), req.descricao(), req.categoria(), req.precoVenda());
        produto.definirPreparo(req.preparoNaCozinha());
        produto.definirFichaTecnica(montarComponentes(req.fichaTecnica()));
        return produtos.save(produto);
    }

    @Transactional
    public Produto atualizar(Long id, ProdutoRequest req) {
        if (produtos.existsByNomeIgnoreCaseAndIdNot(req.nome().trim(), id)) {
            throw new RegraDeNegocioException("Já existe um produto com o nome " + req.nome());
        }
        Produto produto = buscar(id);
        produto.atualizarCadastro(req.nome(), req.descricao(), req.categoria(), req.precoVenda());
        produto.definirPreparo(req.preparoNaCozinha());
        produto.definirFichaTecnica(montarComponentes(req.fichaTecnica()));
        return produto;
    }

    @Transactional
    public Produto alterarPreco(Long id, BigDecimal precoVenda) {
        Produto produto = buscar(id);
        produto.atualizarCadastro(produto.getNome(), produto.getDescricao(), produto.getCategoria(), precoVenda);
        return produto;
    }

    @Transactional
    public void alterarAtivo(Long id, boolean ativo) {
        Produto produto = buscar(id);
        if (ativo) {
            produto.ativar();
        } else {
            produto.desativar();
        }
    }

    @Transactional(readOnly = true)
    public RendimentoInsumo rendimento(Long insumoId) {
        Insumo insumo = insumos.findWithEmbalagensById(insumoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Insumo", insumoId));
        return RendimentoInsumo.de(insumo, fichas.findByInsumoIdOrderByProdutoNomeAsc(insumoId));
    }

    /**
     * Roda na transação de quem publicou: se uma ficha ficar inválida (ex.: removeram a
     * embalagem usada no rendimento), a alteração do insumo é desfeita.
     */
    /**
     * Produto feito só com este insumo (bebida, porção de um ingrediente) que nunca foi vendido sai junto com ele.
     * Prato que usa o insumo junto com outros, ou produto com histórico, bloqueia a exclusão.
     */
    @EventListener
    @Transactional
    public void aoExcluirInsumo(InsumoExcluido evento) {
        List<Produto> usam = fichas.findByInsumoIdOrderByProdutoNomeAsc(evento.insumoId()).stream()
                .map(ItemFichaTecnica::getProduto).distinct().toList();
        List<String> bloqueiam = usam.stream()
                .filter(p -> p.getFichaTecnica().size() > 1 || produtos.temHistorico(p.getId()))
                .map(Produto::getNome).toList();
        if (!bloqueiam.isEmpty()) {
            throw new RegraDeNegocioException(evento.nome() + " é usado em " + String.join(", ", bloqueiam)
                    + ". Para tirar da lista, desmarque \"Ativo\".");
        }
        produtos.deleteAll(usam);
    }

    @EventListener
    @Transactional
    public void aoAlterarInsumo(InsumoConversaoAlterada evento) {
        for (ItemFichaTecnica item : fichas.findByInsumoIdOrderByProdutoNomeAsc(evento.insumoId())) {
            EmbalagemCompra emb = item.getEmbalagem();
            if (emb != null && evento.embalagensRemovidas().contains(emb.getId())) {
                throw new RegraDeNegocioException("A embalagem \"%s\" é usada na ficha técnica de %s. Altere a ficha antes de removê-la."
                        .formatted(emb.getNome(), item.getProduto().getNome()));
            }
            try {
                item.recalcular();
            } catch (RegraDeNegocioException e) {
                throw new RegraDeNegocioException("Ficha técnica de " + item.getProduto().getNome() + ": " + e.getMessage());
            }
        }
    }

    /** Sem a ficha; falha se algum id não existir. */
    @Transactional(readOnly = true)
    public Map<Long, Produto> buscarVarios(Collection<Long> ids) {
        Map<Long, Produto> encontrados = produtos.findAllById(ids).stream()
                .collect(Collectors.toMap(Produto::getId, Function.identity()));
        ids.stream().filter(id -> !encontrados.containsKey(id)).findFirst().ifPresent(id -> {
            throw new RecursoNaoEncontradoException("Produto", id);
        });
        return encontrados;
    }

    @Transactional(readOnly = true)
    public List<ConsumoFicha> consumoDosProdutos(Collection<Long> produtoIds) {
        return produtos.consumoDosProdutos(produtoIds);
    }

    private List<Produto.Componente> montarComponentes(List<ItemFichaRequest> itens) {
        List<Long> ids = itens.stream().map(ItemFichaRequest::insumoId).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            throw new RegraDeNegocioException("O mesmo insumo aparece duas vezes na ficha técnica.");
        }
        Map<Long, Insumo> porId = insumos.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Insumo::getId, Function.identity()));
        return itens.stream().map(item -> {
            Insumo insumo = porId.get(item.insumoId());
            if (insumo == null) {
                throw new RecursoNaoEncontradoException("Insumo", item.insumoId());
            }
            ModoQuantidade modo = item.modo() != null ? item.modo() : ModoQuantidade.UNIDADE_BASE;
            EmbalagemCompra embalagem = item.embalagemId() != null ? insumo.embalagem(item.embalagemId()) : null;
            return new Produto.Componente(insumo, modo, item.quantidade(), embalagem);
        }).toList();
    }
}
