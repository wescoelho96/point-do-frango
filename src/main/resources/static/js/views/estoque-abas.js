// Abas da tela de estoque além da lista de itens: visão geral (painel), compras e inventário.
// Funções puras de desenho: recebem os dados já carregados por estoque.js e devolvem HTML.
import { html, moeda, numero, pct } from '../ui.js';
import { barras, composicao, indicador, STATUS } from '../graficos.js';

export const ABAS = [['painel', '📊 Visão geral'], ['itens', '📦 Itens'], ['compras', '🛒 Compras'], ['inventario', '📋 Inventário']];

export const abasEstoque = (atual) => html`
  <div class="segmented" style="margin-bottom:16px">
    ${ABAS.map(([aba, rotulo]) => html`<a class="${aba === atual ? 'on' : ''}" href="#estoque?aba=${aba}">${rotulo}</a>`)}
  </div>`;

export const LOCAIS = ['Freezer', 'Geladeira', 'Estoque seco', 'Bar', 'Cozinha'];

/** Selo do status com ícone e nome (a cor nunca vem sozinha). */
export function seloStatus(status) {
  if (!status) return '';
  const s = STATUS[status];
  return html`<span class="status-chip" style="color:${status === 'NORMAL' ? 'var(--good-text)' : s.cor}">${s.icone} ${s.rotulo}</span>`;
}

const dias = (d) => `${numero(d, 1)} ${Number(d) === 1 ? 'dia' : 'dias'}`;

