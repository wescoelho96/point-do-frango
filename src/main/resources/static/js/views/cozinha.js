import { api } from '../api.js';
import { aoClicar, hora, html, renderizar, rotulo, toast, toastErro } from '../ui.js';
import { imprimirTicketCozinha } from '../imprimir.js';
import { liberarAudio, ouvir, tocarAlerta } from '../tempo-real.js';

const RESERVA_MS = 60000; // se o tempo real cair, ainda atualiza a cada 1 min
const ATRASO_MIN = 20;
const CHAVE_SOM = 'pdf.cozinha.som';
const CHAVE_AUTO = 'pdf.cozinha.imprimirAuto';

const lerPref = (k) => { try { return localStorage.getItem(k) === '1'; } catch { return false; } };
const gravarPref = (k, v) => { try { localStorage.setItem(k, v ? '1' : '0'); } catch { /* ignore */ } };

export async function montar(container) {
  let pedidos = [];
  let som = lerPref(CHAVE_SOM);
  let imprimirAuto = lerPref(CHAVE_AUTO);
  let travaTela = null;

  async function carregar() {
    pedidos = await api.get('/pedidos/cozinha');
    desenhar();
  }

  const minutos = (iso) => Math.floor((Date.now() - new Date(iso).getTime()) / 60000);

  function ticket(p) {
    const min = minutos(p.criadoEm);
    const pronto = p.status === 'PRONTO';
    return html`
      <article class="card ticket ${pronto ? 'pronto' : ''}" data-pedido="${p.id}">
        <div class="row">
          <strong class="ticket-numero">#${p.id}</strong>
          <span class="badge">${rotulo(p.canal)}</span>
          ${p.formaPagamento === 'CORTESIA' ? html`<span class="badge badge-good">🎁 cortesia</span>` : ''}
          <span class="spacer"></span>
          <span class="${!pronto && min >= ATRASO_MIN ? 'atrasado' : 'muted'}">${hora(p.criadoEm)} · ${min} min</span>
        </div>
        ${p.comanda || p.clienteNome ? html`<div class="ticket-cliente">${p.comanda ? '🍽️ ' : ''}${p.comanda || p.clienteNome}</div>` : ''}
        <ul>${p.itens.map((i) => html`<li><strong>${i.quantidade}×</strong> ${i.produto}${i.brinde ? html` <span class="badge badge-good">🎁 brinde</span>` : ''}</li>`)}</ul>
        ${p.observacao ? html`<div class="obs">📝 ${p.observacao}</div>` : ''}
        ${p.entrega ? html`<div class="obs" style="margin-top:6px">🛵 Entrega · ${p.entrega.bairro}${p.entrega.entregador ? ` · ${p.entrega.entregador}` : ''}</div>`
          : p.enderecoEntrega ? html`<div class="obs" style="margin-top:6px">🛵 ${p.enderecoEntrega}</div>` : ''}
        <div class="row" style="margin-top:10px">
          <button class="btn btn-sm" data-acao="imprimir" data-id="${p.id}" aria-label="Imprimir ticket">🖨️</button>
          ${pronto
            ? html`<button class="btn btn-sm" data-acao="status" data-id="${p.id}" data-v="EM_PREPARO">↩ Voltar</button>
                   <span class="spacer"></span>
                   <button class="btn btn-primary" data-acao="status" data-id="${p.id}" data-v="ENTREGUE">✅ Entregue</button>`
            : html`<span class="spacer"></span>
                   <button class="btn btn-primary" data-acao="status" data-id="${p.id}" data-v="PRONTO">🔔 Pronto</button>`}
        </div>
      </article>`;
  }

  function desenhar() {
    const emPreparo = pedidos.filter((p) => p.status === 'EM_PREPARO');
    const prontos = pedidos.filter((p) => p.status === 'PRONTO');
    renderizar(container, html`
      <div class="page-head">
        <div><h1>Cozinha</h1><p>Pedidos chegam na hora, de qualquer celular ou do balcão. Em vermelho: esperando há mais de ${ATRASO_MIN} min.</p></div>
        <div class="row">
          <button class="btn ${som ? 'btn-primary' : ''}" data-acao="som">${som ? '🔔 Som ligado' : '🔕 Ativar som'}</button>
          <button class="btn ${imprimirAuto ? 'btn-primary' : ''}" data-acao="auto">🖨️ ${imprimirAuto ? 'Imprime sozinho' : 'Imprimir ao chegar'}</button>
          <button class="btn" data-acao="tela-cheia">⛶ Tela cheia</button>
        </div>
      </div>
      <div class="kds">
        <section class="kds-coluna">
          <h2>Em preparo <span class="badge">${emPreparo.length}</span></h2>
          ${emPreparo.length ? emPreparo.map(ticket) : html`<div class="card empty">Nada na fila 🎉</div>`}
        </section>
        <section class="kds-coluna">
          <h2>Prontos para entregar <span class="badge">${prontos.length}</span></h2>
          ${prontos.length ? prontos.map(ticket) : html`<div class="card empty">Nenhum pedido pronto.</div>`}
        </section>
      </div>`);
  }

  // Mantém a tela do tablet/celular da cozinha acesa (Wake Lock API)
  async function manterTelaAcesa() {
    try { travaTela = await navigator.wakeLock?.request('screen'); } catch { /* sem suporte ou sem permissão */ }
  }
  const aoVoltarVisivel = () => { if (document.visibilityState === 'visible') manterTelaAcesa(); };

  const soltarCliques = aoClicar(container, {
    status: async (el) => {
      el.disabled = true;
      try {
        await api.patch(`/pedidos/${el.dataset.id}/status`, { status: el.dataset.v });
        await carregar();
      } catch (e) {
        toastErro(e);
        el.disabled = false;
      }
    },
    imprimir: (el) => imprimirTicketCozinha(pedidos.find((p) => p.id === Number(el.dataset.id))),
    som: async () => {
      som = !som;
      gravarPref(CHAVE_SOM, som);
      if (som) { await liberarAudio(); tocarAlerta(); }
      desenhar();
    },
    auto: () => {
      imprimirAuto = !imprimirAuto;
      gravarPref(CHAVE_AUTO, imprimirAuto);
      toast(imprimirAuto ? 'Cada pedido novo abre a impressão automaticamente' : 'Impressão automática desligada');
      desenhar();
    },
    'tela-cheia': () => {
      if (document.fullscreenElement) document.exitFullscreen();
      else document.documentElement.requestFullscreen?.().catch(() => {});
    },
  });

  // Pedido novo: recarrega a fila, toca o alerta, destaca e imprime se estiver ligado
  const soltarTempoReal = ouvir(async (ev) => {
    if (!ev.tipo.startsWith('PEDIDO_')) return;
    await carregar().catch(() => {});
    if (ev.tipo === 'PEDIDO_NOVO') {
      if (som) tocarAlerta();
      container.querySelector(`[data-pedido="${ev.id}"]`)?.classList.add('ticket-novo');
      const novo = pedidos.find((p) => p.id === ev.id);
      if (imprimirAuto && novo) imprimirTicketCozinha(novo);
    }
  });

  await carregar();
  manterTelaAcesa();
  document.addEventListener('visibilitychange', aoVoltarVisivel);
  const reserva = setInterval(() => carregar().catch(() => {}), RESERVA_MS);
  return () => {
    clearInterval(reserva);
    soltarCliques();
    soltarTempoReal();
    document.removeEventListener('visibilitychange', aoVoltarVisivel);
    travaTela?.release().catch(() => {});
  };
}
