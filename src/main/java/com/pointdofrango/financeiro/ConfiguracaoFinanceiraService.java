package com.pointdofrango.financeiro;

import com.pointdofrango.financeiro.FinanceiroDtos.ConfiguracaoRequest;
import com.pointdofrango.financeiro.FinanceiroDtos.DespesaRequest;
import com.pointdofrango.shared.RecursoNaoEncontradoException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class ConfiguracaoFinanceiraService {

    private final ConfiguracaoFinanceiraRepository configuracoes;
    private final DespesaFixaRepository despesas;
    private final Clock clock;

    public ConfiguracaoFinanceiraService(ConfiguracaoFinanceiraRepository configuracoes,
                                         DespesaFixaRepository despesas, Clock clock) {
        this.configuracoes = configuracoes;
        this.despesas = despesas;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConfiguracaoFinanceira obter() {
        return configuracoes.findById(ConfiguracaoFinanceira.ID_UNICO)
                .orElseThrow(() -> new IllegalStateException("Configuração financeira ausente (migration V1)."));
    }

    /** Vale para os PRÓXIMOS pedidos. Os já lançados mantêm os potes calculados na hora da venda. */
    @Transactional
    public ConfiguracaoFinanceira atualizar(ConfiguracaoRequest req) {
        ConfiguracaoFinanceira config = obter();
        config.atualizar(req.percentualContasFixas(), req.percentualProLabore(), req.taxaPix(), req.taxaDebito(),
                req.taxaCredito(), Instant.now(clock));
        if (req.taxaIfood() != null && req.taxaNoventaNove() != null) {
            config.atualizarTaxasPlataformas(req.taxaIfood(), req.taxaNoventaNove());
        }
        return config;
    }

    @Transactional
    public ConfiguracaoFinanceira definirRegime(RegimeTributario regime, BigDecimal limiteAnual) {
        ConfiguracaoFinanceira config = obter();
        config.definirRegime(regime, limiteAnual, Instant.now(clock));
        return config;
    }

    // ---------- Despesas fixas ----------

    @Transactional(readOnly = true)
    public List<DespesaFixa> listarDespesas() {
        return despesas.findAllByOrderByDiaVencimentoAscDescricaoAsc();
    }

    @Transactional(readOnly = true)
    public BigDecimal totalMensalDespesasAtivas() {
        return despesas.findByAtivaTrue().stream().map(DespesaFixa::getValorMensal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public DespesaFixa criarDespesa(DespesaRequest req) {
        DespesaFixa nova = new DespesaFixa(req.descricao(), req.valorMensal(), req.diaVencimento());
        nova.definirValorVariavel(Boolean.TRUE.equals(req.valorVariavel()));
        return despesas.save(nova);
    }

    @Transactional
    public DespesaFixa atualizarDespesa(Long id, DespesaRequest req) {
        DespesaFixa despesa = despesas.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Despesa", id));
        despesa.atualizar(req.descricao(), req.valorMensal(), req.diaVencimento(),
                req.ativa() == null || req.ativa());
        if (req.valorVariavel() != null) {
            despesa.definirValorVariavel(req.valorVariavel());
        }
        return despesa;
    }

    @Transactional
    public void removerDespesa(Long id) {
        if (!despesas.existsById(id)) {
            throw new RecursoNaoEncontradoException("Despesa", id);
        }
        despesas.deleteById(id);
    }
}
