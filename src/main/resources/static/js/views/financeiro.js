import { api } from '../api.js';
import { aoClicar, dataCurta, hojeISO, html, moeda, numero, pct, renderizar, rotulo, somarDias } from '../ui.js';
import { ativarTooltips, blocoPotes, totalEquipe } from './potes.js';
import * as saidas from './saidas.js';
import { abas } from './saidas.js';
import * as contador from './contador.js';

const PERIODOS = [
  { id: 'hoje', nome: 'Hoje', intervalo: (h) => [h, h] },
  { id: '7d', nome: '7 dias', intervalo: (h) => [somarDias(h, -6), h] },
  { id: '30d', nome: '30 dias', intervalo: (h) => [somarDias(h, -29), h] },
  { id: 'mes', nome: 'Mês atual', intervalo: (h) => [`${h.slice(0, 8)}01`, h] },
];

export async function montar(container, params = {}) {
  if (params.aba === 'saidas') return saidas.montar(container);
  if (params.aba === 'fornecedores') return saidas.montarFornecedores(container);
  if (params.aba === 'contador') return contador.montar(container);
  let periodo = 'hoje';
  let [inicio, fim] = PERIODOS[0].intervalo(hojeISO());
  let r = null;

  async function carregar() {
    r = await api.get(`/painel/resumo?inicio=${inicio}&fim=${fim}`);
    desenhar();
  }

  function graficoDias() {
    if (r.porDia.length < 2) return '';
    const max = Math.max(...r.porDia.map((d) => Number(d.faturamento)), 1);
    const passo = Math.ceil(r.porDia.length / 10);
    return html`
      <div class="card">
        <h2>Faturamento por dia</h2>
        <p class="muted" style="margin:4px 0 0;font-size:.85rem">Pico: ${moeda(max)}</p>
        <div class="dias" role="img" aria-label="Faturamento diário do período">
          ${r.porDia.map((d) => html`
            <div class="col" data-tip="${dataCurta(d.data)}: ${moeda(d.faturamento)} · ${d.pedidos} pedidos · lucro ${moeda(d.lucroLiquido)}">
              <div class="bar" style="height:${(Number(d.faturamento) / max) * 100}%"></div>
            </div>`)}
        </div>
        <div class="dias-eixo">${r.porDia.map((d, i) => html`<span>${i % passo === 0 ? dataCurta(d.data) : ''}</span>`)}</div>
      </div>`;
  }

  /** De onde sai o pote "Equipe e entregas" e quanto o lucro mudou por causa dele. */
  function blocoEquipe(e, potes) {
    if (!totalEquipe(e) && !Number(e.taxasEntregaRecebidas)) return '';
    const linha = (rotuloLinha, valor, classe = '') => html`<tr><td>${rotuloLinha}</td><td class="num right ${classe}">${valor}</td></tr>`;
    return html`
      <h3 style="margin-top:16px">Equipe e entregas: o que já saiu antes de dividir o lucro</h3>
      <table style="max-width:520px"><tbody>
        ${linha('Lucro das vendas (antes da equipe)', moeda(potes.lucroLiquido))}
        ${Number(e.equipe) ? linha('− Diárias e salários', moeda(e.equipe), 'bad') : ''}
        ${Number(e.motoboys) ? linha('− Pagamentos ao motoboy (diária + taxas)', moeda(e.motoboys), 'bad') : ''}
        ${Number(e.outras) ? linha('− Outras despesas (equipamento, outros)', moeda(e.outras), 'bad') : ''}
        ${Number(e.taxasEntregaRecebidas) ? linha('+ Taxas de entrega pagas pelos clientes', moeda(e.taxasEntregaRecebidas), 'good') : ''}
        <tr><th>= Lucro para dividir</th><th class="num right">${moeda(Number(potes.lucroLiquido) - Number(e.descontadoDoLucro))}</th></tr>
      </tbody></table>
      <p class="muted" style="margin:6px 0 0;font-size:.8rem">Compras de insumos saem do pote de reposição, e aluguel, luz e DAS saem do pote de contas.
        Por isso não entram aqui: não são descontados duas vezes.</p>`;
  }

  function tabelaAgrupada(titulo, linhas, { taxas = false } = {}) {
    const total = linhas.reduce((s, l) => s + Number(l.valor), 0) || 1;
    return html`
      <div class="card">
        <h2>${titulo}</h2>
        ${linhas.length ? html`<table>
          ${taxas ? html`<thead><tr><th></th><th class="right">Pedidos</th><th class="right">Vendido</th><th class="right">Taxas</th><th class="right">%</th></tr></thead>` : ''}
          <tbody>${linhas.map((l) => html`
          <tr class="${l.pedidos ? '' : 'inativo'}"><td>${l.rotulo && l.rotulo !== l.chave ? l.rotulo : rotulo(l.chave)}</td>
              <td class="num right muted">${l.pedidos} ped.</td>
              <td class="num right">${moeda(l.valor)}</td>
              ${taxas ? html`<td class="num right muted">${Number(l.taxas) ? moeda(l.taxas) : '—'}</td>` : ''}
              <td class="num right muted">${pct((l.valor / total) * 100)}</td></tr>`)}
        </tbody></table>` : html`<p class="muted">Sem vendas.</p>`}
      </div>`;
  }

  function desenhar() {
    const c = r.contasDoMes;
    const sugestao = c.percentualSugerido != null && Math.abs(c.percentualSugerido - c.percentualConfigurado) >= 2;
    renderizar(container, html`
      ${abas('vendas')}
      <div class="page-head">
        <div><h1>Financeiro</h1><p>Para onde vai cada real vendido.</p></div>
        <div class="row">
          <div class="segmented">${PERIODOS.map((p) => html`
            <button data-acao="periodo" data-v="${p.id}" class="${p.id === periodo ? 'on' : ''}">${p.nome}</button>`)}</div>
          <input type="date" data-campo="inicio" value="${inicio}" style="width:auto" aria-label="Início">
          <input type="date" data-campo="fim" value="${fim}" style="width:auto" aria-label="Fim">
        </div>
      </div>

      <div class="stack">
        <div class="stat-row">
          <div class="card stat"><div class="rotulo">Faturamento bruto</div><div class="valor num">${moeda(r.faturamentoBruto)}</div>
            <div class="sub">${r.quantidadePedidos} pedidos${r.pedidosCancelados ? ` · ${r.pedidosCancelados} cancelados` : ''}</div></div>
          <div class="card stat"><div class="rotulo">Ticket médio</div><div class="valor num">${moeda(r.ticketMedio)}</div></div>
          <div class="card stat"><div class="rotulo">Lucro líquido</div>
            <div class="valor num ${r.potesFinais.lucroLiquido < 0 ? 'bad' : ''}">${moeda(r.potesFinais.lucroLiquido)}</div>
            <div class="sub">margem de ${pct(r.margemLiquidaPercentual)}${Number(r.equipeEEntregas.descontadoDoLucro)
              ? ` · já sem a equipe e entregas` : ''}</div></div>
          <div class="card stat"><div class="rotulo">Seu pró-labore no período</div>
            <div class="valor num">${moeda(r.potesFinais.proLabore)}</div><div class="sub">pode transferir para a conta pessoal</div></div>
          ${r.cortesias.quantidade ? html`<div class="card stat"><div class="rotulo">🎁 Cortesias</div>
            <div class="valor num">${moeda(r.cortesias.custo)}</div>
            <div class="sub">${r.cortesias.quantidade} pedido(s) dado(s) de graça · custo dos insumos, já descontado do lucro</div></div>` : ''}
          ${Number(r.taxaServico) > 0 ? html`<div class="card stat"><div class="rotulo">Taxa de serviço (comandas)</div>
            <div class="valor num">${moeda(r.taxaServico)}</div><div class="sub">repasse à equipe · fora dos potes</div></div>` : ''}
        </div>

        <div class="card">
          <h2>Divisão do dinheiro</h2>
          ${Number(r.faturamentoBruto) > 0 ? blocoPotes(r.potesFinais, { equipe: r.equipeEEntregas }) : html`<p class="muted">Nenhuma venda no período.</p>`}
          ${blocoEquipe(r.equipeEEntregas, r.potes)}
        </div>

        <div class="grid grid-2">
          <div class="card">
            <h2>Contas do mês (${c.mes.slice(5)}/${c.mes.slice(0, 4)})</h2>
            <p class="text-2" style="margin:6px 0 0">Guardado <strong>${moeda(c.separadoNoMes)}</strong> de <strong>${moeda(c.totalContas)}</strong></p>
            <div class="progress" role="progressbar" aria-valuenow="${c.percentualCoberto}" aria-valuemin="0" aria-valuemax="100">
              <div style="width:${c.percentualCoberto}%"></div></div>
            <p class="muted" style="margin:0;font-size:.85rem">${Number(c.faltaSeparar) > 0
              ? `Faltam ${moeda(c.faltaSeparar)} (${pct(100 - c.percentualCoberto)}) para cobrir as contas.` : '✅ Contas do mês cobertas.'}</p>
            ${sugestao ? html`<p style="margin:10px 0 0;font-size:.9rem">💡 Hoje o rateio guarda <strong>${pct(c.percentualConfigurado)}</strong>
              de cada venda para as contas. Pelo faturamento dos últimos 30 dias, o ideal seria
              <strong>${pct(c.percentualSugerido)}</strong>. Ajuste em Configurações.</p>` : ''}
          </div>
          <div class="card ${r.alertasEstoque.length ? 'alerta' : ''}">
            <h2>Estoque a repor</h2>
            ${r.alertasEstoque.length ? html`<table><tbody>${r.alertasEstoque.map((a) => html`
              <tr><td>⚠️ ${a.insumo}</td><td class="num right">${numero(a.estoqueAtual)} ${a.sigla}</td>
                  <td class="num right muted">mín. ${numero(a.estoqueMinimo)} ${a.sigla}</td></tr>`)}</tbody></table>
              <p class="muted" style="margin:8px 0 0;font-size:.85rem">Use o pote de reposição (${moeda(r.potes.reposicaoEstoque)} no período) para essas compras.</p>`
              : html`<p class="muted">✅ Todos os insumos acima do mínimo.</p>`}
          </div>
        </div>

        ${graficoDias()}

        <div class="grid grid-2">
          <div class="card">
            <h2>Mais vendidos</h2>
            ${r.maisVendidos.length ? html`<table><tbody>${r.maisVendidos.map((m) => html`
              <tr><td>${m.produto}</td><td class="num right">${m.quantidade}×</td><td class="num right">${moeda(m.valor)}</td></tr>`)}
            </tbody></table>` : html`<p class="muted">Sem vendas.</p>`}
          </div>
          <div class="stack">
            ${tabelaAgrupada('Por canal', r.porCanal, { taxas: true })}
            ${tabelaAgrupada('Por forma de pagamento', r.porFormaPagamento)}
          </div>
        </div>
      </div>`);

    container.querySelectorAll('[data-campo]').forEach((input) => input.addEventListener('change', () => {
      const i = container.querySelector('[data-campo=inicio]').value;
      const f = container.querySelector('[data-campo=fim]').value;
      if (i && f && i <= f) { inicio = i; fim = f; periodo = null; carregar(); }
    }));
  }

  const soltarCliques = aoClicar(container, {
    periodo: (el) => {
      periodo = el.dataset.v;
      [inicio, fim] = PERIODOS.find((p) => p.id === periodo).intervalo(hojeISO());
      carregar();
    },
  });
  const soltarTips = ativarTooltips(container);

  await carregar();
  return () => { soltarCliques(); soltarTips(); };
}