// ---------- Visão geral ----------
export function painel({ insumos, indicadores, lotes, margens, furo, totalRepor }) {
  const ativos = insumos.filter((i) => i.ativo);
  const status = (i) => indicadores.get(i.id)?.status;
  const contar = (s) => ativos.filter((i) => status(i) === s).length;
  const valorEstoque = ativos.reduce((s, i) => s + Number(i.valorEmEstoque ?? 0), 0);
  const vendidos = margens.filter((m) => !m.brinde);
  const faturamento = vendidos.reduce((s, m) => s + Number(m.faturamento), 0);
  const lucro = margens.reduce((s, m) => s + Number(m.lucro), 0);
  const vencendo = lotes.filter((l) => l.diasParaVencer <= 7);
  const perdas = furo.filter((f) => Number(f.valorPerdas) > 0);
  const totalPerdas = perdas.reduce((s, f) => s + Number(f.valorPerdas), 0);

  const porGrupo = Object.entries(ativos.reduce((acc, i) => {
    const g = i.grupo || 'Sem grupo';
    acc[g] = (acc[g] ?? 0) + Number(i.valorEmEstoque ?? 0);
    return acc;
  }, {})).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]);

  const acabando = ativos.map((i) => ({ i, ind: indicadores.get(i.id) }))
    .filter(({ ind }) => ind?.coberturaDias != null)
    .sort((a, b) => Number(a.ind.coberturaDias) - Number(b.ind.coberturaDias)).slice(0, 8);

  return html`
    <div class="stat-row">
      ${indicador({ rotulo: 'Valor em estoque', valor: moeda(valorEstoque), sub: `${ativos.length} itens ativos` })}
      ${indicador({ rotulo: 'Itens críticos', valor: contar('CRITICO'), sub: `${contar('ALERTA')} em alerta`, status: contar('CRITICO') ? 'CRITICO' : null })}
      ${indicador({ rotulo: 'Margem média (30 dias)', valor: faturamento ? pct((lucro / faturamento) * 100) : '—',
        sub: faturamento ? `lucro de ${moeda(lucro)} em ${moeda(faturamento)} vendidos` : 'sem vendas no período' })}
      ${indicador({ rotulo: 'Vencendo em 7 dias', valor: vencendo.length, status: vencendo.length ? 'ALERTA' : null,
        sub: vencendo.length ? `${moeda(vencendo.reduce((s, l) => s + Number(l.valorRestante), 0))} em mercadoria` : 'nada perto de vencer' })}
      ${indicador({ rotulo: 'Separar para repor', valor: moeda(totalRepor), sub: 'custo do que foi vendido desde a última compra' })}
      ${indicador({ rotulo: 'Perdas no inventário (30 dias)', valor: moeda(totalPerdas),
        sub: perdas.length ? `${perdas.length} item(ns) com falta na contagem` : 'nenhuma falta nas contagens', status: totalPerdas ? 'ALERTA' : null })}
    </div>
    <div class="painel-grade">
      <div class="stack">
        ${composicao({
          titulo: 'Situação do estoque',
          subtitulo: 'Crítico: zerado, abaixo do mínimo ou acaba em menos de 2 dias. Alerta: perto do mínimo ou menos de 5 dias.',
          partes: ['CRITICO', 'ALERTA', 'NORMAL'].map((s) => ({ status: s, valor: contar(s) })),
        })}
        ${['CRITICO', 'ALERTA'].some((s) => contar(s)) ? html`<div class="card">
          ${['CRITICO', 'ALERTA'].filter((s) => contar(s)).map((s) => html`
            <p style="margin:0 0 6px">${seloStatus(s)}
              <span class="text-2" style="font-size:.88rem">${ativos.filter((i) => status(i) === s).map((i) => i.nome).join(', ')}</span></p>`)}
          <a class="btn btn-sm" href="#estoque?aba=compras">Ver sugestão de compra</a>
        </div>` : ''}
      </div>
      ${barras({
        titulo: 'Acaba primeiro',
        subtitulo: 'Para quantos dias o saldo dá, pelo consumo médio dos últimos 30 dias',
        vazio: 'Ainda sem vendas para calcular o consumo.',
        itens: acabando.map(({ i, ind }) => ({
          rotulo: i.nome, valor: Number(ind.coberturaDias), texto: dias(ind.coberturaDias), status: ind.status,
          tip: `${i.nome}: ${numero(i.estoqueAtual)} ${i.sigla} em estoque, sai ${numero(ind.consumoDiario, 2)} ${i.sigla} por dia`,
        })),
      })}
      ${barras({
        titulo: 'Lucro por produto (30 dias)',
        subtitulo: 'Lucro real das vendas (preço − custo de tudo que vai no produto) e a margem',
        itens: vendidos.slice(0, 8).map((m) => ({
          rotulo: m.produto, valor: Number(m.lucro), texto: `${moeda(m.lucro)} · ${pct(m.margemPercentual)}`,
          tip: `${m.produto}: ${m.quantidade} vendidos, faturou ${moeda(m.faturamento)}, custo ${moeda(m.custo)}, lucro ${moeda(m.lucro)}`,
        })),
      })}
      ${barras({
        titulo: 'Menores margens (30 dias)',
        subtitulo: 'Produtos vendidos com a menor margem: candidatos a revisar preço ou ficha técnica',
        itens: [...vendidos].filter((m) => m.margemPercentual != null)
          .sort((a, b) => Number(a.margemPercentual) - Number(b.margemPercentual)).slice(0, 6).map((m) => ({
            rotulo: m.produto, valor: Number(m.margemPercentual), texto: pct(m.margemPercentual),
            status: Number(m.margemPercentual) < 40 ? 'CRITICO' : Number(m.margemPercentual) < 55 ? 'ALERTA' : null,
            tip: `${m.produto}: lucro de ${moeda(Number(m.lucro) / Math.max(1, m.quantidade))} por unidade`,
          })),
      })}
      ${barras({
        titulo: 'Valor parado por grupo',
        subtitulo: 'Dinheiro em mercadoria, pelo custo médio',
        itens: porGrupo.map(([g, v]) => ({ rotulo: g, valor: v, texto: moeda(v) })),
      })}
      ${barras({
        titulo: 'Maiores perdas no inventário (30 dias)',
        subtitulo: 'Falta encontrada na contagem (quebra, vencimento, desvio)',
        vazio: 'Nenhuma falta nas contagens do período.',
        itens: perdas.slice(0, 6).map((f) => ({
          rotulo: f.insumo, valor: Number(f.valorPerdas), texto: moeda(f.valorPerdas),
          tip: `${f.insumo}: faltaram ${numero(f.perdas)} ${f.sigla}${f.percentualPerda != null ? ` (${pct(f.percentualPerda)} do que saiu)` : ''}`,
        })),
      })}
    </div>
    ${vencendo.length ? html`<div class="card" style="margin-top:16px">${tabelaValidades(vencendo, false)}</div>` : ''}`;
}

