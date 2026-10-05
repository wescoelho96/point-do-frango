import { api, sessao } from '../api.js';
import { aoClicar, hojeISO, html, moeda, renderizar, rotulo, toast, toastErro } from '../ui.js';
import { dadosLoja, imprimirDocumento } from '../imprimir.js';
import { abas } from './saidas.js';

const nomeMes = (ym) => new Date(`${ym}-15T12:00:00`).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
const menosMeses = (ym, n) => {
  const d = new Date(`${ym}-15T12:00:00`);
  d.setMonth(d.getMonth() - n);
  return d.toISOString().slice(0, 7);
};

const PERIODOS = [
  { id: 'mes-passado', nome: 'Mês passado', intervalo: (m) => [menosMeses(m, 1), menosMeses(m, 1)] },
  { id: 'mes', nome: 'Este mês', intervalo: (m) => [m, m] },
  { id: 'ano', nome: 'Este ano', intervalo: (m) => [`${m.slice(0, 4)}-01`, m] },
  { id: 'ano-passado', nome: 'Ano passado (declaração)', intervalo: (m) => [`${Number(m.slice(0, 4)) - 1}-01`, `${Number(m.slice(0, 4)) - 1}-12`] },
];

/** Baixa a planilha com o token da sessão (um link comum não mandaria o token). */
async function baixar(caminho) {
  const resp = await fetch(`/api/v1${caminho}`, { headers: { Authorization: `Bearer ${sessao.get()?.token}` } });
  if (!resp.ok) throw new Error('Não foi possível gerar a planilha.');
  const nome = /filename="?([^";]+)/.exec(resp.headers.get('Content-Disposition') ?? '')?.[1] ?? 'relatorio.csv';
  const url = URL.createObjectURL(await resp.blob());
  const a = Object.assign(document.createElement('a'), { href: url, download: nome });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function tabelaReceitas(r) {
  return html`
    <table>
      <thead><tr><th>Mês</th><th class="right num">Pedidos</th><th class="right num">Revenda de mercadorias</th>
        <th class="right num">Produtos industrializados</th><th class="right num">Serviços (entrega)</th>
        <th class="right num">Receita bruta</th></tr></thead>
      <tbody>${r.meses.map((m) => html`
        <tr><td>${nomeMes(m.periodo)}</td><td class="num right">${m.pedidos}</td><td class="num right">${moeda(m.revendaMercadorias)}</td>
          <td class="num right">${moeda(m.produtosIndustrializados)}</td><td class="num right">${moeda(m.servicos)}</td>
          <td class="num right"><strong>${moeda(m.total)}</strong></td></tr>`)}</tbody>
      <tfoot><tr><th>Total</th><th class="num right">${r.total.pedidos}</th><th class="num right">${moeda(r.total.revendaMercadorias)}</th>
        <th class="num right">${moeda(r.total.produtosIndustrializados)}</th><th class="num right">${moeda(r.total.servicos)}</th>
        <th class="num right">${moeda(r.total.total)}</th></tr></tfoot>
    </table>`;
}

function tabelaDespesas(r) {
  return html`
    <table><tbody>${r.despesas.map((d) => html`
      <tr><td>${d.rotulo}</td><td class="num right">${d.lancamentos}×</td><td class="num right">${moeda(d.valor)}</td></tr>`)}</tbody>
      <tfoot><tr><th>Total</th><th></th><th class="num right">${moeda(r.totalDespesas)}</th></tr></tfoot></table>`;
}

function blocoDasn(r) {
  if (!r.dasn) return '';
  const d = r.dasn;
  return html`
    <h2>DASN-SIMEI ${d.ano} (declaração anual do MEI)</h2>
    <table><tbody>
      <tr><td>Receita bruta de comércio e indústria</td><td class="num right"><strong>${moeda(d.receitaComercioIndustria)}</strong></td></tr>
      <tr><td>Receita bruta de prestação de serviços</td><td class="num right"><strong>${moeda(d.receitaServicos)}</strong></td></tr>
      <tr><td>Receita bruta total</td><td class="num right"><strong>${moeda(d.receitaTotal)}</strong></td></tr>
      <tr><td>Teve empregado registrado no ano?</td><td class="num right"><strong>${d.teveEmpregado ? 'Sim' : 'Não'}</strong></td></tr>
    </tbody></table>`;
}

