import { api } from '../api.js';
import { aoClicar, html, moeda, numero, pct, renderizar } from '../ui.js';
import { barras, indicador } from '../graficos.js';
import { ativarTooltips } from './potes.js';
import { abas } from './saidas.js';

const PERIODOS = [14, 30, 60, 90];

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

  async function carregar() {
    r = await api.get(`/painel/vendas?dias=${dias}`);
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
        <div><h1>Vendas e metas</h1>
          <p>Hoje contra a média dos últimos ${r.dias} dias (${r.diasComVenda} dia(s) com venda; dias fechados não contam).</p></div>
        <div class="segmented">${PERIODOS.map((d) => html`
          <button data-acao="dias" data-v="${d}" class="${d === dias ? 'on' : ''}">${d} dias</button>`)}</div>
      </div>

      <div class="stack">
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