// ---------- Compras: sugestão ----------
/**
 * O que comprar para durar `paraDias`: consumo médio × dias + mínimo − saldo. Arredonda para a embalagem
 * de compra (saco, fardo) quando o item tem uma; senão unidade inteira ou kg/L.
 */
export function sugestaoDeCompra(insumos, indicadores, paraDias) {
  return insumos.filter((i) => i.ativo).map((i) => {
    const consumo = Number(indicadores.get(i.id)?.consumoDiario ?? 0);
    const saldo = Math.max(0, Number(i.estoqueAtual));
    const falta = consumo * paraDias + Number(i.estoqueMinimo) - saldo;
    if (falta <= 0.0005) return null;
    const emb = i.embalagens?.[0];
    let quantidade;
    let texto;
    if (emb) {
      const n = Math.ceil(falta / Number(emb.conteudo));
      quantidade = n * Number(emb.conteudo);
      texto = `${n} × ${emb.nome}`;
    } else if (i.sigla === 'un') {
      quantidade = Math.ceil(falta);
      texto = `${quantidade} un`;
    } else {
      quantidade = Math.ceil(falta * 10) / 10;
      texto = `${numero(quantidade, 1)} ${i.sigla}`;
    }
    return { i, consumo, quantidade, texto, custo: quantidade * Number(i.custoUnitario ?? 0) };
  }).filter(Boolean);
}

export function compras({ sugestao, paraDias }) {
  const porGrupo = Object.entries(sugestao.reduce((acc, s) => {
    (acc[s.i.grupo || 'Sem grupo'] ??= []).push(s);
    return acc;
  }, {})).sort(([a], [b]) => a.localeCompare(b, 'pt-BR'));
  const total = sugestao.reduce((s, x) => s + x.custo, 0);
  return html`
    <div class="row" style="margin-bottom:16px">
      <button class="btn btn-primary" data-acao="nota">📷 Importar nota ou cupom</button>
      <button class="btn" data-acao="compra">🛒 Compra sem nota (cupom)</button>
      <button class="btn" data-acao="importadas">🧾 Compras importadas</button>
    </div>
    <div class="card">
      <div class="row" style="justify-content:space-between">
        <div><h2 style="margin:0">Sugestão de compra</h2>
          <p class="muted" style="margin:2px 0 0;font-size:.85rem">Consumo médio dos últimos 30 dias × dias + estoque mínimo − o que já tem.</p></div>
        <label class="row" style="gap:6px">Comprar para
          <input data-dias-compra type="number" min="1" max="60" value="${paraDias}" style="width:70px" aria-label="Dias"> dias</label>
      </div>
      ${sugestao.length ? html`
        <div class="table-wrap"><table>
          <thead><tr><th>Item</th><th class="right">Tem</th><th class="right">Sai por dia</th><th class="right">Comprar</th><th class="right">Custo estimado</th></tr></thead>
          <tbody>${porGrupo.map(([g, itens]) => html`
            <tr class="grupo-linha"><td colspan="5">${g} <span class="muted" style="font-weight:400">· ${moeda(itens.reduce((s, x) => s + x.custo, 0))}</span></td></tr>
            ${itens.map((s) => html`
              <tr>
                <td>${s.i.nome}${s.i.marca ? html` <span class="badge">${s.i.marca}</span>` : ''}</td>
                <td class="num right">${numero(s.i.estoqueAtual)} ${s.i.sigla}</td>
                <td class="num right muted">${s.consumo ? `${numero(s.consumo, 2)} ${s.i.sigla}` : '—'}</td>
                <td class="num right"><strong>${s.texto}</strong></td>
                <td class="num right">${moeda(s.custo)}</td>
              </tr>`)}`)}
          </tbody>
        </table></div>
        <div class="row" style="justify-content:space-between;margin-top:12px">
          <strong>Total estimado: ${moeda(total)}</strong>
          <div class="row">
            <button class="btn" data-acao="copiar-lista">📋 Copiar lista</button>
            <a class="btn" data-whatsapp target="_blank" rel="noopener" href="https://wa.me/?text=${encodeURIComponent(textoDaLista(porGrupo, paraDias, total))}">💬 Enviar no WhatsApp</a>
          </div>
        </div>` : html`<p class="muted">Nada para comprar nesse período: o estoque cobre o consumo previsto.</p>`}
    </div>`;
}