export async function montar(container) {
  const mesAtual = hojeISO().slice(0, 7);
  let periodo = 'mes-passado';
  let [de, ate] = PERIODOS[0].intervalo(mesAtual);
  let r;

  async function carregar() {
    r = await api.get(`/contador/resumo?de=${de}&ate=${ate}`);
    desenhar();
  }

  const titulo = () => (de === ate ? nomeMes(de) : `${nomeMes(de)} a ${nomeMes(ate)}`);

  function desenhar() {
    renderizar(container, html`
      ${abas('contador')}
      <div class="page-head">
        <div><h1>Relatório para o contador</h1><p>${titulo()} · ${rotulo(r.regime)}. Tudo o que entrou e saiu, pronto para mandar ou para a sua declaração.</p></div>
        <div class="row">
          <button class="btn" data-acao="imprimir">🖨️ Imprimir / salvar PDF</button>
        </div>
      </div>
      <div class="filtros">
        <div class="segmented">${PERIODOS.map((p) => html`
          <button data-acao="periodo" data-v="${p.id}" class="${p.id === periodo ? 'on' : ''}">${p.nome}</button>`)}</div>
        <input type="month" data-campo="de" value="${de}" max="${mesAtual}" style="width:auto" aria-label="De">
        <input type="month" data-campo="ate" value="${ate}" max="${mesAtual}" style="width:auto" aria-label="Até">
      </div>
      <div class="stack">
        <div class="stat-row">
          <div class="card stat"><div class="rotulo">Receita bruta</div><div class="valor num">${moeda(r.total.total)}</div>
            <div class="sub">${r.total.pedidos} pedidos</div></div>
          <div class="card stat"><div class="rotulo">Despesas pagas</div><div class="valor num bad">${moeda(r.totalDespesas)}</div>
            <div class="sub">taxas de maquininha/apps à parte: ${moeda(r.taxasMaquininhaEApps)}</div></div>
          <div class="card stat"><div class="rotulo">Resultado</div><div class="valor num ${Number(r.resultado) < 0 ? 'bad' : 'good'}">${moeda(r.resultado)}</div>
            <div class="sub">receita − despesas pagas</div></div>
          <div class="card stat"><div class="rotulo">Taxa de serviço (equipe)</div><div class="valor num">${moeda(r.total.taxaServicoEquipe)}</div>
            <div class="sub">repassada, fora da receita</div></div>
        </div>

        <div class="card">
          <h2>Receitas brutas mês a mês (RMRB)</h2>
          <p class="muted" style="margin:0 0 8px;font-size:.85rem">Base do Relatório Mensal das Receitas Brutas que o MEI guarda todo mês. Tudo sem nota fiscal emitida.</p>
          <div class="table-wrap">${tabelaReceitas(r)}</div>
        </div>

        ${r.dasn ? html`<div class="card">${blocoDasn(r)}</div>` : ''}

        <div class="grid grid-2">
          <div class="card"><h2>Despesas por categoria</h2>${r.despesas.length ? html`<div class="table-wrap">${tabelaDespesas(r)}</div>` : html`<p class="muted">Nenhuma despesa lançada.</p>`}</div>
          <div class="card"><h2>Recebido por forma de pagamento</h2>
            <table><tbody>${r.porFormaPagamento.map((f) => html`
              <tr><td>${rotulo(f.forma)}</td><td class="num right muted">${f.pedidos} ped.</td><td class="num right">${moeda(f.valor)}</td></tr>`)}</tbody></table>
            <p class="muted" style="margin:8px 0 0;font-size:.8rem">Consumo da equipe no período (custo, não é despesa paga): ${moeda(r.consumoInterno)}</p></div>
        </div>

        <div class="card">
          <h2>Planilhas para o contador</h2>
          <p class="muted" style="margin:0 0 8px;font-size:.85rem">Arquivos CSV que abrem no Excel. Não levam nome, telefone nem endereço de clientes.</p>
          <div class="row">
            <button class="btn" data-acao="csv" data-v="receitas">📊 Receitas por dia</button>
            <button class="btn" data-acao="csv" data-v="pedidos">🧾 Todos os pedidos</button>
            <button class="btn" data-acao="csv" data-v="saidas">📤 Todas as saídas</button>
          </div>
        </div>

        <div class="card"><h2>Observações</h2><ul style="margin:0;padding-left:18px">${r.observacoes.map((o) => html`<li class="text-2" style="margin:4px 0">${o}</li>`)}</ul></div>
      </div>`);
  }

  async function imprimir() {
    const loja = await dadosLoja();
    imprimirDocumento(html`
      <h1>${loja.nome}: relatório para o contador</h1>
      <div>${loja.documento ? `CNPJ ${loja.documento} · ` : ''}${rotulo(r.regime)} · Período: ${titulo()} · Emitido em ${new Date().toLocaleString('pt-BR')}</div>
      <h2>Receitas brutas mês a mês (sem nota fiscal emitida)</h2>
      ${tabelaReceitas(r)}
      ${blocoDasn(r)}
      <h2>Recebido por forma de pagamento</h2>
      <table><tbody>${r.porFormaPagamento.map((f) => html`<tr><td>${rotulo(f.forma)}</td><td class="num">${f.pedidos} pedidos</td><td class="num">${moeda(f.valor)}</td></tr>`)}</tbody></table>
      <p>Taxas de maquininha e apps descontadas: ${moeda(r.taxasMaquininhaEApps)} · Taxa de serviço repassada à equipe: ${moeda(r.total.taxaServicoEquipe)}</p>
      <h2>Despesas pagas por categoria</h2>
      ${tabelaDespesas(r)}
      <p><strong>Resultado (receita − despesas pagas): ${moeda(r.resultado)}</strong></p>
      <h2>Observações</h2>
      <ul>${r.observacoes.map((o) => html`<li>${o}</li>`)}</ul>`);
  }

  const soltar = aoClicar(container, {
    periodo: (el) => {
      periodo = el.dataset.v;
      [de, ate] = PERIODOS.find((p) => p.id === periodo).intervalo(mesAtual);
      carregar().catch(toastErro);
    },
    imprimir: () => imprimir().catch(toastErro),
    csv: async (el) => {
      try {
        await baixar(`/contador/${el.dataset.v}.csv?de=${de}&ate=${ate}`);
        toast('Planilha baixada');
      } catch (e) { toastErro(e); }
    },
  });
  const aoMudar = (ev) => {
    const campo = ev.target.dataset.campo;
    if (campo !== 'de' && campo !== 'ate') return;
    const novoDe = container.querySelector('[data-campo=de]').value;
    const novoAte = container.querySelector('[data-campo=ate]').value;
    if (novoDe && novoAte && novoDe <= novoAte) { de = novoDe; ate = novoAte; periodo = null; carregar().catch(toastErro); }
  };
  container.addEventListener('change', aoMudar);

  await carregar();
  return () => { soltar(); container.removeEventListener('change', aoMudar); };
}
