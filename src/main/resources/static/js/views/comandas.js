import { api, sessao } from '../api.js';
import { aoClicar, dataHora, decimal, formaDoPedido, hojeISO, hora, html, modal, moeda, renderizar, rotulo, somarDias, toast, toastErro } from '../ui.js';
import { dadosLoja, imprimirConta } from '../imprimir.js';
import { ouvir } from '../tempo-real.js';

const FORMAS = ['PIX', 'DINHEIRO', 'DEBITO', 'CREDITO'];
const CLASSE_STATUS = { EM_PREPARO: 'badge-warn', PRONTO: 'badge-good', ENTREGUE: '', CANCELADO: 'badge-bad' };

const centavos = (v) => Math.round(v * 100) / 100;
const minutosDesde = (iso) => Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
// Mesmos atalhos de período da tela Financeiro
const PERIODOS = [
  { id: 'hoje', nome: 'Hoje', intervalo: (h) => [h, h] },
  { id: '7d', nome: '7 dias', intervalo: (h) => [somarDias(h, -6), h] },
  { id: '30d', nome: '30 dias', intervalo: (h) => [somarDias(h, -29), h] },
  { id: 'mes', nome: 'Mês atual', intervalo: (h) => [`${h.slice(0, 8)}01`, h] },
];

/** Data local (AAAA-MM-DD) de um instante UTC. O fuso do aparelho é o da loja. */
const diaLocal = (iso) => {
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
};
const rotuloDia = (isoDate) => new Date(`${isoDate}T12:00:00`)
  .toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit', month: '2-digit' });

