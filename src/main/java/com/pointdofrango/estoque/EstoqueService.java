package com.pointdofrango.estoque;

import com.pointdofrango.estoque.EstoqueDtos.AjusteRequest;
import com.pointdofrango.estoque.EstoqueDtos.CompraRequest;
import com.pointdofrango.estoque.EstoqueDtos.CompraResponse;
import com.pointdofrango.estoque.EstoqueDtos.ContagemRequest;
import com.pointdofrango.estoque.EstoqueDtos.ItemContado;
import com.pointdofrango.estoque.EstoqueDtos.ItemCompra;
import com.pointdofrango.estoque.EstoqueDtos.EntradaRequest;
import com.pointdofrango.estoque.EstoqueDtos.InsumoRequest;
import com.pointdofrango.estoque.EstoqueDtos.ReposicaoResponse;
import com.pointdofrango.estoque.EstoqueDtos.UltimaEntrada;
import com.pointdofrango.shared.Dinheiro;
import com.pointdofrango.shared.EstoqueInsuficienteException;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EstoqueService {

    private final InsumoRepository insumos;
    private final MovimentacaoEstoqueRepository movimentacoes;
    private final LoteValidadeRepository lotes;
    private final Clock clock;
    private final ApplicationEventPublisher eventos;

    public EstoqueService(InsumoRepository insumos, MovimentacaoEstoqueRepository movimentacoes,
                          LoteValidadeRepository lotes, Clock clock, ApplicationEventPublisher eventos) {
        this.insumos = insumos;
        this.movimentacoes = movimentacoes;
        this.lotes = lotes;
        this.clock = clock;
        this.eventos = eventos;
    }

    @Transactional(readOnly = true)
    public List<Insumo> listar() {
        return insumos.findAllByOrderByNomeAsc();
    }

    @Transactional(readOnly = true)
    public Insumo buscar(Long id) {
        return insumos.findWithEmbalagensById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Insumo", id));
    }

    @Transactional
    public Insumo criar(InsumoRequest req, String usuario) {
        if (insumos.existsByNomeIgnoreCase(req.nome().trim())) {
            throw new RegraDeNegocioException("Já existe um insumo com o nome " + req.nome());
        }
        Insumo novo = new Insumo(req.nome(), req.unidade(), req.estoqueMinimo(), req.custoUnitario());
        novo.definirUnidadeUso(req.unidadeUsoNome(), req.unidadeUsoPorUnidade());
        novo.definirEmbalagens(dadosEmbalagens(req));
        novo.classificar(req.grupo(), req.marca());
        novo.guardarEm(req.local());
        Insumo insumo = insumos.save(novo);
        BigDecimal inicial = req.estoqueInicial();
        if (inicial != null && inicial.signum() > 0) {
            insumo.darEntrada(inicial, inicial.multiply(req.custoUnitario()));
            registrar(insumo, TipoMovimentacao.ENTRADA, inicial, null, "Estoque inicial", usuario);
        }
        return insumo;
    }

    @Transactional
    public Insumo atualizar(Long id, InsumoRequest req) {
        Insumo insumo = buscar(id);
        insumo.atualizarCadastro(req.nome(), req.unidade(), req.estoqueMinimo(), req.custoUnitario());
        insumo.definirUnidadeUso(req.unidadeUsoNome(), req.unidadeUsoPorUnidade());
        insumo.classificar(req.grupo(), req.marca());
        insumo.guardarEm(req.local());
        Set<Long> removidas = insumo.definirEmbalagens(dadosEmbalagens(req));
        // Produtos recalcula as fichas na mesma transação: ficha inválida desfaz a alteração.
        eventos.publishEvent(new InsumoConversaoAlterada(insumo.getId(), removidas));
        return insumo;
    }

    private static List<Insumo.DadosEmbalagem> dadosEmbalagens(InsumoRequest req) {
        return req.embalagensOuVazio().stream()
                .map(e -> new Insumo.DadosEmbalagem(e.id(), e.nome(), e.conteudo()))
                .toList();
    }

    @Transactional
    public void alterarAtivo(Long id, boolean ativo) {
        Insumo insumo = buscar(id);
        if (ativo) {
            insumo.ativar();
        } else {
            insumo.desativar();
        }
    }

    /**
     * Só apaga insumo sem histórico de venda ou consumo; os demais ficam inativos para não perder o extrato.
     * Produtos e notas fiscais que apontam para ele reagem ao evento na mesma transação.
     */
    @Transactional
    public void excluir(Long id) {
        Insumo insumo = buscar(id);
        if (movimentacoes.existsByInsumoIdAndTipoIn(id, List.of(TipoMovimentacao.SAIDA_VENDA,
                TipoMovimentacao.ESTORNO_VENDA, TipoMovimentacao.CONSUMO_INTERNO))) {
            throw new RegraDeNegocioException(insumo.getNome() + " já foi vendido ou consumido e faz parte do histórico. "
                    + "Para tirar da lista, desmarque \"Ativo\".");
        }
        eventos.publishEvent(new InsumoExcluido(id, insumo.getNome()));
        movimentacoes.apagarDoInsumo(id); // grava e limpa o contexto: o insumo é apagado pelo id
        insumos.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<UltimaEntrada> ultimasEntradas(int dias) {
        Instant desde = Instant.now(clock).minus(Duration.ofDays(dias));
        // A mesma compra pode trazer o item em várias linhas (2 × Sassami 6 kg): soma as da mesma origem.
        return movimentacoes.ultimasEntradas(TipoMovimentacao.ENTRADA, desde).stream()
                .map(m -> new UltimaEntrada(m.getInsumo().getId(), m.getCriadoEm(),
                        m.getObservacao() == null ? m.getQuantidade()
                                : movimentacoes.somaEntradas(m.getInsumo().getId(), m.getObservacao(), m.getCriadoEm().minus(Duration.ofMinutes(30))),
                        m.getObservacao()))
                .toList();
    }

    /** Prefixo das movimentações de quando dois cadastros do mesmo item são juntados. */
    public static final String TRANSFERENCIA = "Transferido ";

    /**
     * Junta um cadastro duplicado no outro (ex.: "Sassami Seara" no "Filé de frango"): o saldo e o valor passam
     * para o destino pelo custo médio, a origem fica zerada e inativa, e validades e ligações de nota acompanham.
     */
    @Transactional
    public Insumo juntar(Long origemId, Long destinoId, String usuario) {
        if (origemId.equals(destinoId)) {
            throw new RegraDeNegocioException("Escolha outro item para juntar.");
        }
        Insumo origemLida = buscar(origemId);
        Insumo destinoLido = buscar(destinoId);
        if (origemLida.getUnidade() != destinoLido.getUnidade()) {
            throw new RegraDeNegocioException("Só dá para juntar itens controlados na mesma unidade ("
                    + origemLida.getUnidade().getSigla() + " e " + destinoLido.getUnidade().getSigla() + ").");
        }
        if (insumos.usadoEmFichaTecnica(origemId)) {
            throw new RegraDeNegocioException(origemLida.getNome() + " está em ficha técnica. Troque nas fichas pelo "
                    + destinoLido.getNome() + " antes de juntar.");
        }
        // trava na ordem dos ids, como na venda, para não dar deadlock
        Map<Long, Insumo> travados = insumos.travarPorIds(List.of(origemId, destinoId)).stream()
                .collect(Collectors.toMap(Insumo::getId, Function.identity()));
        Insumo origem = travados.get(origemId);
        Insumo destino = travados.get(destinoId);
        BigDecimal saldo = origem.getEstoqueAtual();
        if (saldo.signum() < 0) {
            throw new RegraDeNegocioException(origem.getNome() + " está com saldo negativo. Faça a contagem antes de juntar.");
        }
        if (saldo.signum() > 0) {
            destino.darEntrada(saldo, saldo.multiply(origem.getCustoUnitario()));
            registrar(destino, TipoMovimentacao.AJUSTE, saldo, null, TRANSFERENCIA + "de " + origem.getNome(), usuario);
            origem.ajustarPara(BigDecimal.ZERO);
            registrar(origem, TipoMovimentacao.AJUSTE, saldo.negate(), null, TRANSFERENCIA + "para " + destino.getNome(), usuario);
        }
        origem.desativar();
        lotes.transferir(origemId, destinoId);
        eventos.publishEvent(new InsumosJuntados(origemId, destinoId));
        return destino;
    }

    /** Prefixo do ajuste que registra o estorno de uma compra importada. */
    public static final String ESTORNO_DE_IMPORTACAO = "Importação desfeita: ";

    /**
     * Entradas da compra que ainda valem. Cada estorno anterior (mesmo insumo, mesma quantidade, depois da entrada)
     * anula uma delas: desfazer a mesma compra duas vezes não estorna em dobro.
     */
    @Transactional(readOnly = true)
    public List<MovimentacaoEstoque> entradasDaCompra(Collection<String> observacoes, Instant desde, Instant ate) {
        List<MovimentacaoEstoque> estornos = new ArrayList<>(movimentacoes.findByTipoAndObservacaoInAndCriadoEmGreaterThanEqual(
                TipoMovimentacao.AJUSTE, observacoes.stream().map(o -> ESTORNO_DE_IMPORTACAO + o).toList(), desde));
        List<MovimentacaoEstoque> valendo = new ArrayList<>();
        movimentacoes.findByTipoAndObservacaoInAndCriadoEmBetween(TipoMovimentacao.ENTRADA, observacoes, desde, ate).stream()
                .sorted(Comparator.comparing(MovimentacaoEstoque::getId))
                .forEach(entrada -> estornos.stream()
                        .filter(a -> a.getInsumo().getId().equals(entrada.getInsumo().getId()) && a.getId() > entrada.getId()
                                && a.getQuantidade().compareTo(entrada.getQuantidade().negate()) == 0)
                        .findFirst()
                        .ifPresentOrElse(estornos::remove, () -> valendo.add(entrada)));
        return valendo;
    }

    /**
     * Desfaz a entrada de uma compra. O valor dela sai da diferença entre o valor em estoque depois e antes
     * (o custo médio só muda em entradas). Retorna o id do ajuste que registra o estorno no extrato.
     */
    @Transactional
    public Long estornarEntrada(Long movimentoId, String motivo, String usuario) {
        MovimentacaoEstoque entrada = movimentacoes.findById(movimentoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Movimentação", movimentoId));
        if (entrada.getTipo() != TipoMovimentacao.ENTRADA) {
            throw new RegraDeNegocioException("Só entradas de compra podem ser desfeitas.");
        }
        Insumo insumo = travar(entrada.getInsumo().getId());
        BigDecimal custoAntes = movimentacoes.findFirstByInsumoIdAndIdLessThanOrderByIdDesc(insumo.getId(), movimentoId)
                .map(MovimentacaoEstoque::getCustoUnitario).orElse(BigDecimal.ZERO);
        BigDecimal saldoAntes = entrada.getSaldoApos().subtract(entrada.getQuantidade()).max(BigDecimal.ZERO);
        BigDecimal valor = entrada.getSaldoApos().multiply(entrada.getCustoUnitario()).subtract(saldoAntes.multiply(custoAntes));
        insumo.estornarEntrada(entrada.getQuantidade(), valor.max(BigDecimal.ZERO));
        return registrar(insumo, TipoMovimentacao.AJUSTE, entrada.getQuantidade().negate(), null, motivo, usuario).getId();
    }

    /** Insumo que só existia por causa da compra desfeita (todas as movimentações são dela) é apagado. */
    @Transactional
    public boolean excluirSeSoDaCompra(Long insumoId, Set<Long> movimentacoesDaCompra) {
        // Usado em ficha técnica fica, zerado: o produto continua apontando para ele.
        if (insumos.usadoEmFichaTecnica(insumoId) || !movimentacoesDaCompra.containsAll(movimentacoes.idsDoInsumo(insumoId))) {
            return false;
        }
        excluir(insumoId);
        return true;
    }

    @Transactional(readOnly = true)
    public List<ReposicaoResponse> reposicao() {
        return movimentacoes.vendasDesdeAUltimaEntrada(
                        List.of(TipoMovimentacao.SAIDA_VENDA, TipoMovimentacao.ESTORNO_VENDA), TipoMovimentacao.ENTRADA)
                .stream()
                .map(l -> new ReposicaoResponse((Long) l[0], ((BigDecimal) l[1]).negate(),
                        Dinheiro.centavos(((BigDecimal) l[2]).negate())))
                .filter(r -> r.vendido().signum() > 0)
                .toList();
    }

    /** Uma entrada por item (custo médio de cada um) e um único lançamento do pagamento pelo total. */
    @Transactional
    public CompraResponse registrarCompra(CompraRequest req, String usuario) {
        String observacao = req.observacao() == null || req.observacao().isBlank() ? "Compra sem nota" : req.observacao().trim();
        BigDecimal total = BigDecimal.ZERO;
        // Mesma ordem de travamento em todas as compras, para não haver deadlock.
        List<ItemCompra> itens = req.itens().stream().sorted(Comparator.comparing(ItemCompra::insumoId)).toList();
        for (ItemCompra item : itens) {
            registrarEntrada(item.insumoId(), new EntradaRequest(item.quantidade(), item.embalagemId(),
                    item.quantidadeEmbalagens(), item.valorTotal(), observacao, null, item.validade()), usuario);
            total = total.add(item.valorTotal());
        }
        if (req.pagamento() != null && total.signum() > 0) {
            eventos.publishEvent(new CompraDeInsumoPaga(observacao + " (" + itens.size() + " itens)", total,
                    req.pagamento(), usuario, req.data()));
        }
        return new CompraResponse(itens.size(), Dinheiro.centavos(total));
    }

    @Transactional
    public Insumo registrarEntrada(Long id, EntradaRequest req, String usuario) {
        Insumo insumo = travar(id);
        BigDecimal quantidade;
        String observacao = req.observacao();
        if (req.embalagemId() != null) {
            if (req.quantidadeEmbalagens() == null) {
                throw new RegraDeNegocioException("Informe quantas embalagens chegaram.");
            }
            EmbalagemCompra embalagem = insumo.embalagem(req.embalagemId());
            quantidade = embalagem.getConteudo().multiply(req.quantidadeEmbalagens());
            String descricao = req.quantidadeEmbalagens().stripTrailingZeros().toPlainString() + "× " + embalagem.getNome();
            observacao = observacao == null || observacao.isBlank() ? descricao : descricao + " · " + observacao;
        } else if (req.quantidade() != null) {
            quantidade = req.quantidade();
        } else {
            throw new RegraDeNegocioException("Informe a quantidade ou a embalagem da compra.");
        }
        insumo.darEntrada(quantidade, req.valorTotal());
        registrar(insumo, TipoMovimentacao.ENTRADA, quantidade, null, observacao, usuario);
        if (req.validade() != null) {
            lotes.save(new LoteValidade(insumo.getId(), quantidade, req.validade(), usuario, Instant.now(clock)));
        }
        if (req.pagamento() != null && req.valorTotal().signum() > 0) {
            String qtd = quantidade.stripTrailingZeros().toPlainString() + " " + insumo.getUnidade().getSigla();
            eventos.publishEvent(new CompraDeInsumoPaga("Compra: " + qtd + " de " + insumo.getNome(), req.valorTotal(),
                    req.pagamento(), usuario));
        }
        return comEmbalagens(insumo);
    }

    /** Contagem de vários itens de uma vez (um freezer, a geladeira): só gera ajuste onde o contado difere. */
    @Transactional
    public int registrarContagem(ContagemRequest req, String usuario) {
        int ajustados = 0;
        for (ItemContado item : req.itens()) {
            Insumo insumo = buscar(item.insumoId());
            if (insumo.getEstoqueAtual().compareTo(item.quantidadeContada()) != 0) {
                registrarAjuste(item.insumoId(), new AjusteRequest(item.quantidadeContada(), req.motivo()), usuario);
                ajustados++;
            }
        }
        return ajustados;
    }

    @Transactional
    public Insumo registrarAjuste(Long id, AjusteRequest req, String usuario) {
        Insumo insumo = travar(id);
        BigDecimal diferenca = insumo.ajustarPara(req.quantidadeContada());
        if (diferenca.signum() != 0) {
            registrar(insumo, TipoMovimentacao.AJUSTE, diferenca, null, req.motivo(), usuario);
        }
        return comEmbalagens(insumo);
    }

    @Transactional(readOnly = true)
    public List<MovimentacaoEstoque> extrato(Long insumoId) {
        buscar(insumoId);
        return movimentacoes.findTop100ByInsumoIdOrderByCriadoEmDescIdDesc(insumoId);
    }

    /**
     * Trava os insumos e confere o saldo de todo o consumo antes de baixar qualquer coisa.
     * Chamar dentro da transação de quem vai baixar.
     *
     * @param consumo quantidade total necessária por insumoId
     */
    @Transactional
    public Map<Long, Insumo> travarEConferir(Map<Long, BigDecimal> consumo) {
        return travarEConferir(consumo, true);
    }

    /** exigirSaldo false: lançamento retroativo, o que foi vendido já saiu do estoque de verdade. */
    public Map<Long, Insumo> travarEConferir(Map<Long, BigDecimal> consumo, boolean exigirSaldo) {
        Map<Long, Insumo> travados = insumos.travarPorIds(consumo.keySet()).stream()
                .collect(Collectors.toMap(Insumo::getId, Function.identity()));

        List<EstoqueInsuficienteException.Falta> faltas = new ArrayList<>();
        consumo.forEach((insumoId, necessario) -> {
            Insumo insumo = travados.get(insumoId);
            if (insumo == null) {
                throw new RecursoNaoEncontradoException("Insumo", insumoId);
            }
            if (exigirSaldo && !insumo.possui(necessario)) {
                faltas.add(new EstoqueInsuficienteException.Falta(insumo.getNome(), necessario,
                        insumo.getEstoqueAtual(), insumo.getUnidade().getSigla()));
            }
        });
        if (!faltas.isEmpty()) {
            throw new EstoqueInsuficienteException(faltas);
        }
        return travados;
    }

    /** Baixa o consumo já conferido por {@link #travarEConferir} e registra no extrato. */
    @Transactional
    public void baixarParaPedido(Map<Long, Insumo> travados, Map<Long, BigDecimal> consumo, Long pedidoId, String usuario) {
        baixarParaPedido(travados, consumo, pedidoId, usuario, false);
    }

    public void baixarParaPedido(Map<Long, Insumo> travados, Map<Long, BigDecimal> consumo, Long pedidoId, String usuario,
                                 boolean permitirNegativo) {
        consumo.forEach((insumoId, quantidade) -> {
            Insumo insumo = travados.get(insumoId);
            insumo.baixar(quantidade, permitirNegativo);
            registrar(insumo, TipoMovimentacao.SAIDA_VENDA, quantidade.negate(), pedidoId, null, usuario);
        });
    }

    /** Estorna pelo extrato, não pela ficha atual, que pode ter mudado desde a venda. */
    @Transactional
    public void estornarPedido(Long pedidoId, String usuario) {
        List<MovimentacaoEstoque> saidas = movimentacoes.findByPedidoIdAndTipo(pedidoId, TipoMovimentacao.SAIDA_VENDA);
        if (saidas.isEmpty()) {
            return;
        }
        List<Long> ids = saidas.stream().map(m -> m.getInsumo().getId()).distinct().toList();
        Map<Long, Insumo> travados = insumos.travarPorIds(ids).stream()
                .collect(Collectors.toMap(Insumo::getId, Function.identity()));
        for (MovimentacaoEstoque saida : saidas) {
            Insumo insumo = travados.get(saida.getInsumo().getId());
            BigDecimal quantidade = saida.getQuantidade().negate();
            insumo.estornar(quantidade);
            registrar(insumo, TipoMovimentacao.ESTORNO_VENDA, quantidade, pedidoId, "Cancelamento do pedido", usuario);
        }
    }

    /** Consumo da equipe: sai do estoque sem pedido e sem receita. */
    @Transactional
    public List<MovimentacaoEstoque> baixarConsumoInterno(Map<Long, Insumo> travados, Map<Long, BigDecimal> consumo,
                                                          String motivo, String usuario, boolean permitirNegativo) {
        List<MovimentacaoEstoque> registradas = new ArrayList<>();
        consumo.forEach((insumoId, quantidade) -> {
            Insumo insumo = travados.get(insumoId);
            insumo.baixar(quantidade, permitirNegativo);
            registradas.add(registrar(insumo, TipoMovimentacao.CONSUMO_INTERNO, quantidade.negate(), null, motivo, usuario));
        });
        return registradas;
    }

    @Transactional(readOnly = true)
    public List<MovimentacaoEstoque> consumoInternoEntre(Instant inicio, Instant fim) {
        return movimentacoes.doTipoNoPeriodo(TipoMovimentacao.CONSUMO_INTERNO, inicio, fim);
    }

    /** Inicializa as embalagens dentro da transação; a resposta da API usa. */
    private static Insumo comEmbalagens(Insumo insumo) {
        insumo.getEmbalagens();
        return insumo;
    }

    private Insumo travar(Long id) {
        return insumos.travarPorId(id).orElseThrow(() -> new RecursoNaoEncontradoException("Insumo", id));
    }

    private MovimentacaoEstoque registrar(Insumo insumo, TipoMovimentacao tipo, BigDecimal quantidade, Long pedidoId,
                                          String observacao, String usuario) {
        return movimentacoes.save(new MovimentacaoEstoque(insumo, tipo, quantidade, pedidoId, observacao, usuario,
                Instant.now(clock)));
    }
}
