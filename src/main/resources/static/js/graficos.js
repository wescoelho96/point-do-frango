// Gráficos do painel em HTML/CSS: uma série por gráfico (o título diz o que é), valor escrito na ponta
// de cada barra e detalhe no tooltip (data-tip). Status sempre com ícone e nome, nunca só a cor.
import { html } from './ui.js';

export const STATUS = {
  CRITICO: { rotulo: 'Crítico', icone: '⛔', cor: 'var(--critical)' },
  ALERTA: { rotulo: 'Alerta', icone: '⚠️', cor: 'var(--warning)' },
  NORMAL: { rotulo: 'Normal', icone: '✅', cor: 'var(--good)' },
};

/** Indicador numérico de destaque. */
export function indicador({ rotulo, valor, sub = '', status = null }) {
  return html`
    <div class="card stat">
      <div class="rotulo">${status ? html`<span aria-hidden="true">${STATUS[status].icone}</span> ` : ''}${rotulo}</div>
      <div class="valor num">${valor}</div>
      ${sub ? html`<div class="sub">${sub}</div>` : ''}
    </div>`;
}

/**
 * Barras horizontais a partir de zero, ordenadas por quem chama.
 * itens: { rotulo, valor (número), texto (o que aparece na ponta), tip, status? }
 */
export function barras({ titulo, subtitulo = '', itens, vazio = 'Sem dados no período.' }) {
  const max = Math.max(...itens.map((i) => Math.max(0, i.valor)), 0);
  return html`
    <div class="card grafico">
      <h2>${titulo}</h2>
      ${subtitulo ? html`<p class="muted grafico-sub">${subtitulo}</p>` : ''}
      ${itens.length ? html`
        <div class="hbarras" role="list">
          ${itens.map((i) => html`
            <div class="hbarra" role="listitem" data-tip="${i.tip ?? `${i.rotulo}: ${i.texto}`}">
              <span class="hbarra-rotulo" title="${i.rotulo}">${i.status ? html`<span aria-hidden="true">${STATUS[i.status].icone}</span> ` : ''}${i.rotulo}</span>
              <span class="hbarra-trilho"><span class="hbarra-barra" style="width:${max > 0 ? Math.max(0, i.valor) / max * 100 : 0}%;${i.status
                ? `background:${STATUS[i.status].cor}` : ''}"></span></span>
              <span class="hbarra-valor num">${i.texto}</span>
            </div>`)}
        </div>` : html`<p class="muted">${vazio}</p>`}
    </div>`;
}

/** Composição em uma barra de 100% (situação do estoque), com legenda de ícone, nome e quantidade. */
export function composicao({ titulo, subtitulo = '', partes }) {
  const total = partes.reduce((s, p) => s + p.valor, 0);
  return html`
    <div class="card grafico">
      <h2>${titulo}</h2>
      ${subtitulo ? html`<p class="muted grafico-sub">${subtitulo}</p>` : ''}
      <div class="empilhada" role="img" aria-label="${partes.map((p) => `${STATUS[p.status].rotulo}: ${p.valor}`).join(', ')}">
        ${partes.filter((p) => p.valor > 0).map((p) => html`
          <span style="flex:${p.valor};background:${STATUS[p.status].cor}" data-tip="${STATUS[p.status].rotulo}: ${p.valor} de ${total}"></span>`)}
      </div>
      <div class="legenda">
        ${partes.map((p) => html`<span><span class="chip" style="--c:${STATUS[p.status].cor}"></span>${STATUS[p.status].icone}
          ${STATUS[p.status].rotulo} <strong class="num">${p.valor}</strong></span>`)}
      </div>
    </div>`;
}
