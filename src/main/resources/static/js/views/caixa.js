import { api, sessao } from '../api.js';
import { aoClicar, dataHora, decimal, hora, html, modal, moeda, renderizar, rotulo, toast, toastErro } from '../ui.js';

const CATEGORIAS_SAIDA = ['INSUMOS', 'FUNCIONARIO', 'PRO_LABORE', 'EQUIPAMENTO', 'SANGRIA', 'OUTRO'];
const FORMAS_SAIDA = ['DINHEIRO', 'PIX'];

const radiosForma = (nome = 'forma') => html`
  <div class="segmented">${FORMAS_SAIDA.map((f, i) => html`
    <label class="seg-opcao"><input type="radio" name="${nome}" value="${f}" ${i === 0 ? 'checked' : ''}><span>${rotulo(f)}</span></label>`)}</div>`;

export async function montar(container) {
  const admin = sessao.admin();
  let caixa = null;
  let colaboradores = [];
  let historico = [];
  let fornecedores = [];

  async function carregar() {
    [caixa, colaboradores, historico, fornecedores] = await Promise.all([
      api.get('/caixa/atual'), api.get('/colaboradores'), admin ? api.get('/caixa/historico') : [], api.get('/fornecedores'),
    ]);
    desenhar();
  }

  const corDiferenca = (v) => (Number(v) < -0.004 ? 'bad' : Number(v) > 0.004 ? 'good' : '');

  function telaFechado() {
    return html`
      <form class="card stack" id="form-abrir" style="max-width:420px">
        <h2>Abrir o caixa</h2>
        <p class="text-2" style="margin:0">Conte o dinheiro que já está na gaveta para troco antes da primeira venda.</p>
        <label class="field">Troco na gaveta (R$)<input name="valorAbertura" inputmode="decimal" required placeholder="Ex.: 100" autocomplete="off"></label>
        <div><button class="btn btn-primary" type="submit">Abrir caixa</button></div>
      </form>
      ${admin ? html`
        <h2 style="margin-top:28px">Caixas anteriores</h2>
        <div class="card table-wrap">${historico.length ? html`<table>
          <thead><tr><th>Dia</th><th>Horário</th><th class="right">Recebido</th><th class="right">Saídas</th>
            <th class="right">Esperado</th><th class="right">Contado</th><th class="right">Diferença</th></tr></thead>
          <tbody>${historico.map((c) => html`
            <tr>
              <td>${new Date(c.abertoEm).toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit', month: '2-digit' })}</td>
              <td class="num muted">${hora(c.abertoEm)}–${hora(c.fechadoEm)} · ${c.fechadoPor}</td>
              <td class="num right">${moeda(c.totalRecebido)}</td>
              <td class="num right">${moeda(Number(c.saidasDinheiro) + Number(c.saidasOutras))}</td>
              <td class="num right">${moeda(c.dinheiroEsperado)}</td>
              <td class="num right">${moeda(c.dinheiroContado)}</td>
              <td class="num right ${corDiferenca(c.diferenca)}" title="${c.observacao || ''}"><strong>${moeda(c.diferenca)}</strong></td>
            </tr>`)}</tbody></table>` : html`<div class="empty">Nenhum caixa fechado ainda.</div>`}
        </div>` : ''}`;
  }

  function resumoAdmin(c) {
    const saidas = Number(c.saidasDinheiro) + Number(c.saidasOutras);
    return html`
      <div class="stat-row">
        <div class="card stat"><div class="rotulo">Troco inicial</div><div class="valor num">${moeda(c.valorAbertura)}</div>
          <div class="sub">aberto às ${hora(c.abertoEm)} por ${c.abertoPor}</div></div>
        <div class="card stat"><div class="rotulo">Recebido hoje</div><div class="valor num">${moeda(c.totalRecebido)}</div>
          <div class="sub">${c.recebimentos.map((r) => `${rotulo(r.forma)} ${moeda(r.valor)}`).join(' · ') || 'nenhuma venda ainda'}</div></div>
        <div class="card stat"><div class="rotulo">Taxas de entrega</div><div class="valor num">${moeda(c.taxasEntrega)}</div>
          <div class="sub">dinheiro do motoboy (já está no recebido)</div></div>
        <div class="card stat"><div class="rotulo">Saídas</div><div class="valor num bad">${moeda(saidas)}</div>
          <div class="sub">${moeda(c.saidasDinheiro)} da gaveta · ${moeda(c.saidasOutras)} no PIX</div></div>
        <div class="card stat"><div class="rotulo">Dinheiro na gaveta (esperado)</div><div class="valor num">${moeda(c.dinheiroEsperado)}</div>
          <div class="sub">troco + vendas em dinheiro${Number(c.suprimentos) ? ' + suprimentos' : ''} − saídas em dinheiro</div></div>
      </div>
      ${Number(c.vendasApps) ? html`<p class="muted" style="font-size:.85rem">iFood/99Food no período: ${moeda(c.vendasApps)} (o app paga depois, não entra na gaveta).</p>` : ''}
      <h2 style="margin-top:20px">Recebimentos <span class="badge">${c.vendas.length}</span></h2>
      ${new Date(c.vendasDesde) < new Date(c.abertoEm) ? html`<p class="muted" style="margin:0 0 8px;font-size:.85rem">
        Inclui o que foi recebido desde ${hora(c.vendasDesde)}, antes de o caixa ser aberto.</p>` : ''}
      <div class="card table-wrap">${c.vendas.length ? html`<table>
        <thead><tr><th>Hora</th><th>Venda</th><th>Forma</th><th class="right">Valor</th></tr></thead>
        <tbody>${c.vendas.map((v) => html`
          <tr><td class="num">${hora(v.quando)}</td><td>${v.descricao}</td><td>${rotulo(v.forma)}</td>
            <td class="num right good">+${moeda(v.valor)}</td></tr>`)}</tbody></table>`
        : html`<div class="empty">Nenhuma venda recebida ainda.</div>`}</div>`;
  }

  function telaAberto(c) {
    return html`
      ${admin ? resumoAdmin(c) : html`
        <div class="card stack" style="max-width:520px">
          <div>Caixa aberto às <strong>${hora(c.abertoEm)}</strong> por ${c.abertoPor} com <strong>${moeda(c.valorAbertura)}</strong> de troco.</div>
          <div class="muted" style="font-size:.85rem">No fechamento, conte o dinheiro da gaveta e informe o valor. A conferência fica com o dono.</div>
        </div>`}
      <div class="row" style="margin:16px 0">
        <button class="btn" data-acao="diaria">👷 Pagar diária</button>
        <a class="btn" href="#entregas">🛵 Pagar motoboy</a>
        <button class="btn" data-acao="saida">➖ Outra saída</button>
        <button class="btn" data-acao="suprimento">➕ Suprimento</button>
        <span class="spacer"></span>
        <button class="btn btn-primary" data-acao="fechar">🔒 Fechar caixa</button>
      </div>
      <h2>Saídas e suprimentos</h2>
      <div class="card table-wrap">${c.movimentos.length ? html`<table>
        <thead><tr><th>Hora</th><th>Tipo</th><th>Descrição</th><th>Forma</th><th class="right">Valor</th><th>Quem</th>${admin ? html`<th></th>` : ''}</tr></thead>
        <tbody>${c.movimentos.map((m) => html`
          <tr>
            <td class="num">${hora(m.criadoEm)}</td>
            <td><span class="badge">${rotulo(m.categoria)}</span></td>
            <td>${m.descricao}</td>
            <td>${rotulo(m.forma)}</td>
            <td class="num right ${m.tipo === 'SAIDA' ? 'bad' : 'good'}">${m.tipo === 'SAIDA' ? '−' : '+'}${moeda(m.valor)}</td>
            <td class="muted">${m.criadoPor}</td>
            ${admin ? html`<td class="right"><button class="btn btn-sm" data-acao="remover" data-id="${m.id}" aria-label="Remover lançamento">✕</button></td>` : ''}
          </tr>`)}</tbody></table>` : html`<div class="empty">Nenhuma saída lançada hoje.</div>`}
      </div>`;
  }

  function desenhar() {
    renderizar(container, html`
      <div class="page-head">
        <div><h1>Caixa</h1><p>${caixa ? html`Aberto desde ${dataHora(caixa.abertoEm)}` : 'Fechado'}. Abertura, saídas do dia (diárias, motoboy, compras) e fechamento.</p></div>
      </div>
      ${caixa ? telaAberto(caixa) : telaFechado()}`);

    container.querySelector('#form-abrir')?.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      try {
        caixa = await api.post('/caixa/abrir', { valorAbertura: decimal(ev.target.valorAbertura.value) });
        toast('Caixa aberto. Boas vendas!');
        desenhar();
      } catch (e) { toastErro(e); }
    });
  }

  const funcionarios = () => colaboradores.filter((c) => c.ativo && c.funcao === 'FUNCIONARIO');

  const soltar = aoClicar(container, {
    diaria: () => {
      const lista = funcionarios();
      if (!lista.length) {
        toast(admin ? 'Cadastre o funcionário em Config. → Equipe.' : 'Nenhum funcionário cadastrado. Fale com o dono.', 'erro');
        return;
      }
      modal({
        titulo: 'Pagar diária',
        corpo: html`
          <label class="field">Funcionário
            <select name="colaboradorId">${lista.map((f) => html`<option value="${f.id}">${f.nome} · ${moeda(f.valorDiaria)}</option>`)}</select></label>
          <label class="field">Valor (R$)<input name="valor" inputmode="decimal" value="${String(lista[0].valorDiaria).replace('.', ',')}"></label>
          <div><div class="muted" style="font-size:.8rem;margin-bottom:4px">Pago em</div>${radiosForma()}</div>`,
        textoSalvar: 'Registrar pagamento',
        aoAbrir: (form) => form.colaboradorId.addEventListener('change', () => {
          const f = lista.find((x) => x.id === Number(form.colaboradorId.value));
          form.valor.value = String(f.valorDiaria).replace('.', ',');
        }),
        aoSalvar: async (d) => {
          caixa = await api.post('/caixa/diarias', { colaboradorId: Number(d.colaboradorId), valor: decimal(d.valor), forma: d.forma });
          toast('Diária registrada');
          desenhar();
        },
      });
    },
    saida: () => modal({
      titulo: 'Registrar saída',
      corpo: html`
        <div class="form-grid">
          <label class="field">Tipo<select name="categoria">${CATEGORIAS_SAIDA.map((c) => html`<option value="${c}">${rotulo(c)}</option>`)}</select></label>
          <label class="field">Valor (R$)<input name="valor" inputmode="decimal" required autocomplete="off"></label>
          <label class="field" style="grid-column:1/-1">Descrição<input name="descricao" required maxlength="150" placeholder="Ex.: gelo no mercado"></label>
          <label class="field" style="grid-column:1/-1">Fornecedor / mercado (opcional)<select name="fornecedorId"><option value="">—</option>
            ${fornecedores.filter((f) => f.ativo).map((f) => html`<option value="${f.id}">${f.nome}</option>`)}</select></label>
        </div>
        <div><div class="muted" style="font-size:.8rem;margin-bottom:4px">Pago em</div>${radiosForma()}</div>`,
      textoSalvar: 'Registrar saída',
      aoSalvar: async (d) => {
        caixa = await api.post('/caixa/saidas', {
          categoria: d.categoria, descricao: d.descricao, valor: decimal(d.valor), forma: d.forma,
          fornecedorId: d.fornecedorId ? Number(d.fornecedorId) : null,
        });
        toast('Saída registrada');
        desenhar();
      },
    }),
    suprimento: () => modal({
      titulo: 'Colocar dinheiro na gaveta',
      corpo: html`
        <label class="field">Valor (R$)<input name="valor" inputmode="decimal" required autocomplete="off"></label>
        <label class="field">Descrição<input name="descricao" maxlength="150" placeholder="Ex.: troco trazido do cofre"></label>`,
      textoSalvar: 'Registrar',
      aoSalvar: async (d) => {
        caixa = await api.post('/caixa/suprimentos', { valor: decimal(d.valor), descricao: d.descricao || null });
        desenhar();
      },
    }),
    remover: (el) => modal({
      titulo: 'Remover lançamento',
      corpo: html`<p class="text-2">O lançamento sai do caixa. Se for o pagamento de um motoboy, as entregas dele voltam a ficar pendentes.</p>`,
      textoSalvar: 'Remover',
      perigo: true,
      aoSalvar: async () => {
        caixa = await api.del(`/caixa/movimentos/${el.dataset.id}`);
        desenhar();
      },
    }),
    fechar: () => modal({
      titulo: 'Fechar caixa',
      corpo: html`
        <p class="text-2" style="margin:0">Conte todo o dinheiro da gaveta (notas e moedas) e informe o total.</p>
        <label class="field">Dinheiro contado (R$)<input name="dinheiroContado" inputmode="decimal" required autocomplete="off"></label>
        ${admin ? html`<div class="card" style="background:var(--surface-2)" data-conferencia>Esperado: <strong>${moeda(caixa.dinheiroEsperado)}</strong></div>` : ''}
        <label class="field">Observação<input name="observacao" maxlength="255" placeholder="Opcional"></label>`,
      textoSalvar: 'Fechar caixa',
      aoAbrir: (form) => form.addEventListener('input', () => {
        const alvo = form.querySelector('[data-conferencia]');
        const contado = decimal(form.dinheiroContado.value);
        if (!alvo) return;
        const dif = contado == null ? null : contado - Number(caixa.dinheiroEsperado);
        alvo.innerHTML = String(html`Esperado: <strong>${moeda(caixa.dinheiroEsperado)}</strong>${dif == null ? ''
          : html` · diferença <strong class="${corDiferenca(dif)}">${moeda(dif)}</strong>`}`);
      }),
      aoSalvar: async (d) => {
        const fechado = await api.post('/caixa/fechar', { dinheiroContado: decimal(d.dinheiroContado), observacao: d.observacao || null });
        toast(admin ? `Caixa fechado. Diferença: ${moeda(fechado.diferenca)}` : 'Caixa fechado. Obrigado!');
        await carregar();
      },
    }),
  });

  await carregar();
  return soltar;
}
