import { api } from '../api.js';
import { aoClicar, dataCurta, hojeISO, html, moeda, numero, pct, renderizar } from '../ui.js';
import { barras, indicador } from '../graficos.js';
import { ativarTooltips } from './potes.js';
import { abas } from './saidas.js';

const PERIODOS = [14, 30, 60, 90];
/** Não são despesa do mês: compra de mercadoria vira estoque (entra no custo quando vende) e sangria/suprimento só mudam o dinheiro de lugar. */
const FORA_DAS_DESPESAS = ['INSUMOS', 'SANGRIA', 'SUPRIMENTO', 'PRO_LABORE'];

/**
 * Resultado do mês: vendas menos taxas, custo do que saiu (fichas, cortesias, equipe, perdas da contagem) e despesas.
 * Ponto de equilíbrio: quanto vender no mês para pagar tudo, pela margem que sobra de cada real vendido.
 */
export function saudeDoMes({ resumo, saidas, furo, conferencia, hoje }) {
  const n = (v) => Number(v ?? 0);
  const vendas = n(resumo.faturamentoBruto);
  const taxas = n(resumo.potes.taxaPagamento);
  const custoVendido = n(resumo.potes.reposicaoEstoque);
  const cortesias = n(resumo.cortesias?.custo);
  const equipe = n(saidas.consumoInterno);
  // Loja nova: a primeira contagem corrige lançamentos antigos e infla as perdas; a conta pelo dinheiro
  // (comprado - baixado - estoque) mede o que de fato sumiu desde a primeira compra.
  const [anoHoje, mesHoje] = hoje.split('-').map(Number);
  const mesPassado = mesHoje === 1 ? `${anoHoje - 1}-12-01` : `${anoHoje}-${String(mesHoje - 1).padStart(2, '0')}-01`;
  const pelaConferencia = conferencia?.desde != null && conferencia.desde >= mesPassado;
  const perdas = pelaConferencia ? n(conferencia.semExplicacao) : furo.reduce((t, f) => t + n(f.valorPerdas) - n(f.valorSobras), 0);
  const margem = vendas - taxas - custoVendido - cortesias - equipe - perdas;
  const despesas = saidas.porCategoria.filter((c) => !FORA_DAS_DESPESAS.includes(c.categoria));
  const pagas = despesas.reduce((t, c) => t + n(c.valor), 0);
  const aPagar = saidas.contasFixas.reduce((t, c) => t + Math.max(0, n(c.valorMensal) - n(c.pago)), 0);
  const resultado = margem - pagas - aPagar;
  const proLabore = n(saidas.porCategoria.find((c) => c.categoria === 'PRO_LABORE')?.valor);
  const compras = n(saidas.porCategoria.find((c) => c.categoria === 'INSUMOS')?.valor);

  const percentual = vendas > 0 ? margem / vendas : 0;
  const necessario = percentual > 0 ? (pagas + aPagar) / percentual : null;
  const [ano, mes, dia] = hoje.split('-').map(Number);
  const diasNoMes = new Date(ano, mes, 0).getDate();
  const restantes = diasNoMes - dia + 1;
  const falta = necessario == null ? null : Math.max(0, necessario - vendas);
  const ticket = n(resumo.ticketMedio);
  const linha = (rotulo, valor, dica = '', forte = false) => html`
    <tr${forte ? html` style="font-weight:700"` : ''}><td>${rotulo}${dica ? html`<div class="muted" style="font-size:.78rem">${dica}</div>` : ''}</td>
      <td class="num right">${moeda(valor)}</td></tr>`;

  return html`
    <div class="card grafico ${resultado < 0 ? 'alerta' : ''}">
      <h2>Saúde do mês ${resultado < 0 ? '⚠️' : '✅'}</h2>
      <p class="muted grafico-sub">De 1º/${String(mes).padStart(2, '0')} até hoje, com as contas fixas que ainda vencem no mês</p>
      <div class="painel-grade" style="margin-top:0">
        <table><tbody>
          ${linha('Vendas', vendas, `${resumo.quantidadePedidos} pedidos · ticket médio ${moeda(ticket)}`)}
          ${linha('(−) Taxas da maquininha e dos apps', -taxas)}
          ${linha('(−) Custo do que foi vendido', -custoVendido, 'pela ficha técnica de cada produto')}
          ${cortesias ? linha('(−) Cortesias e doações', -cortesias) : ''}
          ${equipe ? linha('(−) Consumo da equipe e doações', -equipe) : ''}
          ${perdas ? (pelaConferencia
            ? linha(perdas > 0 ? '(−) Mercadoria que sumiu' : '(+) Estoque a mais do que as compras explicam', -perdas,
              `desde ${dataCurta(conferencia.desde)}: comprado ${moeda(conferencia.compras)} − baixado ${moeda(conferencia.baixado)} − estoque ${moeda(conferencia.estoque)}`)
            : linha(perdas > 0 ? '(−) Perdas na contagem do estoque' : '(+) Sobras na contagem do estoque', -perdas,
              'diferença entre o que as fichas baixaram e o que foi contado')) : ''}
          ${linha('= Sobra das vendas', margem, vendas ? `${pct(percentual * 100)} de cada real vendido` : '', true)}
          ${despesas.map((c) => linha(`(−) ${c.rotulo}`, -n(c.valor)))}
          ${aPagar ? linha('(−) Contas fixas que ainda vencem', -aPagar) : ''}
          ${linha(resultado < 0 ? '= Prejuízo do mês' : '= Lucro do mês', resultado, proLabore ? `antes das suas retiradas (${moeda(proLabore)})` : '', true)}
        </tbody></table>
        <div class="stack">
          ${indicador({ rotulo: 'Vender no mês para pagar tudo', valor: necessario == null ? '—' : moeda(necessario),
            sub: necessario == null ? 'sem margem positiva ainda' : `já vendido ${moeda(vendas)}`, status: falta === 0 ? 'NORMAL' : null })}
          ${indicador({ rotulo: 'Falta vender', valor: falta == null ? '—' : moeda(falta),
            sub: falta ? `${moeda(falta / restantes)} por dia nos ${restantes} dia(s) que faltam${ticket ? ` · ≈ ${Math.ceil(falta / ticket)} pedidos` : ''}`
              : 'contas do mês cobertas 🎉', status: falta ? 'ALERTA' : 'NORMAL' })}
          ${indicador({ rotulo: 'Pago em mercadoria no mês', valor: moeda(compras),
            sub: `custo do que foi vendido: ${moeda(custoVendido)} · compras no crédito entram no mês da fatura` })}
        </div>
      </div>
    </div>`;
}

