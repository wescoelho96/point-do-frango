import { api, sessao } from '../api.js';
import { aoClicar, decimal, hojeISO, hora, html, modal, moeda, numero, renderizar, rotulo, toast, toastErro } from '../ui.js';
import { blocoPotes } from './potes.js';

const CLASSE_STATUS = { EM_PREPARO: 'badge-warn', PRONTO: 'badge-good', ENTREGUE: '', CANCELADO: 'badge-bad' };
const PLATAFORMAS = ['IFOOD', 'NOVENTA_NOVE_FOOD'];

/** params.app = '1' abre direto o lançamento de pedido do iFood/99Food (atalho vindo do PDV). */
export async function montar(container, params = {}) {
  const admin = sessao.admin();
  let dia = hojeISO();
  let pedidos = [];

  async function carregar() {
    pedidos = await api.get(`/pedidos?data=${dia}`);
    desenhar();
  }

  function desenhar() {
    const validos = pedidos.filter((p) => p.status !== 'CANCELADO');
    const total = validos.reduce((s, p) => s + Number(p.valorTotal), 0);
    renderizar(container, html`
      <div class="page-head">
        <div><h1>Pedidos do dia</h1><p>${validos.length} pedidos · ${moeda(total)}</p></div>
        <div class="row">
          <button class="btn btn-primary" data-acao="app">+ Pedido iFood / 99Food</button>
          <input type="date" id="filtro-dia" value="${dia}" style="width:auto" aria-label="Dia">
        </div>
      </div>
      <div class="card table-wrap">
        ${pedidos.length ? html`
        <table>
          <thead><tr>
            <th>#</th><th>Hora</th><th>Canal</th><th>Cliente</th><th>Itens</th><th>Pagamento</th>
            <th class="right">Total</th>${admin ? html`<th class="right">Taxas</th><th class="right">Lucro</th>` : ''}<th>Status</th>${admin ? html`<th></th>` : ''}
          </tr></thead>
          <tbody>
            ${pedidos.map((p) => html`
              <tr class="${p.status === 'CANCELADO' ? 'inativo' : ''}">
                <td class="num">${p.id}</td>
                <td class="num">${hora(p.criadoEm)}</td>
                <td>${rotulo(p.canal)}${p.codigoExterno ? html`<div class="muted" style="font-size:.78rem">nº ${p.codigoExterno}</div>` : ''}</td>
                <td>${p.clienteNome || html`<span class="muted">—</span>`}${p.entrega ? html`<div class="muted" style="font-size:.78rem">🛵 ${p.entrega.bairro}</div>` : ''}</td>
                <td>${p.itens.map((i) => `${i.quantidade}× ${i.produto}`).join(', ')}</td>
                <td>${p.formaPagamento ? rotulo(p.formaPagamento) : html`<span class="muted">na comanda</span>`}</td>
                <td class="num right">${moeda(p.valorTotal)}${p.entrega ? html`<div class="muted" style="font-size:.78rem">+ ${moeda(p.entrega.taxa)} entrega</div>` : ''}</td>
                ${admin ? html`
                  <td class="num right muted">${moeda(p.distribuicao.taxaPagamento)}</td>
                  <td class="num right ${p.distribuicao.lucroLiquido < 0 ? 'bad' : ''}">${moeda(p.distribuicao.lucroLiquido)}</td>` : ''}
                <td><span class="badge ${CLASSE_STATUS[p.status]}" title="${p.motivoCancelamento || ''}">${rotulo(p.status)}</span></td>
                ${admin ? html`<td>${p.status !== 'CANCELADO'
                  ? html`<button class="btn btn-sm btn-danger" data-acao="cancelar" data-id="${p.id}">Cancelar</button>` : ''}</td>` : ''}
              </tr>`)}
          </tbody>
        </table>` : html`<div class="empty">Nenhum pedido neste dia.</div>`}
      </div>`);
    container.querySelector('#filtro-dia').addEventListener('change', (ev) => {
      dia = ev.target.value || hojeISO();
      carregar().catch(toastErro);
    });
  }

  const soltar = aoClicar(container, {
    app: () => abrirPedidoDeApp(async (p) => { dia = p.criadoEm ? hojeLocal(p.criadoEm) : dia; await carregar(); }).catch(toastErro),
    cancelar: (el) => {
      const id = el.dataset.id;
      modal({
        titulo: `Cancelar pedido #${id}`,
        corpo: html`
          <p class="text-2">Os insumos voltam para o estoque e a venda sai do financeiro. Essa ação fica registrada.</p>
          <label class="field">Motivo<input name="motivo" required maxlength="255" placeholder="Ex.: cliente desistiu"></label>`,
        textoSalvar: 'Cancelar pedido',
        perigo: true,
        aoSalvar: async ({ motivo }) => {
          await api.post(`/pedidos/${id}/cancelar`, { motivo });
          toast(`Pedido #${id} cancelado e estoque devolvido`);
          await carregar();
        },
      });
    },
  });

  await carregar();
  if (params.app) {
    history.replaceState(null, '', '#pedidos');
    abrirPedidoDeApp(async () => carregar()).catch(toastErro);
  }
  return soltar;
}

