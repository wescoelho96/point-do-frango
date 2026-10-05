import { api, sessao } from '../api.js';
import { aoClicar, decimal, diaDeHoje, html, moeda, renderizar, rotulo, toast, toastErro } from '../ui.js';
import { dadosLoja, imprimirVenda } from '../imprimir.js';
import { abrirContaComanda, abrirNovaComanda, trocoHtml } from './comandas.js';
import { blocoPotes } from './potes.js';

const CANAIS = ['BALCAO', 'WHATSAPP', 'TELEFONE'];
const FORMAS = ['PIX', 'DINHEIRO', 'DEBITO', 'CREDITO'];
// Cortesia (pedido dado de graça) só aparece para o dono
const formasDoPerfil = () => (sessao.admin() ? [...FORMAS, 'CORTESIA'] : FORMAS);
const ORDEM_CATEGORIAS = ['PORCAO', 'ESPETINHO', 'COMBO', 'BEBIDA', 'OUTRO'];

/** params.comanda = id da comanda vinda da tela de Comandas (#pdv?comanda=3). */
export async function montar(container, params = {}) {
  const estado = {
    produtos: [],
    comandas: [],
    bairros: [],
    motoboys: [],
    caixa: undefined, // null = fechado
    itens: new Map(), // produtoId: quantidade
    destino: params.comanda ? 'COMANDA' : 'DIRETA',
    comandaId: params.comanda ? Number(params.comanda) : null,
    canal: 'BALCAO',
    forma: 'PIX',
    entrega: false,
    bairroId: null,
    entregadorId: null, // id do motoboy ou 'DONO'
    gratis: false,
    diasGratis: [],
    promocoes: [],
    semPromocao: false,
    salvarCliente: true,
    ultimo: null,
    enviando: false,
    gavetaAberta: false, // celular: comanda aberta por cima do cardápio
  };

  async function carregar() {
    let colaboradores; let loja;
    [estado.produtos, estado.comandas, estado.bairros, colaboradores, estado.caixa, estado.promocoes, loja] = await Promise.all([
      api.get('/produtos?apenasAtivos=true'), api.get('/comandas'), api.get('/bairros?apenasAtivos=true'),
      api.get('/colaboradores'), api.get('/caixa/atual'), api.get('/promocoes'), dadosLoja(true),
    ]);
    estado.diasGratis = loja.diasEntregaGratis ?? [];
    estado.motoboys = colaboradores.filter((c) => c.ativo && c.funcao === 'MOTOBOY');
    if (estado.comandaId && !estado.comandas.some((c) => c.id === estado.comandaId)) estado.comandaId = null;
    if (estado.destino === 'COMANDA' && !estado.comandaId) estado.comandaId = estado.comandas[0]?.id ?? null;
    if (estado.motoboys.length === 1) estado.entregadorId ??= estado.motoboys[0].id;
  }

  const produto = (id) => estado.produtos.find((p) => p.id === id);
  const bairro = () => estado.bairros.find((b) => b.id === estado.bairroId);
  const ehEntrega = () => estado.destino === 'DIRETA' && estado.canal !== 'BALCAO' && estado.entrega;
  const totalItens = () => [...estado.itens].reduce((s, [id, q]) => s + produto(id).precoVenda * q, 0);
  const gratisHoje = () => estado.diasGratis.includes(diaDeHoje());
  const taxaEntrega = () => (ehEntrega() && bairro() && !estado.gratis ? Number(bairro().taxaEntrega) : 0);

  /** Promoções que valem hoje para o que está no carrinho: [{ nome, brinde, quantidade }]. */
  function brindesDoCarrinho() {
    const hoje = diaDeHoje();
    return estado.promocoes
      .filter((p) => p.ativa && p.dias.includes(hoje) && estado.itens.get(p.produtoCompraId) >= p.quantidadeCompra)
      .map((p) => ({ nome: p.nome, brinde: p.produtoBrinde,
        quantidade: Math.floor(estado.itens.get(p.produtoCompraId) / p.quantidadeCompra) * p.quantidadeBrinde }));
  }

  function painelPromocao() {
    const brindes = brindesDoCarrinho();
    if (!brindes.length) return '';
    return html`
      <div class="card" style="background:var(--primary-soft);padding:10px;margin:8px 0">
        <label class="row" style="gap:8px;font-weight:600">
          <input type="checkbox" data-campo="promo" ${estado.semPromocao ? '' : 'checked'} style="width:auto">
          🎁 Promoção do dia: dar o brinde</label>
        ${brindes.map((b) => html`<div style="font-size:.85rem;margin-top:4px">${b.quantidade}× ${b.brinde} <span class="muted">(${b.nome})</span></div>`)}
      </div>`;
  }
  const cortesia = () => estado.destino === 'DIRETA' && estado.forma === 'CORTESIA';
  const total = () => (cortesia() ? 0 : totalItens() + taxaEntrega());
  const qtdItens = () => [...estado.itens.values()].reduce((s, q) => s + q, 0);
  const comandaAtual = () => estado.comandas.find((c) => c.id === estado.comandaId);

  function painelDestino() {
    const naComanda = estado.destino === 'COMANDA';
    return html`
      <div class="segmented" style="margin-bottom:10px">
        <button data-acao="destino" data-v="DIRETA" class="${!naComanda ? 'on' : ''}">💳 Venda direta</button>
        <button data-acao="destino" data-v="COMANDA" class="${naComanda ? 'on' : ''}">🍽️ Comanda / mesa</button>
      </div>
      ${naComanda ? html`
        <div class="row" style="flex-wrap:nowrap">
          <select data-campo="comanda" aria-label="Comanda">
            ${estado.comandas.length ? estado.comandas.map((c) => html`
              <option value="${c.id}" ${c.id === estado.comandaId ? 'selected' : ''}>${c.identificacao} · ${moeda(c.consumo)}</option>`)
              : html`<option value="">Nenhuma comanda aberta</option>`}
          </select>
          <button class="btn" data-acao="nova-comanda" style="white-space:nowrap">+ Nova</button>
        </div>` : ''}`;
  }

  function painelEntrega() {
    if (!ehEntrega()) return '';
    return html`
      <input name="enderecoEntrega" placeholder="Rua e número" maxlength="200" autocomplete="off">
      <input name="referencia" placeholder="Complemento / ponto de referência" maxlength="150" autocomplete="off">
      <select data-campo="bairro" aria-label="Bairro">
        <option value="">Bairro…</option>
        ${estado.bairros.map((b) => html`<option value="${b.id}" ${b.id === estado.bairroId ? 'selected' : ''}>${b.nome} · ${moeda(b.taxaEntrega)}</option>`)}
      </select>
      ${estado.bairros.length ? '' : html`<p class="muted" style="margin:0;font-size:.8rem">Nenhum bairro cadastrado. Cadastre em Entregas → Bairros e taxas.</p>`}
      <select data-campo="motoboy" aria-label="Quem vai entregar">
        <option value="">Quem entrega: definir depois</option>
        ${estado.motoboys.map((m) => html`<option value="${m.id}" ${m.id === estado.entregadorId ? 'selected' : ''}>🛵 ${m.nome}</option>`)}
        <option value="DONO" ${estado.entregadorId === 'DONO' ? 'selected' : ''}>🙋 Eu mesmo (dono)</option>
      </select>
      <label class="row" style="gap:6px;font-size:.85rem">
        <input type="checkbox" data-campo="gratis" ${estado.gratis ? 'checked' : ''} style="width:auto">
        Entrega grátis${gratisHoje() ? ' (hoje é dia de entrega grátis)' : ''}: o cliente não paga a taxa</label>`;
  }

  function painelPagamento() {
    if (estado.destino === 'COMANDA') {
      const c = comandaAtual();
      return html`
        <p class="muted" style="margin:0;font-size:.85rem">Os pedidos vão para a conta da mesa. O cliente paga ao fechar a conta.</p>
        ${c ? html`<button type="button" class="btn btn-block" data-acao="receber-comanda">💳 Receber / fechar conta de ${c.identificacao} · ${moeda(c.consumo)}</button>` : ''}`;
    }
    const precisaCliente = estado.canal !== 'BALCAO';
    return html`
      <div>
        <div class="muted" style="font-size:.8rem;margin-bottom:4px">Canal</div>
        <div class="segmented">
          ${CANAIS.map((c) => html`<button data-acao="canal" data-v="${c}" class="${c === estado.canal ? 'on' : ''}">${rotulo(c)}</button>`)}
        </div>
        <a href="#pedidos?app=1" class="muted" style="font-size:.8rem;display:inline-block;margin-top:6px">Pedido do iFood ou 99Food? Lance aqui com as taxas do app →</a>
      </div>
      ${precisaCliente ? html`
        <div class="segmented">
          <button data-acao="entrega" data-v="0" class="${!estado.entrega ? 'on' : ''}">🛍️ Retirada</button>
          <button data-acao="entrega" data-v="1" class="${estado.entrega ? 'on' : ''}">🛵 Entrega</button>
        </div>
        <div class="stack ${estado.entrega ? 'entrega-box' : ''}" data-cliente>
          <input name="clienteTelefone" placeholder="Telefone / WhatsApp (busca o cadastro)" maxlength="20" inputmode="tel" autocomplete="off">
          <input name="clienteNome" placeholder="Nome do cliente" maxlength="100" autocomplete="off">
          ${painelEntrega()}
          <label class="row" style="gap:6px;font-size:.85rem">
            <input type="checkbox" data-campo="salvar" ${estado.salvarCliente ? 'checked' : ''} style="width:auto">
            Salvar cliente para os próximos pedidos
          </label>
        </div>` : ''}
      <div>
        <div class="muted" style="font-size:.8rem;margin-bottom:4px">Pagamento</div>
        <div class="segmented">
          ${formasDoPerfil().map((f) => html`<button data-acao="forma" data-v="${f}" class="${f === estado.forma ? 'on' : ''}">${rotulo(f)}</button>`)}
        </div>
      </div>
      ${estado.forma === 'CORTESIA' ? html`
        <input name="motivoCortesia" maxlength="150" autocomplete="off" placeholder="Para quem / por quê (ex.: cliente fiel, aniversário)" aria-label="Motivo da cortesia">
        <p class="muted" style="margin:0;font-size:.8rem">Sai do estoque e vai para a cozinha normalmente, sem cobrar nada
          (nem a entrega). O custo aparece no Financeiro e a lista fica em Estoque → Cortesias.</p>` : ''}
      ${estado.forma === 'DINHEIRO' ? html`
        <div class="row" style="flex-wrap:nowrap;align-items:center">
          <input name="valorRecebido" inputmode="decimal" autocomplete="off" style="max-width:170px"
                 placeholder="${ehEntrega() ? 'Troco para (R$)' : 'Cliente entregou (R$)'}" aria-label="Valor recebido">
          <span class="troco" data-troco></span>
        </div>` : ''}`;
  }

  function painelUltimo() {
    const u = estado.ultimo;
    if (!u) return '';
    if (u.comanda) {
      const c = estado.comandas.find((x) => x.id === u.comandaId);
      return html`
        <div class="card ultimo-pedido">
          <strong>Pedido #${u.id} enviado para a cozinha</strong>
          <p class="muted" style="margin:4px 0 0">${u.comanda}${c ? html` · consumo até agora ${moeda(c.consumo)}` : ''}</p>
        </div>`;
    }
    return html`
      <div class="card ultimo-pedido">
        <div class="row"><strong>Pedido #${u.id} · ${moeda(u.totalACobrar ?? u.valorTotal)}</strong><span class="spacer"></span>
          <button class="btn btn-sm" data-acao="imprimir-ultimo">${u.entrega ? '🛵 Via do motoboy' : '🖨️ Cupom'}</button></div>
        ${u.troco != null ? html`<p class="troco" style="margin:6px 0 0">${u.entrega ? 'Motoboy leva de troco' : 'Troco'}: <strong>${moeda(u.troco)}</strong></p>` : ''}
        ${u.distribuicao
          ? html`<p class="muted" style="margin:4px 0 0">Para onde vai esse dinheiro:</p>${blocoPotes(u.distribuicao, { compacto: true })}`
          : html`<p class="muted" style="margin:4px 0 0">${u.status === 'ENTREGUE' ? 'Só bebida: entregue na hora.' : 'Enviado para a cozinha.'}</p>`}
      </div>`;
  }

  function desenhar() {
    const porCategoria = ORDEM_CATEGORIAS
      .map((cat) => [cat, estado.produtos.filter((p) => p.categoria === cat)])
      .filter(([, lista]) => lista.length);

    const cardapio = porCategoria.length ? porCategoria.map(([, lista]) => html`
      <h3 class="categoria-titulo">${lista[0].categoriaRotulo}</h3>
      <div class="produtos">
        ${lista.map((p) => {
          const noCarrinho = estado.itens.get(p.id) || 0;
          const restante = p.unidadesDisponiveis - noCarrinho;
          return html`
            <button class="produto ${noCarrinho ? 'no-carrinho' : ''}" data-acao="add" data-id="${p.id}" ${restante <= 0 ? 'disabled' : ''}>
              ${noCarrinho ? html`<span class="produto-qtd">${noCarrinho}</span>` : ''}
              <strong>${p.nome}</strong>
              <small>${restante <= 0 ? 'Sem estoque' : `dá para fazer ${restante}`}</small>
              <span class="preco">${moeda(p.precoVenda)}</span>
            </button>`;
        })}
      </div>`) : html`<div class="card empty">Nenhum produto ativo. Cadastre o cardápio primeiro.</div>`;

    const itens = [...estado.itens].map(([id, q]) => {
      const p = produto(id);
      return html`
        <div class="comanda-item">
          <span>${p.nome}</span>
          <span class="qtd">
            <button class="btn btn-icon btn-sm" data-acao="menos" data-id="${id}" aria-label="Diminuir">−</button>
            <strong class="num">${q}</strong>
            <button class="btn btn-icon btn-sm" data-acao="add" data-id="${id}" aria-label="Aumentar">+</button>
          </span>
          <span class="num right">${moeda(p.precoVenda * q)}</span>
        </div>`;
    });

    const naComanda = estado.destino === 'COMANDA';
    const podeEnviar = estado.itens.size && !estado.enviando && (!naComanda || estado.comandaId);

    renderizar(container, html`
      <div class="page-head">
        <div><h1>Frente de caixa</h1><p>Toque nos produtos. O estoque baixa sozinho e o pedido aparece na cozinha na hora.</p></div>
      </div>
      ${estado.caixa === null ? html`<div class="card aviso-caixa" style="margin-bottom:12px">💵 O caixa de hoje ainda não foi aberto.
        <a href="#caixa">Abrir caixa</a> para as vendas entrarem no fechamento do dia.</div>` : ''}
      <div class="pdv ${estado.gavetaAberta ? 'gaveta-aberta' : ''}">
        <section class="pdv-cardapio">${cardapio}</section>
        <aside class="card comanda">
          <div class="row comanda-topo">
            <h2>${naComanda && comandaAtual() ? comandaAtual().identificacao : 'Pedido'}</h2>
            <span class="spacer"></span>
            <button class="btn btn-sm so-celular" data-acao="gaveta" aria-label="Voltar ao cardápio">✕ Fechar</button>
          </div>
          ${painelDestino()}
          <div class="comanda-itens">
            ${itens.length ? itens : html`<p class="muted">Nenhum item ainda.</p>`}
          </div>
          ${painelPromocao()}
          <form id="dados-cliente" class="stack">
            ${painelPagamento()}
            <input name="observacao" placeholder="Observação para a cozinha (ex.: sem molho)" maxlength="255">
          </form>
          ${ehEntrega() ? html`
            <div class="cupom-linha"><span>Itens</span><span class="num">${moeda(totalItens())}</span></div>
            <div class="cupom-linha"><span>Entrega${bairro() ? ` (${bairro().nome})` : ''}</span>
              <span class="num">${!bairro() ? 'escolha o bairro' : estado.gratis ? 'grátis' : moeda(taxaEntrega())}</span></div>
            ${estado.gratis && bairro() && estado.entregadorId !== 'DONO' ? html`<p class="muted" style="margin:0;font-size:.78rem">
              A loja paga ${moeda(bairro().taxaEntrega)} ao motoboy por esta entrega.</p>` : ''}` : ''}
          <div class="total"><span>Total</span><span class="num">${moeda(total())}</span></div>
          <button class="btn btn-primary btn-block" data-acao="lancar" ${podeEnviar ? '' : 'disabled'}>
            ${estado.enviando ? 'Enviando…' : naComanda ? '🍳 Enviar para a cozinha' : 'Lançar pedido'}
          </button>
          ${estado.itens.size ? html`<button class="btn btn-block" style="margin-top:8px" data-acao="limpar">Limpar</button>` : ''}
          ${painelUltimo()}
        </aside>
        <button class="barra-pedido so-celular" data-acao="gaveta" ${estado.itens.size || estado.ultimo ? '' : 'hidden'}>
          <span>${qtdItens() ? `${qtdItens()} ${qtdItens() > 1 ? 'itens' : 'item'}` : 'Ver pedido'}</span>
          <strong class="num">${moeda(total())}</strong>
        </button>
      </div>`);
    atualizarTroco();
  }

  function atualizarTroco() {
    const alvo = container.querySelector('[data-troco]');
    const campo = container.querySelector('[name=valorRecebido]');
    if (alvo && campo) alvo.innerHTML = String(trocoHtml(total(), decimal(campo.value)));
  }

  // Preserva o que foi digitado nos campos entre redesenhos
  function lerCampos() {
    const f = container.querySelector('#dados-cliente');
    return f ? Object.fromEntries(new FormData(f)) : {};
  }
  function redesenhar() {
    const dados = lerCampos();
    desenhar();
    const f = container.querySelector('#dados-cliente');
    Object.entries(dados).forEach(([k, v]) => { if (f.elements[k] && f.elements[k].type !== 'checkbox') f.elements[k].value = v; });
    atualizarTroco();
  }

  /** Digitou o telefone: se o cliente já pediu antes, preenche nome, endereço e bairro. */
  async function buscarCliente(tel) {
    if (tel.replace(/\D/g, '').length < 10) return;
    try {
      const c = await api.get(`/clientes/busca?telefone=${encodeURIComponent(tel)}`);
      if (!c) return;
      const f = container.querySelector('#dados-cliente');
      if (!f.clienteNome.value) f.clienteNome.value = c.nome;
      if (f.enderecoEntrega && !f.enderecoEntrega.value) f.enderecoEntrega.value = c.endereco ?? '';
      if (f.referencia && !f.referencia.value) f.referencia.value = c.referencia ?? '';
      if (c.bairroId && estado.bairros.some((b) => b.id === c.bairroId)) estado.bairroId ??= c.bairroId;
      redesenhar();
      toast(`Cliente encontrado: ${c.nome}`);
    } catch (e) { toastErro(e); }
  }

  async function lancar() {
    const campos = lerCampos();
    const naComanda = estado.destino === 'COMANDA';
    if (!naComanda && estado.canal !== 'BALCAO' && !campos.clienteNome?.trim()) {
      toast('Informe o nome do cliente para pedidos fora do balcão.', 'erro');
      return;
    }
    if (ehEntrega() && (!campos.clienteTelefone?.trim() || !campos.enderecoEntrega?.trim() || !estado.bairroId)) {
      toast('Para entrega, informe telefone, endereço e bairro.', 'erro');
      return;
    }
    if (cortesia() && !campos.motivoCortesia?.trim()) {
      toast('Na cortesia, informe para quem foi ou o motivo.', 'erro');
      return;
    }
    const recebido = estado.forma === 'DINHEIRO' ? decimal(campos.valorRecebido) : null;
    if (recebido != null && recebido < total() - 0.004) {
      toast(`O valor recebido é menor que o total (${moeda(total())}).`, 'erro');
      return;
    }
    estado.enviando = true;
    redesenhar();
    try {
      const corpo = {
        itens: [...estado.itens].map(([produtoId, quantidade]) => ({ produtoId, quantidade })),
        observacao: campos.observacao || null,
        semPromocao: estado.semPromocao,
      };
      Object.assign(corpo, naComanda
        ? { canal: 'MESA', comandaId: estado.comandaId }
        : {
          canal: estado.canal, formaPagamento: estado.forma, clienteNome: campos.clienteNome || null,
          clienteTelefone: campos.clienteTelefone || null, enderecoEntrega: campos.enderecoEntrega || null,
          valorRecebido: recebido, salvarCliente: estado.canal !== 'BALCAO' && estado.salvarCliente,
          motivoCortesia: cortesia() ? campos.motivoCortesia.trim() : null,
          entrega: ehEntrega() ? {
            bairroId: estado.bairroId, referencia: campos.referencia || null, gratis: estado.gratis,
            entregadorId: estado.entregadorId === 'DONO' ? null : estado.entregadorId, peloDono: estado.entregadorId === 'DONO',
          } : null,
        });
      const pedido = await api.post('/pedidos', corpo);
      estado.ultimo = pedido;
      estado.itens.clear();
      estado.canal = 'BALCAO';
      estado.entrega = false;
      estado.bairroId = null;
      estado.gratis = gratisHoje();
      estado.semPromocao = false;
      toast(pedido.status === 'ENTREGUE'
        ? `Pedido #${pedido.id} registrado: só bebida, sai direto do balcão${pedido.comanda ? ` (na conta de ${pedido.comanda})` : ''}`
        : `Pedido #${pedido.id} enviado para a cozinha${pedido.comanda ? ` (${pedido.comanda})` : ''}`);
      await carregar(); // atualiza "dá para fazer N" e o consumo da comanda
      estado.enviando = false;
      desenhar();
    } catch (e) {
      estado.enviando = false;
      redesenhar();
      toastErro(e);
    }
  }

  const soltar = aoClicar(container, {
    add: (el) => {
      const id = Number(el.dataset.id);
      estado.itens.set(id, (estado.itens.get(id) || 0) + 1);
      navigator.vibrate?.(15);
      redesenhar();
    },
    menos: (el) => {
      const id = Number(el.dataset.id);
      const q = (estado.itens.get(id) || 0) - 1;
      if (q <= 0) estado.itens.delete(id); else estado.itens.set(id, q);
      redesenhar();
    },
    destino: (el) => {
      estado.destino = el.dataset.v;
      if (estado.destino === 'COMANDA' && !estado.comandaId) estado.comandaId = estado.comandas[0]?.id ?? null;
      redesenhar();
    },
    'nova-comanda': () => abrirNovaComanda(async (c) => {
      await carregar();
      estado.comandaId = c.id;
      redesenhar();
    }),
    canal: (el) => { estado.canal = el.dataset.v; redesenhar(); },
    entrega: (el) => { estado.entrega = el.dataset.v === '1'; redesenhar(); },
    forma: (el) => { estado.forma = el.dataset.v; redesenhar(); },
    limpar: () => { estado.itens.clear(); redesenhar(); },
    gaveta: () => { estado.gavetaAberta = !estado.gavetaAberta; redesenhar(); },
    'imprimir-ultimo': () => imprimirVenda(estado.ultimo).catch(toastErro),
    'receber-comanda': async () => {
      try {
        await abrirContaComanda(await api.get(`/comandas/${estado.comandaId}`), async () => {
          await carregar();
          redesenhar();
        });
      } catch (e) { toastErro(e); }
    },
    lancar,
  });
  const aoMudarCampo = (ev) => {
    const campo = ev.target.dataset.campo;
    if (campo === 'comanda') { estado.comandaId = Number(ev.target.value) || null; redesenhar(); }
    if (campo === 'bairro') { estado.bairroId = Number(ev.target.value) || null; redesenhar(); }
    if (campo === 'motoboy') { estado.entregadorId = ev.target.value === 'DONO' ? 'DONO' : Number(ev.target.value) || null; redesenhar(); }
    if (campo === 'gratis') { estado.gratis = ev.target.checked; redesenhar(); }
    if (campo === 'promo') { estado.semPromocao = !ev.target.checked; redesenhar(); }
    if (campo === 'salvar') estado.salvarCliente = ev.target.checked;
    if (ev.target.name === 'clienteTelefone') buscarCliente(ev.target.value);
  };
  const aoDigitar = (ev) => { if (ev.target.name === 'valorRecebido') atualizarTroco(); };
  container.addEventListener('change', aoMudarCampo);
  container.addEventListener('input', aoDigitar);
  // Enter num campo não pode "enviar" o formulário (recarregaria a página)
  const semSubmit = (ev) => ev.preventDefault();
  container.addEventListener('submit', semSubmit);

  await carregar();
  estado.gratis = gratisHoje();
  desenhar();
  return () => {
    soltar();
    container.removeEventListener('change', aoMudarCampo);
    container.removeEventListener('input', aoDigitar);
    container.removeEventListener('submit', semSubmit);
  };
}