const tempo = (min) => (min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${min % 60} min`);

export async function montar(container) {
  let abertas = [];
  let encerradas = [];
  let periodo = 'hoje';
  let [inicio, fim] = PERIODOS[0].intervalo(hojeISO());
  let loja = await dadosLoja(true);

  async function carregar() {
    [abertas, encerradas] = await Promise.all([
      api.get('/comandas'),
      api.get(`/comandas/encerradas?inicio=${inicio}&fim=${fim}`),
    ]);
    desenhar();
  }

  /** Histórico agrupado por dia, com subtotal de cada dia e total do período (canceladas não somam). */
  function historico() {
    const fechadas = encerradas.filter((c) => c.status === 'FECHADA');
    const soma = (lista, campo) => lista.reduce((t, c) => t + Number(c[campo] || 0), 0);
    const porDia = new Map();
    encerradas.forEach((c) => {
      const dia = diaLocal(c.fechadaEm);
      if (!porDia.has(dia)) porDia.set(dia, []);
      porDia.get(dia).push(c);
    });
    const unicoDia = inicio === fim;

    return html`
      <div class="page-head" style="margin:28px 0 12px">
        <h2>Comandas encerradas</h2>
        <div class="row">
          <div class="segmented">${PERIODOS.map((p) => html`
            <button data-acao="periodo" data-v="${p.id}" class="${p.id === periodo ? 'on' : ''}">${p.nome}</button>`)}</div>
          <input type="date" data-campo="inicio" value="${inicio}" max="${hojeISO()}" style="width:auto" aria-label="Início">
          <input type="date" data-campo="fim" value="${fim}" max="${hojeISO()}" style="width:auto" aria-label="Fim">
        </div>
      </div>

      <div class="stat-row" style="margin-bottom:12px">
        <div class="card stat"><div class="rotulo">Comandas fechadas</div><div class="valor num">${fechadas.length}</div>
          <div class="sub">${encerradas.length - fechadas.length ? `${encerradas.length - fechadas.length} cancelada(s)` : 'nenhuma cancelada'}</div></div>
        <div class="card stat"><div class="rotulo">Consumo</div><div class="valor num">${moeda(soma(fechadas, 'consumo'))}</div>
          <div class="sub">média ${moeda(fechadas.length ? soma(fechadas, 'consumo') / fechadas.length : 0)} por comanda</div></div>
        <div class="card stat"><div class="rotulo">Taxa de serviço</div><div class="valor num">${moeda(soma(fechadas, 'valorServico'))}</div>
          <div class="sub">${fechadas.filter((c) => c.cobrarServico).length} de ${fechadas.length} pagaram os ${Number(loja.percentualServico).toLocaleString('pt-BR')}%</div></div>
        <div class="card stat"><div class="rotulo">Total recebido</div><div class="valor num">${moeda(soma(fechadas, 'valorTotal'))}</div></div>
      </div>

      <div class="card table-wrap">
        ${encerradas.length ? html`<table>
          <thead><tr><th>Comanda</th><th>Aberta</th><th>Fechada</th><th>Pagamento</th><th class="right">Consumo</th>
            <th class="right">Serviço</th><th class="right">Total</th><th></th></tr></thead>
          <tbody>${[...porDia].map(([dia, lista]) => {
            const fechadasDia = lista.filter((c) => c.status === 'FECHADA');
            return html`
              ${unicoDia ? '' : html`<tr class="linha-dia">
                <td colspan="4"><strong>${rotuloDia(dia)}</strong> <span class="muted">· ${fechadasDia.length} comanda(s)</span></td>
                <td class="num right">${moeda(soma(fechadasDia, 'consumo'))}</td>
                <td class="num right">${moeda(soma(fechadasDia, 'valorServico'))}</td>
                <td class="num right"><strong>${moeda(soma(fechadasDia, 'valorTotal'))}</strong></td><td></td></tr>`}
              ${lista.map((c) => html`
                <tr class="${c.status === 'CANCELADA' ? 'inativo' : ''}">
                  <td><strong>${c.identificacao}</strong>${c.status === 'CANCELADA'
                    ? html` <span class="badge badge-bad" title="${c.motivoCancelamento || ''}">cancelada</span>` : ''}</td>
                  <td class="num muted">${hora(c.abertaEm)}</td>
                  <td class="num">${hora(c.fechadaEm)}</td>
                  <td>${c.formaPagamento ? formaDoPedido(c) : '—'}</td>
                  <td class="num right">${moeda(c.consumo)}</td>
                  <td class="num right">${c.status === 'FECHADA' ? (Number(c.valorServico) ? moeda(c.valorServico) : html`<span class="muted">não cobrado</span>`) : '—'}</td>
                  <td class="num right"><strong>${c.status === 'FECHADA' ? moeda(c.valorTotal) : '—'}</strong></td>
                  <td class="right" style="white-space:nowrap">
                    <button class="btn btn-sm" data-acao="detalhes" data-id="${c.id}">Ver</button>
                    ${c.status === 'FECHADA' ? html`<button class="btn btn-sm" data-acao="reimprimir" data-id="${c.id}" aria-label="Reimprimir comprovante">🖨️</button>` : ''}
                  </td>
                </tr>`)}`;
          })}</tbody>
        </table>` : html`<div class="empty">Nenhuma comanda encerrada ${unicoDia ? 'neste dia' : 'neste período'}.</div>`}
      </div>`;
  }

  function desenhar() {
    renderizar(container, html`
      <div class="page-head">
        <div><h1>Comandas</h1><p>Mesas e clientes consumindo no local. Cada pedido vai para a cozinha na hora; a conta fecha no final.</p></div>
        <button class="btn btn-primary" data-acao="abrir">+ Abrir comanda</button>
      </div>

      ${abertas.length ? html`<div class="comandas">${abertas.map((c) => html`
        <article class="card comanda-card">
          <div class="row"><h2>${c.identificacao}</h2><span class="spacer"></span>
            <span class="muted" style="font-size:.85rem">⏱ ${tempo(minutosDesde(c.abertaEm))}</span></div>
          <div class="muted" style="font-size:.85rem">${c.pedidos.filter((p) => p.status !== 'CANCELADO').length} pedido(s)
            ${c.pedidos.some((p) => p.status === 'EM_PREPARO') ? html` · <span class="badge badge-warn">na cozinha</span>` : ''}</div>
          <div class="comanda-valor num">${moeda(c.consumo)}</div>
          <div class="muted" style="font-size:.8rem">com ${Number(c.percentualServico).toLocaleString('pt-BR')}% de serviço: ${moeda(c.totalComServico)}</div>
          <div class="row" style="margin-top:12px">
            <a class="btn btn-primary btn-sm" href="#pdv?comanda=${c.id}">+ Pedido</a>
            <button class="btn btn-sm" data-acao="conta" data-id="${c.id}">🧾 Conta</button>
          </div>
        </article>`)}</div>`
      : html`<div class="card empty">Nenhuma comanda aberta. Toque em "Abrir comanda" quando uma mesa sentar.</div>`}

      ${historico()}`);

    container.querySelectorAll('[data-campo]').forEach((input) => input.addEventListener('change', () => {
      const i = container.querySelector('[data-campo=inicio]').value;
      const f = container.querySelector('[data-campo=fim]').value;
      if (i && f && i <= f) { inicio = i; fim = f; periodo = null; carregar().catch(toastErro); }
    }));
  }

  const soltarCliques = aoClicar(container, {
    abrir: () => abrirNovaComanda(async () => carregar()),
    conta: async (el) => {
      try { await abrirContaComanda(await api.get(`/comandas/${el.dataset.id}`), carregar); } catch (e) { toastErro(e); }
    },
    periodo: (el) => {
      periodo = el.dataset.v;
      [inicio, fim] = PERIODOS.find((p) => p.id === periodo).intervalo(hojeISO());
      carregar().catch(toastErro);
    },
    detalhes: (el) => {
      const c = encerradas.find((x) => x.id === Number(el.dataset.id));
      modal({
        titulo: `${c.identificacao} · ${c.status === 'FECHADA' ? 'fechada' : 'cancelada'}`,
        largo: true,
        corpo: html`
          <p class="text-2" style="margin:0">Aberta ${dataHora(c.abertaEm)} por ${c.abertaPor || '—'} ·
            ${c.status === 'FECHADA' ? 'fechada' : 'cancelada'} ${dataHora(c.fechadaEm)} por ${c.fechadaPor || '—'}
            ${c.motivoCancelamento ? html`<br>Motivo: ${c.motivoCancelamento}` : ''}</p>
          <div class="grid grid-2" style="gap:16px">
            <div>
              <h3>Consumo</h3>
              ${c.itens.length ? html`<table><tbody>${c.itens.map((i) => html`
                <tr><td>${i.quantidade}× ${i.produto}</td><td class="num right">${moeda(i.subtotal)}</td></tr>`)}</tbody></table>`
                : html`<p class="muted">Sem itens válidos.</p>`}
              ${c.status === 'FECHADA' ? html`<div class="card" style="background:var(--surface-2);margin-top:12px">
                <div class="cupom-linha"><span>Consumo</span><span class="num">${moeda(c.consumo)}</span></div>
                <div class="cupom-linha"><span>Serviço</span><span class="num">${Number(c.valorServico) ? moeda(c.valorServico) : 'não cobrado'}</span></div>
                <div class="cupom-linha total"><span>Total · ${formaDoPedido(c)}</span><span class="num">${moeda(c.valorTotal)}</span></div>
              </div>` : ''}
            </div>
            <div>
              <h3>Pedidos</h3>
              <table style="font-size:.85rem"><tbody>${c.pedidos.map((p) => html`
                <tr class="${p.status === 'CANCELADO' ? 'inativo' : ''}">
                  <td class="num">#${p.id} · ${hora(p.criadoEm)}</td><td>${p.itens}</td><td>${p.criadoPor || ''}</td>
                  <td><span class="badge ${CLASSE_STATUS[p.status]}">${rotulo(p.status)}</span></td></tr>`)}</tbody></table>
            </div>
          </div>`,
      });
    },
    reimprimir: async (el) => {
      try { await imprimirConta(await api.get(`/comandas/${el.dataset.id}`)); } catch (e) { toastErro(e); }
    },
  });

  // Outro aparelho pode ter lançado pedido ou fechado conta
  const soltarTempoReal = ouvir((ev) => {
    if (ev.tipo === 'COMANDA_ALTERADA' && !document.getElementById('modal').open) carregar().catch(() => {});
  });
  const relogio = setInterval(() => { if (!document.getElementById('modal').open) desenhar(); }, 60000);

  await carregar();
  return () => { soltarCliques(); soltarTempoReal(); clearInterval(relogio); };
}

function totaisHtml(c, cobrar) {
  const servico = cobrar ? Number(c.valorServico) : 0;
  return html`
    <div class="cupom-linha"><span>Consumo</span><span class="num">${moeda(c.consumo)}</span></div>
    <div class="cupom-linha"><span>Serviço (${Number(c.percentualServico).toLocaleString('pt-BR')}%)</span>
      <span class="num">${cobrar ? moeda(servico) : 'não cobrado'}</span></div>
    <div class="cupom-linha total"><span>Total</span><span class="num">${moeda(Number(c.consumo) + servico)}</span></div>`;
}

/**
 * Conta da comanda: taxa de serviço, forma de pagamento, troco e fechamento.
 * Usada na tela de Comandas e no PDV. aoMudar() é chamado depois de fechar ou cancelar.
 */
export async function abrirContaComanda(c, aoMudar = async () => {}) {
  const [loja, caixa] = await Promise.all([dadosLoja(), api.get('/caixa/atual')]);
  const admin = sessao.admin();
  modal({
    titulo: `Conta: ${c.identificacao}`,
    largo: true,
    corpo: html`
      <div class="grid grid-2" style="gap:16px">
        <div>
          <h3>Consumo</h3>
          ${c.itens.length ? html`<table><tbody>${c.itens.map((i) => html`
            <tr><td>${i.quantidade}× ${i.produto}${i.brinde ? html` <span class="badge badge-good">🎁 brinde</span>` : ''}</td>
              <td class="num right">${i.brinde ? 'R$ 0,00' : moeda(i.subtotal)}</td></tr>`)}</tbody></table>`
            : html`<p class="muted">Nada consumido ainda.</p>`}
          <h3 style="margin-top:16px">Pedidos</h3>
          <table style="font-size:.85rem"><tbody>${c.pedidos.map((p) => html`
            <tr class="${p.status === 'CANCELADO' ? 'inativo' : ''}">
              <td class="num">#${p.id} · ${hora(p.criadoEm)}</td><td>${p.itens}</td>
              <td><span class="badge ${CLASSE_STATUS[p.status]}">${rotulo(p.status)}</span></td></tr>`)}</tbody></table>
        </div>
        <div class="stack">
          ${caixa ? '' : html`<div class="card aviso-caixa" style="font-size:.85rem">💵 O caixa está fechado. Pode receber normalmente:
            o pagamento entra no caixa de hoje assim que ele for aberto.</div>`}
          <label class="row" style="gap:8px;font-weight:600">
            <input type="checkbox" name="cobrarServico" ${loja.servicoMarcado ? 'checked' : ''} style="width:auto">
            Cobrar ${Number(c.percentualServico).toLocaleString('pt-BR')}% de serviço (opcional para o cliente)
          </label>
          <div class="card" style="background:var(--surface-2)" data-totais>${totaisHtml(c, loja.servicoMarcado)}</div>
          <div>
            <div class="muted" style="font-size:.8rem;margin-bottom:4px">Forma de pagamento</div>
            <div class="segmented" data-formas>${[...FORMAS, 'DIVIDIDO'].map((f, i) => html`
              <label class="seg-opcao"><input type="radio" name="formaPagamento" value="${f}" ${i === 0 ? 'checked' : ''}><span>${f === 'DIVIDIDO' ? '➗ Dividir' : rotulo(f)}</span></label>`)}</div>
          </div>
          <div class="card stack hidden" style="padding:10px;gap:8px" data-divisao></div>
          <div class="stack hidden" data-dinheiro>
            <label class="field">Cliente entregou (R$)
              <input name="valorRecebido" inputmode="decimal" placeholder="Ex.: 100" autocomplete="off"></label>
            <div class="troco" data-troco></div>
          </div>
          <label class="row" style="gap:8px"><input type="checkbox" name="imprimir" checked style="width:auto"> Imprimir comprovante ao fechar</label>
          <div class="row">
            <button type="button" class="btn" data-previa>🖨️ Imprimir conferência</button>
            <a class="btn" href="#pdv?comanda=${c.id}">+ Pedido</a>
            ${admin ? html`<span class="spacer"></span><button type="button" class="btn btn-danger" data-cancelar>Cancelar comanda</button>` : ''}
          </div>
        </div>
      </div>`,
    textoSalvar: 'Fechar conta',
    aoAbrir: (form) => {
      const total = () => centavos(Number(c.consumo) + (form.cobrarServico.checked ? Number(c.valorServico) : 0));
      const dividido = () => form.formaPagamento.value === 'DIVIDIDO';
      // Conta dividida: [{ forma, valor }]; auto = ainda é a divisão igual (acompanha o total ao mudar o serviço)
      const divisao = { partes: [], pessoas: 2, auto: true };
      const somaPartes = () => centavos(divisao.partes.reduce((s, p) => s + (decimal(p.valor) || 0), 0));
      const emDinheiro = () => (dividido()
        ? centavos(divisao.partes.filter((p) => p.forma === 'DINHEIRO').reduce((s, p) => s + (decimal(p.valor) || 0), 0))
        : form.formaPagamento.value === 'DINHEIRO' ? total() : 0);
      const painel = form.querySelector('[data-divisao]');

      function dividirIgual(n) {
        const qtd = Math.min(Math.max(Math.round(n) || 2, 2), 10);
        const base = Math.floor(total() * 100 / qtd) / 100;
        divisao.pessoas = qtd;
        divisao.partes = Array.from({ length: qtd }, (_, i) => ({
          forma: divisao.partes[i]?.forma ?? 'PIX',
          valor: (i === qtd - 1 ? centavos(total() - base * (qtd - 1)) : base).toFixed(2).replace('.', ','),
        }));
        divisao.auto = true;
      }
      const faltaHtml = () => {
        const diferenca = centavos(total() - somaPartes());
        if (Math.abs(diferenca) < 0.005) return html`<span class="good">✓ fecha ${moeda(total())}</span>`;
        return diferenca > 0 ? html`<span class="bad">Falta ${moeda(diferenca)}</span>` : html`<span class="bad">Passou ${moeda(-diferenca)}</span>`;
      };
      const desenharDivisao = () => {
        painel.innerHTML = String(html`
          <div class="row" style="flex-wrap:nowrap;align-items:center;gap:6px;font-size:.85rem">
            Dividir igual em
            <input data-pessoas type="number" min="2" max="10" value="${divisao.pessoas}" style="width:64px" aria-label="Quantas partes">
            <button type="button" class="btn btn-sm" data-dividir-igual>Dividir</button>
          </div>
          ${divisao.partes.map((p, i) => html`
            <div class="row" style="flex-wrap:nowrap;gap:6px">
              <select data-parte-forma="${i}" aria-label="Forma da parte ${i + 1}">
                ${FORMAS.map((f) => html`<option value="${f}" ${f === p.forma ? 'selected' : ''}>${rotulo(f)}</option>`)}
              </select>
              <input data-parte-valor="${i}" value="${p.valor}" inputmode="decimal" autocomplete="off" style="max-width:110px"
                aria-label="Valor da parte ${i + 1}">
              <button type="button" class="btn btn-icon btn-sm" data-remover-parte="${i}" aria-label="Tirar esta parte"
                ${divisao.partes.length <= 2 ? 'disabled' : ''}>✕</button>
            </div>`)}
          <div class="row" style="align-items:center">
            <button type="button" class="btn btn-sm" data-add-parte ${divisao.partes.length >= 10 ? 'disabled' : ''}>+ Parte</button>
            <span class="spacer"></span><span data-falta style="font-size:.85rem">${faltaHtml()}</span>
          </div>`);
      };
      painel.addEventListener('click', (ev) => {
        if (ev.target.closest('[data-dividir-igual]')) dividirIgual(Number(painel.querySelector('[data-pessoas]').value));
        else if (ev.target.closest('[data-add-parte]')) {
          divisao.partes.push({ forma: 'PIX', valor: Math.max(centavos(total() - somaPartes()), 0).toFixed(2).replace('.', ',') });
          divisao.auto = false;
        } else if (ev.target.closest('[data-remover-parte]')) {
          divisao.partes.splice(Number(ev.target.closest('[data-remover-parte]').dataset.removerParte), 1);
          divisao.auto = false;
        } else return;
        desenharDivisao();
        atualizar();
      });

      const atualizar = (ev) => {
        const alvo = ev?.target;
        if (alvo?.dataset?.parteValor != null) {
          divisao.partes[Number(alvo.dataset.parteValor)].valor = alvo.value;
          divisao.auto = false;
        } else if (alvo?.dataset?.parteForma != null) {
          divisao.partes[Number(alvo.dataset.parteForma)].forma = alvo.value;
        } else if (alvo?.dataset?.pessoas != null) {
          divisao.pessoas = Number(alvo.value) || divisao.pessoas;
        } else if (dividido() && (!divisao.partes.length || divisao.auto) && (alvo?.name === 'formaPagamento' || alvo?.name === 'cobrarServico')) {
          dividirIgual(divisao.partes.length || divisao.pessoas);
          desenharDivisao();
        }
        form.querySelector('[data-totais]').innerHTML = String(totaisHtml(c, form.cobrarServico.checked));
        painel.classList.toggle('hidden', !dividido());
        const falta = painel.querySelector('[data-falta]');
        if (falta) falta.innerHTML = String(faltaHtml());
        form.querySelector('[data-dinheiro]').classList.toggle('hidden', !emDinheiro());
        form.querySelector('[data-troco]').innerHTML = String(trocoHtml(emDinheiro(), decimal(form.valorRecebido.value)));
      };
      form.addEventListener('input', atualizar);
      form.addEventListener('change', atualizar);
      form.validarDivisao = () => {
        if (dividido() && Math.abs(total() - somaPartes()) >= 0.005) {
          throw new Error(`As partes somam ${moeda(somaPartes())}, mas o total é ${moeda(total())}.`);
        }
        return dividido() ? divisao.partes.map((p) => ({ forma: p.forma, valor: decimal(p.valor) })) : null;
      };
      form.emDinheiro = emDinheiro;
      form.querySelector('[data-previa]').addEventListener('click', () =>
        imprimirConta(c, { cobrarServico: form.cobrarServico.checked }).catch(toastErro));
      form.querySelector('[data-cancelar]')?.addEventListener('click', () => {
        document.getElementById('modal').close();
        cancelar(c, aoMudar);
      });
    },
    aoSalvar: async (d, form) => {
      const pagamentos = form.validarDivisao();
      const fechada = await api.post(`/comandas/${c.id}/fechar`, {
        formaPagamento: d.formaPagamento,
        cobrarServico: form.cobrarServico.checked,
        valorRecebido: form.emDinheiro() ? decimal(d.valorRecebido) : null,
        pagamentos,
      });
      toast(`${fechada.identificacao} fechada: ${moeda(fechada.valorTotal)} ${pagamentos
        ? `dividida (${fechada.pagamentos.map((x) => `${rotulo(x.forma)} ${moeda(x.valor)}`).join(' + ')})`
        : `no ${rotulo(fechada.formaPagamento)}`}` + (fechada.troco != null ? ` · troco ${moeda(fechada.troco)}` : ''));
      if (form.imprimir.checked) await imprimirConta(fechada);
      await aoMudar();
    },
  });
}

function cancelar(c, aoMudar) {
  modal({
    titulo: `Cancelar ${c.identificacao}`,
    corpo: html`
      <p class="text-2">Todos os pedidos desta comanda são cancelados e os insumos voltam para o estoque.</p>
      <label class="field">Motivo<input name="motivo" required maxlength="255" placeholder="Ex.: cliente desistiu"></label>`,
    textoSalvar: 'Cancelar comanda',
    perigo: true,
    aoSalvar: async ({ motivo }) => {
      await api.post(`/comandas/${c.id}/cancelar`, { motivo });
      toast(`${c.identificacao} cancelada`);
      await aoMudar();
    },
  });
}

/** "Troco: R$ 21,00" ou o quanto falta. Usado aqui e no PDV. */
export function trocoHtml(total, recebido) {
  if (!recebido) return '';
  const troco = recebido - total;
  return troco < -0.004
    ? html`<span class="bad">Faltam ${moeda(-troco)}</span>`
    : html`Troco: <strong class="num">${moeda(troco)}</strong>`;
}

/** Usado aqui e no PDV. aoCriar(comanda) recebe a comanda recém-aberta. */
export function abrirNovaComanda(aoCriar) {
  modal({
    titulo: 'Abrir comanda',
    corpo: html`
      <label class="field">Mesa ou nome do cliente<input name="identificacao" required maxlength="50" placeholder="Ex.: Mesa 4"></label>
      <label class="field">Observação<input name="observacao" maxlength="255" placeholder="Opcional"></label>`,
    textoSalvar: 'Abrir',
    aoSalvar: async (d) => {
      const c = await api.post('/comandas', { identificacao: d.identificacao, observacao: d.observacao || null });
      toast(`Comanda ${c.identificacao} aberta`);
      await aoCriar(c);
    },
  });
}