const hojeLocal = (iso) => {
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
};

function agoraLocal() {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

/**
 * Lançamento manual de pedido do iFood/99Food (ex.: no fim do dia, olhando o app).
 * O estoque baixa pela ficha técnica; o financeiro usa o valor e as taxas que o app informou.
 */
async function abrirPedidoDeApp(aoLancar) {
  const admin = sessao.admin();
  const [produtos, cfg] = await Promise.all([
    api.get('/produtos?apenasAtivos=true'),
    admin ? api.get('/financeiro/configuracao') : Promise.resolve(null),
  ]);
  const taxaPadrao = (plataforma) => (cfg ? Number(plataforma === 'IFOOD' ? cfg.taxaIfood : cfg.taxaNoventaNove) : null);

  const linhaItem = () => html`
    <div class="ficha-linha" data-item style="grid-template-columns:minmax(0,1fr) 70px 32px">
      <select name="produtoId" aria-label="Produto">${produtos.map((p) => html`<option value="${p.id}">${p.nome} · ${moeda(p.precoVenda)}</option>`)}</select>
      <input name="qtd" type="number" min="1" max="99" value="1" aria-label="Quantidade">
      <button type="button" class="btn btn-icon" data-remover-item aria-label="Remover">✕</button>
    </div>`;

  modal({
    titulo: 'Pedido do iFood / 99Food',
    largo: true,
    corpo: html`
      <p class="text-2" style="margin:0">Lance o que saiu pelo app (dá para fazer no fim do dia, olhando o pedido ou o extrato).
        Os insumos baixam do estoque e o valor entra no financeiro já descontando as taxas do app.</p>
      <div class="grid grid-2" style="gap:16px">
        <div class="stack">
          <div class="segmented">${PLATAFORMAS.map((p, i) => html`
            <label class="seg-opcao"><input type="radio" name="plataforma" value="${p}" ${i === 0 ? 'checked' : ''}><span>${rotulo(p)}</span></label>`)}</div>
          <div class="form-grid">
            <label class="field">Nº do pedido no app<input name="codigoExterno" maxlength="50" placeholder="Ex.: 7342"></label>
            <label class="field">Data e hora<input name="realizadoEm" type="datetime-local" value="${agoraLocal()}" max="${agoraLocal()}" required></label>
          </div>
          <label class="field">Cliente (opcional)<input name="clienteNome" maxlength="100"></label>
          <div>
            <div class="muted" style="font-size:.8rem;margin-bottom:4px">Itens (o estoque baixa pela ficha técnica)</div>
            <div class="stack" data-itens>${linhaItem()}</div>
            <button type="button" class="btn btn-sm" style="margin-top:8px" data-add-item>+ Item</button>
          </div>
        </div>
        <div class="stack">
          <div class="muted" style="font-size:.85rem" data-cardapio></div>
          <div class="form-grid">
            <label class="field">Valor do pedido no app (R$)<input name="valorPedido" inputmode="decimal" required></label>
            <label class="field">Promoção paga pela loja (R$)<input name="descontoLoja" inputmode="decimal" placeholder="0,00"></label>
            <label class="field" style="grid-column:1/-1">Taxas que o app descontou (R$)<input name="taxasPlataforma" inputmode="decimal" required>
              <span class="muted" style="font-size:.78rem" data-dica-taxa></span></label>
          </div>
          <div class="card" style="background:var(--surface-2)" data-resumo></div>
          <label class="row" style="gap:8px"><input type="checkbox" name="enviarParaCozinha" style="width:auto">
            Enviar para a cozinha (deixe desmarcado se o pedido já saiu)</label>
        </div>
      </div>`,
    textoSalvar: 'Lançar pedido',
    aoAbrir: (form) => {
      const itens = form.querySelector('[data-itens]');
      // Enquanto a pessoa não digitar, valor e taxa acompanham os itens e a plataforma
      const editado = { valorPedido: false, taxasPlataforma: false };
      form.valorPedido.addEventListener('input', () => { editado.valorPedido = true; });
      form.taxasPlataforma.addEventListener('input', () => { editado.taxasPlataforma = true; });

      const atualizar = () => {
        let cardapio = 0;
        let cmv = 0;
        itens.querySelectorAll('[data-item]').forEach((l) => {
          const p = produtos.find((x) => x.id === Number(l.querySelector('[name=produtoId]').value));
          const q = Number(l.querySelector('[name=qtd]').value) || 0;
          cardapio += Number(p.precoVenda) * q;
          cmv += Number(p.custoAtual) * q;
        });
        form.querySelector('[data-cardapio]').textContent = `Valor no seu cardápio: ${moeda(cardapio)} (no app o preço pode ser outro)`;
        if (!editado.valorPedido) form.valorPedido.value = numero(cardapio, 2);

        const valor = decimal(form.valorPedido.value) || 0;
        const desconto = decimal(form.descontoLoja.value) || 0;
        const vendido = valor - desconto;
        const pct = taxaPadrao(form.plataforma.value);
        if (!editado.taxasPlataforma && pct != null) form.taxasPlataforma.value = numero((vendido * pct) / 100, 2);
        form.querySelector('[data-dica-taxa]').textContent = pct != null
          ? `Sugestão: ${numero(pct, 2)}% do pedido (configurável em Configurações). Use o valor exato do extrato do app.`
          : 'Use o valor exato do extrato do app (comissão + taxa de pagamento online).';

        const taxas = decimal(form.taxasPlataforma.value) || 0;
        const repasse = vendido - taxas;
        let potes = '';
        if (cfg && vendido > 0) {
          // Mesma conta do RateioFinanceiro.calcularComTaxa (só pré-visualização)
          const contas = (vendido * Number(cfg.percentualContasFixas)) / 100;
          const lucro = vendido - taxas - cmv - contas;
          const pro = lucro > 0 ? (lucro * Number(cfg.percentualProLabore)) / 100 : 0;
          potes = blocoPotes({ taxaPagamento: taxas, reposicaoEstoque: cmv, contasFixas: contas, lucroLiquido: lucro,
            proLabore: pro, reservaEmergencia: Math.max(lucro, 0) - pro }, { compacto: true });
        }
        form.querySelector('[data-resumo]').innerHTML = String(html`
          <div class="cupom-linha"><span>Vendido</span><span class="num">${moeda(vendido)}</span></div>
          <div class="cupom-linha"><span>Taxas do app</span><span class="num">− ${moeda(taxas)}</span></div>
          <div class="cupom-linha total"><span>Repasse (cai na conta)</span><span class="num">${moeda(repasse)}</span></div>
          ${potes}`);
      };

      form.addEventListener('input', atualizar);
      form.addEventListener('change', atualizar);
      form.querySelector('[data-add-item]').addEventListener('click', () => {
        itens.insertAdjacentHTML('beforeend', String(linhaItem()));
        atualizar();
      });
      itens.addEventListener('click', (ev) => {
        if (ev.target.closest('[data-remover-item]') && itens.children.length > 1) {
          ev.target.closest('[data-item]').remove();
          atualizar();
        }
      });
      atualizar();
    },
    aoSalvar: async (d, form) => {
      const itens = [...form.querySelectorAll('[data-item]')].map((l) => ({
        produtoId: Number(l.querySelector('[name=produtoId]').value),
        quantidade: Number(l.querySelector('[name=qtd]').value),
      })).filter((i) => i.quantidade > 0);
      const p = await api.post('/pedidos/plataforma', {
        plataforma: d.plataforma,
        codigoExterno: d.codigoExterno || null,
        realizadoEm: d.realizadoEm || null,
        clienteNome: d.clienteNome || null,
        itens,
        valorPedido: decimal(d.valorPedido),
        descontoLoja: decimal(d.descontoLoja),
        taxasPlataforma: decimal(d.taxasPlataforma),
        enviarParaCozinha: form.enviarParaCozinha.checked,
      });
      toast(`Pedido ${rotulo(p.canal)}${p.codigoExterno ? ` nº ${p.codigoExterno}` : ''} lançado: ${moeda(p.valorTotal)}`);
      await aoLancar(p);
    },
  });
}