export function textoDaLista(porGrupo, paraDias, total) {
  const linhas = [`Lista de compras (para ${paraDias} dias)`];
  porGrupo.forEach(([g, itens]) => {
    linhas.push('', `*${g}*`);
    itens.forEach((s) => linhas.push(`- ${s.texto} ${s.i.nome}`));
  });
  linhas.push('', `Total estimado: ${moeda(total)}`);
  return linhas.join('\n');
}

// ---------- Inventário ----------
export function inventario({ insumos, localContagem, furo, diasFuro, lotes }) {
  const locais = [...new Set([...LOCAIS, ...insumos.map((i) => i.local).filter(Boolean)])];
  const daContagem = insumos.filter((i) => i.ativo && (localContagem === '' || (i.local || 'Sem local') === localContagem
    || (localContagem === 'Sem local' && !i.local)));
  const comPerda = furo.filter((f) => Number(f.perdas) > 0 || Number(f.sobras) > 0);
  return html`
    <div class="painel-grade" style="margin-top:0">
      ${barras({
        titulo: `Furo de inventário (${diasFuro} dias)`,
        subtitulo: 'Falta encontrada nas contagens, em reais',
        vazio: 'Nenhuma diferença nas contagens do período.',
        itens: furo.filter((f) => Number(f.valorPerdas) > 0).slice(0, 8).map((f) => ({
          rotulo: f.insumo, valor: Number(f.valorPerdas), texto: moeda(f.valorPerdas),
          tip: `${f.insumo}: faltaram ${numero(f.perdas)} ${f.sigla}`,
        })),
      })}
      <div class="card">
        <div class="row" style="justify-content:space-between">
          <h2 style="margin:0">Esperado × contado</h2>
          <select data-dias-furo style="width:auto" aria-label="Período do furo">
            ${[7, 30, 90].map((d) => html`<option value="${d}" ${diasFuro === d ? 'selected' : ''}>${d} dias</option>`)}
          </select>
        </div>
        ${comPerda.length ? html`<div class="table-wrap"><table>
          <thead><tr><th>Item</th><th class="right">Vendido</th><th class="right">Equipe</th><th class="right">Faltou</th><th class="right">Sobrou</th><th class="right">% perda</th></tr></thead>
          <tbody>${comPerda.map((f) => html`
            <tr>
              <td>${f.insumo}</td>
              <td class="num right">${numero(f.vendido)} ${f.sigla}</td>
              <td class="num right">${numero(f.consumoInterno)} ${f.sigla}</td>
              <td class="num right ${Number(f.perdas) > 0 ? 'bad' : ''}">${numero(f.perdas)} ${f.sigla}<div class="sub">${moeda(f.valorPerdas)}</div></td>
              <td class="num right">${numero(f.sobras)} ${f.sigla}</td>
              <td class="num right">${f.percentualPerda != null ? pct(f.percentualPerda) : '—'}</td>
            </tr>`)}</tbody>
        </table></div>` : html`<p class="muted">Sem diferenças nas contagens desse período.</p>`}
      </div>
    </div>

    <div class="card" style="margin-top:16px">
      <div class="row" style="justify-content:space-between">
        <h2 style="margin:0">Validades</h2>
        <button class="btn btn-sm" data-acao="nova-validade">+ Registrar validade</button>
      </div>
      <p class="muted" style="margin:2px 0 8px;font-size:.85rem">O que chegou antes sai antes: o saldo de cada item fica com os lotes mais novos,
        e os mais antigos somem da lista quando acabam.</p>
      ${lotes.length ? tabelaValidades(lotes, true) : html`<p class="muted">Nenhuma validade registrada. Informe na entrada da compra ou aqui.</p>`}
    </div>
    <div class="card" style="margin-top:16px">
      <h2 style="margin:0">Contagem por local</h2>
      <p class="muted" style="margin:2px 0 10px;font-size:.85rem">Conte um lugar de cada vez (o freezer, a geladeira). Deixe em branco o que não contou;
        a diferença vira ajuste no extrato e aparece no furo de inventário.</p>
      <div class="form-grid">
        <label class="field">Local
          <select data-local-contagem>
            <option value="" ${localContagem === '' ? 'selected' : ''}>Todos</option>
            ${[...locais, 'Sem local'].map((l) => html`<option ${localContagem === l ? 'selected' : ''}>${l}</option>`)}
          </select></label>
        <label class="field">Motivo<input data-motivo-contagem maxlength="150" value="Contagem ${new Date().toLocaleDateString('pt-BR')}"></label>
      </div>
      ${daContagem.length ? html`
        <div class="table-wrap" style="margin-top:8px"><table>
          <thead><tr><th>Item</th><th class="right">No sistema</th><th class="right">Contado</th><th class="right">Diferença</th></tr></thead>
          <tbody>${daContagem.map((i) => html`
            <tr data-contagem="${i.id}">
              <td>${i.nome}${i.local ? html` <span class="badge">${i.local}</span>` : ''}</td>
              <td class="num right">${numero(i.estoqueAtual)} ${i.sigla}</td>
              <td class="right"><input data-contado="${i.id}" inputmode="decimal" style="width:90px" aria-label="Contado de ${i.nome}"></td>
              <td class="num right muted" data-diferenca="${i.id}">—</td>
            </tr>`)}</tbody>
        </table></div>
        <div class="row" style="justify-content:flex-end;margin-top:10px">
          <button class="btn btn-primary" data-acao="salvar-contagem">Salvar contagem</button>
        </div>` : html`<p class="muted">Nenhum item nesse local. Defina o local em Itens → Editar.</p>`}
    </div>`;
}

