import { api, sessao } from '../api.js';
import { aoClicar, decimal, hojeISO, hora, html, modal, moeda, numero, renderizar, rotulo, telefone, toast, toastErro } from '../ui.js';
import { imprimirVenda } from '../imprimir.js';
import { ouvir } from '../tempo-real.js';

const CLASSE_STATUS = { EM_PREPARO: 'badge-warn', PRONTO: 'badge-good', ENTREGUE: '', CANCELADO: 'badge-bad' };

export async function montar(container) {
  const admin = sessao.admin();
  let dia = hojeISO();
  let entregas = [];
  let pendencias = [];
  let motoboys = [];
  let bairros = [];

  async function carregar() {
    let pedidos; let colaboradores;
    [pedidos, pendencias, colaboradores, bairros] = await Promise.all([
      api.get(`/pedidos?data=${dia}`), api.get('/caixa/motoboys'), api.get('/colaboradores'), api.get('/bairros'),
    ]);
    entregas = pedidos.filter((p) => p.entrega).reverse();
    motoboys = colaboradores.filter((c) => c.ativo && c.funcao === 'MOTOBOY');
    desenhar();
  }

  function seletorMotoboy(p) {
    const travado = p.status === 'CANCELADO' || p.entrega.pagaAoMotoboy;
    return html`
      <select data-campo="motoboy" data-id="${p.id}" ${travado ? 'disabled' : ''} aria-label="Quem entregou o pedido ${p.id}" style="min-width:130px">
        <option value="">— ninguém ainda —</option>
        <option value="DONO" ${p.entrega.peloDono ? 'selected' : ''}>🙋 Eu mesmo (dono)</option>
        ${motoboys.map((m) => html`<option value="${m.id}" ${m.id === p.entrega.entregadorId ? 'selected' : ''}>${m.nome}</option>`)}
        ${p.entrega.entregadorId && !motoboys.some((m) => m.id === p.entrega.entregadorId)
          ? html`<option value="${p.entrega.entregadorId}" selected>${p.entrega.entregador}</option>` : ''}
      </select>
      ${p.entrega.pagaAoMotoboy ? html`<div><span class="badge badge-good">paga ao motoboy</span></div>` : ''}`;
  }

  function tabelaEntregas() {
    const validas = entregas.filter((p) => p.status !== 'CANCELADO');
    const taxas = validas.reduce((s, p) => s + Number(p.entrega.taxaCobrada), 0);
    return html`
      <div class="page-head" style="margin:0 0 12px">
        <h2>Entregas do dia <span class="badge">${validas.length}</span></h2>
        <div class="row"><span class="muted">${moeda(taxas)} em taxas cobradas</span>
          <input type="date" data-campo="dia" value="${dia}" max="${hojeISO()}" style="width:auto" aria-label="Dia"></div>
      </div>
      <div class="card table-wrap">${entregas.length ? html`<table>
        <thead><tr><th>#</th><th>Cliente</th><th>Endereço</th><th class="right">Cobrar</th><th>Motoboy</th><th>Status</th><th></th></tr></thead>
        <tbody>${entregas.map((p) => html`
          <tr class="${p.status === 'CANCELADO' ? 'inativo' : ''}">
            <td class="num">${p.id}<div class="muted" style="font-size:.78rem">${hora(p.criadoEm)}</div></td>
            <td>${p.clienteNome || html`<span class="muted">—</span>`}${p.clienteTelefone ? html`<div class="muted" style="font-size:.78rem">${telefone(p.clienteTelefone)}</div>` : ''}</td>
            <td>${p.enderecoEntrega || html`<span class="muted">anonimizado</span>`}<div class="muted" style="font-size:.78rem">${p.entrega.bairro} · taxa ${moeda(p.entrega.taxa)}
              ${p.entrega.gratis ? html` <span class="badge badge-warn" title="Cliente não pagou; a loja paga a taxa ao motoboy">grátis</span>` : ''}
              ${p.entrega.peloDono ? html` <span class="badge badge-good" title="A taxa cobrada ficou com a loja">dono entregou</span>` : ''}</div></td>
            <td class="num right"><strong>${moeda(p.totalACobrar)}</strong><div class="muted" style="font-size:.78rem">${rotulo(p.formaPagamento)}${p.troco != null ? ` · troco ${moeda(p.troco)}` : ''}</div></td>
            <td>${seletorMotoboy(p)}</td>
            <td><span class="badge ${CLASSE_STATUS[p.status]}">${rotulo(p.status)}</span></td>
            <td><button class="btn btn-sm" data-acao="imprimir" data-id="${p.id}" aria-label="Imprimir via do motoboy">🖨️</button></td>
          </tr>`)}</tbody></table>` : html`<div class="empty">Nenhuma entrega neste dia.</div>`}
      </div>`;
  }

  function cartoesMotoboys() {
    return html`
      <h2 style="margin-top:28px">Pagar motoboys</h2>
      <p class="muted" style="margin:0 0 12px;font-size:.85rem">Diária fixa + a taxa de cada entrega que ainda não foi paga. O pagamento sai do caixa aberto.</p>
      ${pendencias.length ? html`<div class="comandas">${pendencias.map((m) => html`
        <article class="card comanda-card">
          <h2>🛵 ${m.motoboy}</h2>
          <ul class="lista-simples" style="font-size:.9rem;margin:8px 0">
            <li class="row"><span>${m.quantidadeEntregas} entrega(s)</span><span class="spacer"></span><span class="num">${moeda(m.totalTaxas)}</span></li>
            <li class="row"><span>Diária</span><span class="spacer"></span>
              <span class="num">${m.diariaPaga ? html`<span class="muted">já paga hoje</span>` : moeda(m.valorDiaria)}</span></li>
          </ul>
          <div class="comanda-valor num">${moeda(m.totalAPagar)}</div>
          <div class="row" style="margin-top:12px">
            <button class="btn btn-primary btn-sm" data-acao="pagar" data-id="${m.motoboyId}" ${Number(m.totalAPagar) > 0 ? '' : 'disabled'}>Pagar</button>
            ${m.quantidadeEntregas ? html`<button class="btn btn-sm" data-acao="ver-pendentes" data-id="${m.motoboyId}">Ver entregas</button>` : ''}
          </div>
        </article>`)}</div>`
      : html`<div class="card empty">Nenhum motoboy cadastrado. ${admin ? 'Cadastre em Config. → Equipe.' : ''}</div>`}`;
  }

  function tabelaBairros() {
    return html`
      <div class="page-head" style="margin:28px 0 12px">
        <h2>Bairros e taxas de entrega</h2>
        ${admin ? html`<button class="btn btn-primary btn-sm" data-acao="novo-bairro">+ Bairro</button>` : ''}
      </div>
      <div class="card table-wrap">${bairros.length ? html`<table>
        <thead><tr><th>Bairro</th><th class="right">Taxa</th><th></th></tr></thead>
        <tbody>${bairros.map((b) => html`
          <tr class="${b.ativo ? '' : 'inativo'}">
            <td>${b.nome}${b.ativo ? '' : html` <span class="badge">não entregamos</span>`}</td>
            <td class="num right">${moeda(b.taxaEntrega)}</td>
            <td class="right">${admin ? html`<button class="btn btn-sm" data-acao="editar-bairro" data-id="${b.id}">Editar</button>` : ''}</td>
          </tr>`)}</tbody></table>` : html`<div class="empty">Nenhum bairro cadastrado.</div>`}
      </div>`;
  }

  function desenhar() {
    renderizar(container, html`
      <div class="page-head">
        <div><h1>Entregas</h1><p>Quem leva cada pedido, quanto pagar a cada motoboy no fim do dia e a taxa de cada bairro.</p></div>
      </div>
      ${tabelaEntregas()}
      ${cartoesMotoboys()}
      ${tabelaBairros()}`);
  }

  function formBairro(b) {
    return html`
      <div class="form-grid">
        <label class="field">Bairro<input name="nome" required maxlength="80" value="${b?.nome ?? ''}"></label>
        <label class="field">Taxa de entrega (R$)<input name="taxaEntrega" required inputmode="decimal" value="${b ? numero(b.taxaEntrega, 2) : ''}"></label>
      </div>
      ${b ? html`<label class="row" style="gap:6px"><input type="checkbox" name="ativo" ${b.ativo ? 'checked' : ''} style="width:auto"> Entregamos neste bairro</label>` : ''}`;
  }

  const soltarCliques = aoClicar(container, {
    imprimir: (el) => imprimirVenda(entregas.find((p) => p.id === Number(el.dataset.id))).catch(toastErro),
    'ver-pendentes': (el) => {
      const m = pendencias.find((x) => x.motoboyId === Number(el.dataset.id));
      modal({
        titulo: `Entregas a pagar: ${m.motoboy}`,
        corpo: html`<table><tbody>${m.entregas.map((e) => html`
          <tr><td class="num">#${e.pedidoId}</td><td class="num muted">${new Date(e.criadoEm).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })}</td>
            <td>${e.cliente || '—'}</td><td>${e.bairro}</td><td class="num right">${moeda(e.taxa)}</td></tr>`)}</tbody></table>`,
      });
    },
    pagar: (el) => {
      const m = pendencias.find((x) => x.motoboyId === Number(el.dataset.id));
      const totalCom = (comDiaria) => Number(m.totalTaxas) + (comDiaria ? Number(m.valorDiaria) : 0);
      modal({
        titulo: `Pagar ${m.motoboy}`,
        corpo: html`
          <div class="cupom-linha"><span>${m.quantidadeEntregas} entrega(s)</span><span class="num">${moeda(m.totalTaxas)}</span></div>
          <label class="row" style="gap:8px"><input type="checkbox" name="incluirDiaria" ${m.diariaPaga ? 'disabled' : 'checked'} style="width:auto">
            Diária ${moeda(m.valorDiaria)}${m.diariaPaga ? ' (já paga hoje)' : ''}</label>
          <div class="cupom-linha total"><span>Total</span><span class="num" data-total>${moeda(totalCom(!m.diariaPaga))}</span></div>
          <div><div class="muted" style="font-size:.8rem;margin-bottom:4px">Pago em</div>
            <div class="segmented">${['DINHEIRO', 'PIX'].map((f, i) => html`
              <label class="seg-opcao"><input type="radio" name="forma" value="${f}" ${i === 0 ? 'checked' : ''}><span>${rotulo(f)}</span></label>`)}</div></div>`,
        textoSalvar: 'Confirmar pagamento',
        aoAbrir: (form) => form.incluirDiaria.addEventListener('change', () => {
          form.querySelector('[data-total]').textContent = moeda(totalCom(form.incluirDiaria.checked));
        }),
        aoSalvar: async (d, form) => {
          await api.post(`/caixa/motoboys/${m.motoboyId}/acerto`, { incluirDiaria: form.incluirDiaria.checked, forma: d.forma });
          toast(`${m.motoboy} pago: ${moeda(totalCom(form.incluirDiaria.checked))}`);
          await carregar();
        },
      });
    },
    'novo-bairro': () => modal({
      titulo: 'Novo bairro',
      corpo: formBairro(null),
      aoSalvar: async (d) => {
        await api.post('/bairros', { nome: d.nome, taxaEntrega: decimal(d.taxaEntrega) });
        toast(`${d.nome} cadastrado`);
        await carregar();
      },
    }),
    'editar-bairro': (el) => {
      const b = bairros.find((x) => x.id === Number(el.dataset.id));
      modal({
        titulo: `Editar ${b.nome}`,
        corpo: formBairro(b),
        aoSalvar: async (d) => {
          await api.put(`/bairros/${b.id}`, { nome: d.nome, taxaEntrega: decimal(d.taxaEntrega), ativo: d.ativo === 'on' });
          await carregar();
        },
      });
    },
  });

  const aoMudar = async (ev) => {
    if (ev.target.dataset.campo === 'dia') {
      dia = ev.target.value || hojeISO();
      carregar().catch(toastErro);
    }
    if (ev.target.dataset.campo === 'motoboy') {
      try {
        const dono = ev.target.value === 'DONO';
        await api.patch(`/pedidos/${ev.target.dataset.id}/entregador`, { entregadorId: dono ? null : Number(ev.target.value) || null, dono });
        await carregar();
      } catch (e) { toastErro(e); await carregar().catch(() => {}); }
    }
  };
  container.addEventListener('change', aoMudar);

  const soltarTempoReal = ouvir((ev) => {
    if (ev.tipo.startsWith('PEDIDO_') && !document.getElementById('modal').open) carregar().catch(() => {});
  });

  await carregar();
  return () => { soltarCliques(); soltarTempoReal(); container.removeEventListener('change', aoMudar); };
}