function textoMeta(feito, alvo, fmt) {
  return alvo > 0 ? `meta ${fmt(alvo)} · ${pct((feito / alvo) * 100)} da média` : 'sem histórico para a meta';
}

/** Barra do que já foi vendido hoje com o traço da meta (a média por dia de venda). */
function linhaMeta({ rotulo, feito, alvo, texto, tip }) {
  const escala = Math.max(feito, alvo, 1e-9) * 1.1;
  const batida = alvo > 0 && feito >= alvo;
  return html`
    <div class="meta-linha" role="listitem" data-tip="${tip}">
      <span class="hbarra-rotulo" title="${rotulo}">${batida ? html`<span aria-hidden="true">✅</span> ` : ''}${rotulo}</span>
      <span class="meta-trilho">
        <span class="meta-feito${batida ? ' batida' : ''}" style="width:${(feito / escala) * 100}%"></span>
        ${alvo > 0 ? html`<span class="meta-alvo" style="left:${(alvo / escala) * 100}%"></span>` : ''}
      </span>
      <span class="hbarra-valor num">${texto}</span>
    </div>`;
}

function porHora(horas) {
  const usadas = horas.filter((h) => Number(h.mediaFaturamento) > 0 || Number(h.hojeFaturamento) > 0);
  if (!usadas.length) return html`<p class="muted">Sem vendas no período.</p>`;
  const de = usadas[0].hora;
  const ate = usadas[usadas.length - 1].hora;
  const faixa = horas.filter((h) => h.hora >= de && h.hora <= ate);
  const max = Math.max(...faixa.map((h) => Math.max(Number(h.mediaFaturamento), Number(h.hojeFaturamento))), 1);
  return html`
    <div class="colunas" role="list">
      ${faixa.map((h) => {
        const hoje = Number(h.hojeFaturamento);
        const media = Number(h.mediaFaturamento);
        return html`
        <div class="coluna" role="listitem"
          data-tip="${`${h.hora}h — hoje ${moeda(hoje)} (${h.hojePedidos} pedidos) · média ${moeda(media)} (${numero(h.mediaPedidos)} pedidos)`}">
          <span class="coluna-area">
            <span class="coluna-barra${hoje > 0 && hoje >= media ? ' batida' : ''}" style="height:${(hoje / max) * 100}%"></span>
            ${media > 0 ? html`<span class="coluna-media" style="height:${(media / max) * 100}%"></span>` : ''}
          </span>
          <span class="coluna-rotulo">${h.hora}h</span>
        </div>`;
      })}
    </div>
    <div class="legenda">
      <span><span class="chip" style="--c:var(--pote-reposicao)"></span>Hoje</span>
      <span><span class="chip chip-linha"></span>Média do horário (meta)</span>
    </div>`;
}

