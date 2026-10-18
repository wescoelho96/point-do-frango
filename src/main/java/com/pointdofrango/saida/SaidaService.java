package com.pointdofrango.saida;

import com.pointdofrango.caixa.CaixaService;
import com.pointdofrango.entrega.Colaborador;
import com.pointdofrango.entrega.EntregaCadastroService;
import com.pointdofrango.estoque.CompraDeInsumoPaga;
import com.pointdofrango.financeiro.CategoriaSaida;
import com.pointdofrango.financeiro.DespesaFixa;
import com.pointdofrango.financeiro.DespesaFixaRepository;
import com.pointdofrango.financeiro.FormaPagamento;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SaidaService {

    private static final Logger log = LoggerFactory.getLogger(SaidaService.class);

    private final SaidaRepository saidas;
    private final FornecedorRepository fornecedores;
    private final DespesaFixaRepository despesasFixas;
    private final EntregaCadastroService cadastros;
    private final CaixaService caixa;
    private final Clock clock;
    private final ZoneId zona;

    public SaidaService(SaidaRepository saidas, FornecedorRepository fornecedores, DespesaFixaRepository despesasFixas,
                        EntregaCadastroService cadastros, CaixaService caixa, Clock clock, ZoneId zona) {
        this.saidas = saidas;
        this.fornecedores = fornecedores;
        this.despesasFixas = despesasFixas;
        this.cadastros = cadastros;
        this.caixa = caixa;
        this.clock = clock;
        this.zona = zona;
    }

    public record NovaSaida(LocalDate data, CategoriaSaida categoria, String descricao, BigDecimal valor,
                            FormaPagamento forma, Long fornecedorId, Long colaboradorId, Long despesaFixaId) {
    }

    public static final int MAX_PARCELAS = 12;

    /**
     * Crédito: entra no mês seguinte à compra, quando a fatura é paga; parcelado, uma saída por mês.
     * Centavos que sobram da divisão ficam na 1ª parcela. Outras formas: uma saída na data da compra.
     */
    @Transactional
    public List<Saida> lancarParcelado(NovaSaida n, Integer parcelas, String usuario) {
        int vezes = parcelas == null ? 1 : parcelas;
        if (n.forma() != FormaPagamento.CREDITO) {
            if (vezes > 1) {
                throw new RegraDeNegocioException("Só o pagamento no crédito pode ser parcelado.");
            }
            return List.of(lancar(n, usuario));
        }
        if (vezes < 1 || vezes > MAX_PARCELAS) {
            throw new RegraDeNegocioException("Parcelamento em até " + MAX_PARCELAS + " vezes.");
        }
        LocalDate compra = n.data() != null ? n.data() : LocalDate.now(clock.withZone(zona));
        if (vezes == 1) {
            return List.of(lancar(new NovaSaida(compra.plusMonths(1), n.categoria(), n.descricao(), n.valor(), n.forma(),
                    n.fornecedorId(), n.colaboradorId(), n.despesaFixaId()), usuario));
        }
        BigDecimal parcela = n.valor().divide(BigDecimal.valueOf(vezes), 2, RoundingMode.DOWN);
        BigDecimal primeira = n.valor().subtract(parcela.multiply(BigDecimal.valueOf(vezes - 1)));
        String grupo = UUID.randomUUID().toString();
        Fornecedor fornecedor = fornecedorOuNulo(n.fornecedorId());
        Colaborador colaborador = n.colaboradorId() == null ? null : cadastros.colaborador(n.colaboradorId());
        List<Saida> lancadas = new ArrayList<>();
        for (int i = 1; i <= vezes; i++) {
            String sufixo = " (" + i + "/" + vezes + ")";
            String descricao = n.descricao().strip();
            if (descricao.length() + sufixo.length() > 150) {
                descricao = descricao.substring(0, 150 - sufixo.length());
            }
            Saida saida = new Saida(compra.plusMonths(i), n.categoria(), descricao + sufixo, i == 1 ? primeira : parcela,
                    n.forma(), usuario, Instant.now(clock));
            saida.vincular(fornecedor, colaborador, n.despesaFixaId());
            saida.parcelar(i, vezes, grupo);
            lancadas.add(saidas.save(saida));
        }
        log.info("Compra parcelada em {}x lançada por {}: {} ({})", vezes, usuario, n.descricao(), n.valor());
        return lancadas;
    }

    @Transactional
    public Saida lancar(NovaSaida n, String usuario) {
        LocalDate hoje = LocalDate.now(clock.withZone(zona));
        LocalDate data = n.data() != null ? n.data() : hoje;
        if (data.isAfter(hoje.plusDays(60)) || data.isBefore(hoje.minusYears(2))) {
            throw new RegraDeNegocioException("Data fora do intervalo permitido (até 2 anos atrás).");
        }
        Saida saida = new Saida(data, n.categoria(), n.descricao(), n.valor(), n.forma(), usuario, Instant.now(clock));
        Colaborador colaborador = n.colaboradorId() == null ? null : cadastros.colaborador(n.colaboradorId());
        saida.vincular(fornecedorOuNulo(n.fornecedorId()), colaborador, n.despesaFixaId());
        return saidas.save(saida);
    }

    /** Sem valor informado, usa o do código de barras ou, na falta dele, o valor cadastrado. */
    @Transactional
    public Saida pagarContaFixa(Long despesaFixaId, BigDecimal valor, FormaPagamento forma, LocalDate data,
                                String codigoBarras, String usuario) {
        DespesaFixa conta = despesasFixas.findById(despesaFixaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Conta fixa", despesaFixaId));
        CodigoDeBarras.Leitura leitura = codigoBarras == null || codigoBarras.isBlank() ? null
                : CodigoDeBarras.ler(codigoBarras);
        BigDecimal pago = valor != null ? valor
                : leitura != null && leitura.valor() != null ? leitura.valor() : conta.getValorMensal();
        Saida saida = lancar(new NovaSaida(data, CategoriaSaida.CONTA_FIXA, conta.getDescricao(), pago, forma, null,
                null, conta.getId()), usuario);
        if (leitura != null) {
            saida.guardarCodigoBarras(leitura.codigoBarras());
        }
        conta.registrarPagamento(saida.getValor());
        return saida;
    }

    public Saida pagarContaFixa(Long despesaFixaId, BigDecimal valor, FormaPagamento forma, LocalDate data,
                                String usuario) {
        return pagarContaFixa(despesaFixaId, valor, forma, data, null, usuario);
    }

    /** Pagamento lançado pela importação de uma compra (à vista ou parcelado), para desfazer junto com ela. */
    @Transactional
    public int removerDaCompra(Collection<String> descricoes, Instant desde, Instant ate, String usuario) {
        List<Saida> daCompra = saidas.findByCriadoEmBetween(desde, ate).stream()
                .filter(s -> descricoes.stream().anyMatch(d -> s.getDescricao().equals(d) || s.getDescricao().startsWith(d + " (")))
                .toList();
        saidas.deleteAll(daCompra);
        log.info("{} saída(s) da compra desfeita removida(s) por {}", daCompra.size(), usuario);
        return daCompra.size();
    }

    @Transactional
    public void remover(Long id, String usuario) {
        Saida s = saidas.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Saída", id));
        if (s.getGrupoParcelas() != null) {
            // Parcela é parte de uma compra: remover desfaz a compra inteira.
            saidas.deleteAll(saidas.findByGrupoParcelas(s.getGrupoParcelas()));
            log.info("Compra parcelada de #{} ({}x) removida por {}", id, s.getParcelas(), usuario);
            return;
        }
        saidas.delete(s);
        log.info("Saída #{} ({}, {}) removida por {}", id, s.getDescricao(), s.getValor(), usuario);
    }

    /** Compra de insumo já paga vira saída do caixa ou saída por fora. */
    @EventListener
    @Transactional
    public void aoComprarInsumo(CompraDeInsumoPaga compra) {
        var pg = compra.pagamento();
        if (pg.doCaixa()) {
            if (pg.forma() != FormaPagamento.DINHEIRO) {
                throw new RegraDeNegocioException("Pago com dinheiro do caixa precisa ser em dinheiro.");
            }
            fornecedorOuNulo(pg.fornecedorId());
            caixa.registrarSaida(CategoriaSaida.INSUMOS, compra.descricao(), compra.valor(), FormaPagamento.DINHEIRO,
                    null, pg.fornecedorId(), compra.usuario());
        } else {
            lancarParcelado(new NovaSaida(compra.data(), CategoriaSaida.INSUMOS, compra.descricao(), compra.valor(), pg.forma(),
                    pg.fornecedorId(), null, null), pg.parcelas(), compra.usuario());
        }
    }

    // ---------- Fornecedores ----------

    @Transactional(readOnly = true)
    public List<Fornecedor> fornecedores() {
        return fornecedores.findAllByOrderByAtivoDescNomeAsc();
    }

    @Transactional
    public Fornecedor salvarFornecedor(Long id, String nome, String fornece, String telefone, String documento,
                                       String observacao, boolean ativo) {
        boolean repetido = id == null ? fornecedores.existsByNomeIgnoreCase(nome.strip())
                : fornecedores.existsByNomeIgnoreCaseAndIdNot(nome.strip(), id);
        if (repetido) {
            throw new RegraDeNegocioException("Já existe um fornecedor chamado " + nome.strip() + ".");
        }
        Fornecedor f = id == null ? new Fornecedor(nome)
                : fornecedores.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Fornecedor", id));
        f.alterar(nome, fornece, telefone, documento, observacao, ativo);
        return fornecedores.save(f);
    }

    /** Busca pelo CNPJ da nota; se não existir, cadastra com o nome da nota. */
    @Transactional
    public Fornecedor fornecedorDaNota(String cnpj, String nome) {
        return fornecedorPorCnpj(cnpj).orElseGet(() -> {
            String nomeLivre = fornecedores.existsByNomeIgnoreCase(nome.strip()) ? nome.strip() + " (" + cnpj + ")" : nome;
            Fornecedor novo = new Fornecedor(nomeLivre);
            novo.alterar(nomeLivre, "compras com nota fiscal", null, cnpj, null, true);
            return fornecedores.save(novo);
        });
    }

    @Transactional(readOnly = true)
    public Optional<Fornecedor> fornecedorPorCnpj(String cnpj) {
        return fornecedores.findAll().stream()
                .filter(f -> f.getDocumento() != null && f.getDocumento().replaceAll("\\D", "").equals(cnpj))
                .findFirst();
    }

    private Fornecedor fornecedorOuNulo(Long id) {
        return id == null ? null
                : fornecedores.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Fornecedor", id));
    }
}