function tabelaValidades(lotes, comAcoes) {
  return html`
    <h3 style="margin:0 0 6px">${comAcoes ? '' : 'Vencendo nos próximos 7 dias'}</h3>
    <div class="table-wrap"><table>
      <thead><tr><th>Item</th><th class="right">Resta</th><th>Validade</th><th>Situação</th>${comAcoes ? html`<th></th>` : ''}</tr></thead>
      <tbody>${lotes.map((l) => html`
        <tr>
          <td>${l.insumo}${l.local ? html` <span class="badge">${l.local}</span>` : ''}</td>
          <td class="num right">${numero(l.restante)} ${l.sigla}<div class="sub">${moeda(l.valorRestante)}</div></td>
          <td class="num">${l.validade.split('-').reverse().join('/')}</td>
          <td>${seloStatus(l.diasParaVencer <= 2 ? 'CRITICO' : l.diasParaVencer <= 7 ? 'ALERTA' : 'NORMAL')}
            <span class="muted" style="font-size:.8rem">${l.diasParaVencer < 0 ? `venceu há ${-l.diasParaVencer} dia(s)`
              : l.diasParaVencer === 0 ? 'vence hoje' : `vence em ${l.diasParaVencer} dia(s)`}</span></td>
          ${comAcoes ? html`<td class="right"><button class="btn btn-sm btn-icon" data-acao="remover-validade" data-id="${l.id}"
            title="Remover (usado ou descartado)" aria-label="Remover validade">✕</button></td>` : ''}
        </tr>`)}</tbody>
    </table></div>`;
}
