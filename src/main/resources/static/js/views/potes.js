// Visualização dos "potes" do rateio. Usada no PDV (pedido recém-lançado) e no Financeiro.
import { html, moeda, pct } from '../ui.js';

// Ordem fixa = cor fixa: cada pote tem sempre a mesma cor em todas as telas.
export const POTES = [
  { chave: 'reposicaoEstoque', nome: 'Reposição de estoque', cor: '--pote-reposicao',
    dica: 'Custo dos insumos usados (ficha técnica). Reserve para comprar mercadoria.' },
  { chave: 'contasFixas', nome: 'Contas da loja', cor: '--pote-contas',
    dica: 'Aluguel, luz, água, internet, gás, contador.' },
  { chave: 'proLabore', nome: 'Seu pró-labore', cor: '--pote-prolabore',
    dica: 'Seu salário. Transfira para a conta pessoal em datas fixas.' },
  { chave: 'reservaEmergencia', nome: 'Reserva de emergência', cor: '--pote-reserva',
    dica: 'Fica na conta da loja para imprevistos e investimentos.' },
  { chave: 'taxaPagamento', nome: 'Taxas (maquininha e apps)', cor: '--pote-taxas',
    dica: 'Descontado pela maquininha, banco ou iFood/99Food. Esse dinheiro nem chega.' },
];

/** Diárias + motoboy + outras despesas da operação pagas no período (vem do painel). */
export const totalEquipe = (e) => Number(e.equipe) + Number(e.motoboys) + Number(e.outras);

/** Pote extra do painel: o que já foi pago à equipe e ao motoboy no período (sai antes do lucro). */
const POTE_EQUIPE = { chave: 'equipe', nome: 'Equipe e entregas', cor: '--pote-equipe',
  dica: 'Diárias, salários e motoboy já pagos no período (e outras despesas da operação). Saem antes de dividir o lucro.' };

export function blocoPotes(potes, { compacto = false, equipe = null } = {}) {
  const d = equipe ? { ...potes, equipe: totalEquipe(equipe) } : potes;
  const lista = equipe ? [POTES[0], POTES[1], POTE_EQUIPE, ...POTES.slice(2)] : POTES;
  // total = taxa + reposição + contas (+ equipe) + lucro (o lucro já contém pró-labore + reserva)
  const bruto = Number(d.taxaPagamento) + Number(d.reposicaoEstoque) + Number(d.contasFixas) + Number(d.lucroLiquido)
    + Number(d.equipe ?? 0);
  const prejuizo = Number(d.lucroLiquido) < 0;
  const base = bruto > 0 ? bruto : 1;

  const barra = html`
    <div class="potes-barra" role="img" aria-label="Divisão do valor vendido entre os potes">
      ${lista.filter((p) => Number(d[p.chave]) > 0).map((p) => html`
        <div style="flex:${Number(d[p.chave])};background:var(${p.cor})"
             data-tip="${p.nome}: ${moeda(d[p.chave])} (${pct((d[p.chave] / base) * 100)})"></div>`)}
    </div>`;

  const aviso = prejuizo ? html`
    <p class="bad" role="alert" style="margin:8px 0 0">
      ⚠️ <strong>Prejuízo de ${moeda(-d.lucroLiquido)}</strong>: as vendas não cobrem custo + contas + taxas${equipe ? ' + equipe' : ''}.
      Revise os preços no Cardápio${equipe ? ' ou os gastos com equipe' : ''}.
    </p>` : '';

  if (compacto) {
    return html`${barra}
      <table style="font-size:.85rem">
        ${lista.map((p) => html`
          <tr><td><span class="chip" style="--c:var(${p.cor})"></span>${p.nome}</td>
              <td class="num right">${moeda(d[p.chave])}</td></tr>`)}
      </table>${aviso}`;
  }

  return html`${barra}${aviso}
    <div class="potes">
      ${lista.map((p) => html`
        <div class="card pote" style="--c:var(${p.cor})">
          <div class="text-2" style="font-size:.85rem"><span class="chip"></span>${p.nome}</div>
          <div class="valor num">${moeda(d[p.chave])}</div>
          <div class="muted" style="font-size:.8rem">${pct((d[p.chave] / base) * 100)} do que entrou</div>
          <p>${p.dica}</p>
        </div>`)}
    </div>`;
}

// Tooltip para elementos com data-tip.
export function ativarTooltips(container) {
  let tip = null;
  const mover = (ev) => {
    const alvo = ev.target.closest('[data-tip]');
    if (!alvo) { tip?.remove(); tip = null; return; }
    if (!tip) { tip = document.createElement('div'); tip.className = 'tooltip'; document.body.appendChild(tip); }
    tip.textContent = alvo.dataset.tip;
    const x = Math.min(ev.clientX + 12, window.innerWidth - tip.offsetWidth - 8);
    tip.style.left = `${Math.max(8, x)}px`;
    tip.style.top = `${ev.clientY - 36}px`;
  };
  const sair = () => { tip?.remove(); tip = null; };
  container.addEventListener('mousemove', mover);
  container.addEventListener('mouseleave', sair);
  return () => { container.removeEventListener('mousemove', mover); container.removeEventListener('mouseleave', sair); sair(); };
}