export async function montar(container) {
  let dias = 30;
  let r = null;
  let saude = null;

  async function carregar() {
    const hoje = hojeISO();
    const inicio = `${hoje.slice(0, 8)}01`;
    const [vendas, resumo, saidas, furo, conferencia] = await Promise.all([api.get(`/painel/vendas?dias=${dias}`),
      api.get(`/painel/resumo?inicio=${inicio}&fim=${hoje}`), api.get(`/saidas/mes?mes=${hoje.slice(0, 7)}`),
      api.get(`/insumos/furo?inicio=${inicio}&fim=${hoje}`), api.get('/painel/conferencia')]);
    r = vendas;
    saude = { resumo, saidas, furo, conferencia, hoje };
    desenhar();
  }

  function desenhar() {
    const h = r.hojeTotais;
    const m = r.mediaDiaria;
    const fatHoje = Number(h.faturamento);
    const fatMeta = Number(m.faturamento);
    const metaBatida = fatMeta > 0 && fatHoje >= fatMeta;
    const metas = r.produtos.filter((p) => Number(p.mediaDiaria) > 0 || p.hoje > 0).slice(0, 15);
    const top = r.produtos.filter((p) => p.quantidadePeriodo > 0).slice(0, 10);
    const semana = r.porDiaDaSemana.filter((d) => d.diasComVenda > 0);

    renderizar(container, html`
      ${abas('metas')}
      <div class="page-head">
        <div><h1>Resultado e metas</h1>
          <p>Hoje contra a média dos últimos ${r.dias} dias (${r.diasComVenda} dia(s) com venda; dias fechados não contam).</p></div>
        <div class="segmented">${PERIODOS.map((d) => html`
          <button data-acao="dias" data-v="${d}" class="${d === dias ? 'on' : ''}">${d} dias</button>`)}</div>
      </div>

      <div class="stack">
        ${saudeDoMes(saude)}
        <div class="stat-row">
          ${indicador({ rotulo: 'Vendido hoje', valor: moeda(fatHoje), status: metaBatida ? 'NORMAL' : null,
            sub: textoMeta(fatHoje, fatMeta, moeda) })}
          ${indicador({ rotulo: 'Pedidos hoje', valor: h.pedidos, status: m.pedidos > 0 && h.pedidos >= m.pedidos ? 'NORMAL' : null,
            sub: textoMeta(h.pedidos, m.pedidos, String) })}
          ${indicador({ rotulo: 'Ticket médio hoje', valor: moeda(h.ticketMedio), sub: `no período: ${moeda(m.ticketMedio)}` })}
          ${indicador({ rotulo: 'Falta para a meta do dia', valor: moeda(Math.max(0, fatMeta - fatHoje)),
            sub: metaBatida ? 'meta batida 🎉' : 'para igualar a média diária' })}
        </div>

        <div class="painel-grade">
          <div class="card grafico">
            <h2>Metas de hoje por produto</h2>
            <p class="muted grafico-sub">Barra = vendido hoje · traço = média por dia de venda (a meta)</p>
            ${metas.length ? html`<div class="hbarras" role="list">${metas.map((p) => linhaMeta({
              rotulo: p.produto, feito: p.hoje, alvo: Number(p.mediaDiaria),
              texto: `${p.hoje} / ${numero(p.mediaDiaria)}`,
              tip: `${p.produto}: ${p.hoje} hoje · média ${numero(p.mediaDiaria)} por dia · ${p.quantidadePeriodo} no período (${moeda(p.faturamentoPeriodo)})`,
            }))}</div>` : html`<p class="muted">Sem vendas no período.</p>`}
          </div>

          <div class="card grafico">
            <h2>Venda por hora</h2>
            <p class="muted grafico-sub">Faturamento de hoje em cada hora contra a média daquele horário</p>
            ${porHora(r.porHora)}
          </div>

          ${barras({ titulo: 'Produtos que mais saem', subtitulo: `Unidades vendidas nos últimos ${r.dias} dias`,
            itens: top.map((p) => ({ rotulo: p.produto, valor: p.quantidadePeriodo,
              texto: `${p.quantidadePeriodo} un · ${moeda(p.faturamentoPeriodo)}`,
              tip: `${p.produto}: ${p.quantidadePeriodo} un, ${moeda(p.faturamentoPeriodo)} (${numero(p.mediaDiaria)} por dia)` })) })}

          ${barras({ titulo: 'Média por dia da semana', subtitulo: 'Faturamento médio nos dias em que abriu',
            itens: semana.map((d) => ({ rotulo: d.rotulo, valor: Number(d.mediaFaturamento), texto: moeda(d.mediaFaturamento),
              tip: `${d.rotulo}: média ${moeda(d.mediaFaturamento)} em ${d.diasComVenda} dia(s)` })) })}
        </div>
      </div>`);
  }

  const soltarCliques = aoClicar(container, {
    dias: (el) => {
      dias = Number(el.dataset.v);
      carregar();
    },
  });
  const soltarTips = ativarTooltips(container);
  await carregar();
  return () => { soltarCliques(); soltarTips(); };
}
