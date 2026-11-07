import { api, sessao } from '../api.js';
import { aoClicar, decimal, diaDeHoje, html, moeda, renderizar, rotulo, toast, toastErro } from '../ui.js';
import { dadosLoja, imprimirVenda } from '../imprimir.js';
import { abrirContaComanda, abrirNovaComanda, trocoHtml } from './comandas.js';
import { blocoPotes } from './potes.js';

const CANAIS = ['BALCAO', 'WHATSAPP', 'TELEFONE'];
const FORMAS = ['PIX', 'DINHEIRO', 'DEBITO', 'CREDITO'];
// Cortesia (pedido dado de graça) só aparece para o dono
const formasDoPerfil = () => (sessao.admin() ? [...FORMAS, 'DIVIDIDO', 'CORTESIA'] : [...FORMAS, 'DIVIDIDO']);
const ROTULO_BOTAO = { DIVIDIDO: '➗ Dividir' };
const centavos = (v) => Math.round(v * 100) / 100;
const ORDEM_CATEGORIAS = ['PORCAO', 'ESPETINHO', 'COMBO', 'BEBIDA', 'OUTRO'];

/** params.comanda = id da comanda vinda da tela de Comandas (#pdv?comanda=3). */
export async function montar(container, params = {}) {
  const estado = {
    produtos: [],
    busca: '',
    comandas: [],
    bairros: [],
    motoboys: [],
    caixa: undefined, // null = fechado
    itens: new Map(), // produtoId: quantidade
    destino: params.comanda ? 'COMANDA' : 'DIRETA',
    comandaId: params.comanda ? Number(params.comanda) : null,
    canal: 'BALCAO',
    forma: 'PIX',
    desconto: '', // como foi digitado: "5" (reais) ou "10%"
    // Conta dividida: [{ forma, valor }]; partesAuto = ainda é a divisão igual (acompanha o total)
    partes: [],
    partesAuto: true,
    pessoas: 2,
    entrega: false,
    bairroId: null,
    entregadorId: null, // id do motoboy ou 'DONO'
    gratis: false,
    diasGratis: [],
    promocoes: [],
    semPromocao: false,
    // comanda de papel lançada depois (só o dono): data e hora da venda
    realizadoEm: '',
    motoboyJaPago: false,
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
  const semAcento = (x) => String(x ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();

  // Busca no cardápio: esconde os produtos sem redesenhar, para o teclado do celular não fechar
  function filtrar() {
    const termos = semAcento(estado.busca).split(/\s+/).filter(Boolean);
    let achados = 0;
    container.querySelectorAll('[data-categoria]').forEach((cat) => {
      let visiveis = 0;
      cat.querySelectorAll('[data-nome]').forEach((el) => {
        const ok = termos.every((t) => el.dataset.nome.includes(t));
        el.hidden = !ok;
        if (ok) visiveis++;
      });
      cat.hidden = !visiveis;
      achados += visiveis;
    });
    const vazio = container.querySelector('[data-busca-vazia]');
    if (vazio) vazio.hidden = !termos.length || achados > 0;
    container.querySelector('[data-acao="limpar-busca"]')?.toggleAttribute('hidden', !estado.busca);
  }
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
  const dividido = () => estado.destino === 'DIRETA' && estado.forma === 'DIVIDIDO';
  /** Desconto em reais: "10%" vira 10% dos itens; nunca passa do valor dos itens. */
  const valorDesconto = () => {
    if (cortesia() || estado.destino !== 'DIRETA') return 0;
    const texto = String(estado.desconto).trim();
    if (!texto) return 0;
    const v = texto.endsWith('%') ? totalItens() * (decimal(texto.slice(0, -1)) || 0) / 100 : decimal(texto) || 0;
    return Math.min(Math.max(centavos(v), 0), totalItens());
  };
  const total = () => (cortesia() ? 0 : centavos(totalItens() - valorDesconto() + taxaEntrega()));
  const somaPartes = () => centavos(estado.partes.reduce((s, p) => s + (decimal(p.valor) || 0), 0));
  /** O que é pago em dinheiro (para o troco): tudo, ou só a parte em dinheiro da conta dividida. */
  const emDinheiro = () => (dividido()
    ? centavos(estado.partes.filter((p) => p.forma === 'DINHEIRO').reduce((s, p) => s + (decimal(p.valor) || 0), 0))
    : estado.forma === 'DINHEIRO' ? total() : 0);

  /** Divide o total em n partes iguais; os centavos que sobram vão para a última. Mantém a forma de cada parte. */
  function dividirIgual(n) {
    const qtd = Math.min(Math.max(Math.round(n) || 2, 2), 10);
    const base = Math.floor(total() * 100 / qtd) / 100;
    estado.pessoas = qtd;
    estado.partes = Array.from({ length: qtd }, (_, i) => ({
      forma: estado.partes[i]?.forma ?? 'PIX',
      valor: (i === qtd - 1 ? centavos(total() - base * (qtd - 1)) : base).toFixed(2).replace('.', ','),
    }));
    estado.partesAuto = true;
  }
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
      ${sessao.admin() && estado.destino === 'DIRETA' ? html`
        <details class="card" style="padding:8px 10px" ${estado.realizadoEm ? 'open' : ''}>
          <summary style="cursor:pointer;font-size:.85rem;font-weight:600">📅 Venda de outro dia (comanda de papel)</summary>
          <label class="field" style="margin-top:6px">Data e hora da venda
            <input type="datetime-local" data-campo="realizado" value="${estado.realizadoEm}" style="max-width:240px"></label>
          ${estado.realizadoEm && ehEntrega() && estado.entregadorId && estado.entregadorId !== 'DONO' ? html`
            <label class="row" style="gap:6px;font-size:.85rem"><input type="checkbox" data-campo="motoboyPago" ${estado.motoboyJaPago ? 'checked' : ''}
              style="width:auto"> O motoboy já recebeu esta entrega (pago por fora)</label>` : ''}
          <p class="muted" style="margin:4px 0 0;font-size:.8rem">Até 31 dias atrás. Entra já entregue, sem cozinha e sem promoção,
            e baixa o estoque mesmo que o sistema esteja sem saldo. Deixe em branco para venda de agora.</p>
        </details>` : ''}
      <div>
        <div class="muted" style="font-size:.8rem;margin-bottom:4px">Pagamento</div>
        <div class="segmented">
          ${formasDoPerfil().map((f) => html`<button data-acao="forma" data-v="${f}" class="${f === estado.forma ? 'on' : ''}">${ROTULO_BOTAO[f] ?? rotulo(f)}</button>`)}
        </div>
      </div>
      ${estado.forma !== 'CORTESIA' ? html`
        <label class="row" style="flex-wrap:nowrap;align-items:center;gap:8px;font-size:.85rem">Desconto
          <input data-campo="desconto" value="${estado.desconto}" inputmode="decimal" autocomplete="off" style="max-width:130px"
            placeholder="R$ ou %" aria-label="Desconto em reais ou porcentagem (ex.: 5 ou 10%)">
          <span class="muted" data-desconto>${valorDesconto() ? `- ${moeda(valorDesconto())}` : ''}</span></label>` : ''}
      ${dividido() ? painelDivisao() : ''}
      ${estado.forma === 'CORTESIA' ? html`
        <input name="motivoCortesia" maxlength="150" autocomplete="off" placeholder="Para quem / por quê (ex.: cliente fiel, aniversário)" aria-label="Motivo da cortesia">
        <p class="muted" style="margin:0;font-size:.8rem">Sai do estoque e vai para a cozinha normalmente, sem cobrar nada
          (nem a entrega). O custo aparece no Financeiro e a lista fica em Estoque → Cortesias.</p>` : ''}
      ${estado.forma === 'DINHEIRO' || (dividido() && estado.partes.some((p) => p.forma === 'DINHEIRO')) ? html`
        <div class="row" style="flex-wrap:nowrap;align-items:center">
          <input name="valorRecebido" inputmode="decimal" autocomplete="off" style="max-width:170px"
                 placeholder="${ehEntrega() ? 'Troco para (R$)' : dividido() ? 'Entregou em dinheiro (R$)' : 'Cliente entregou (R$)'}" aria-label="Valor recebido">
          <span class="troco" data-troco></span>
        </div>` : ''}`;
  }

  function painelDivisao() {
    return html`
      <div class="card stack" style="padding:10px;gap:8px">
        <div class="row" style="flex-wrap:nowrap;align-items:center;gap:6px;font-size:.85rem">
          Dividir igual em
          <input data-campo="pessoas" type="number" min="2" max="10" value="${estado.pessoas}" style="width:64px" aria-label="Quantas partes">
          <button type="button" class="btn btn-sm" data-acao="dividir-igual">Dividir</button>
        </div>
        ${estado.partes.map((p, i) => html`
          <div class="row" style="flex-wrap:nowrap;gap:6px">
            <select data-parte-forma="${i}" aria-label="Forma da parte ${i + 1}">
              ${FORMAS.map((f) => html`<option value="${f}" ${f === p.forma ? 'selected' : ''}>${rotulo(f)}</option>`)}
            </select>
            <input data-parte-valor="${i}" value="${p.valor}" inputmode="decimal" autocomplete="off" style="max-width:110px"
              aria-label="Valor da parte ${i + 1}">
            <button type="button" class="btn btn-icon btn-sm" data-acao="remover-parte" data-i="${i}" aria-label="Tirar esta parte"
              ${estado.partes.length <= 2 ? 'disabled' : ''}>✕</button>
          </div>`)}
        <div class="row" style="align-items:center">
          <button type="button" class="btn btn-sm" data-acao="add-parte" ${estado.partes.length >= 10 ? 'disabled' : ''}>+ Parte</button>
          <span class="spacer"></span><span data-falta style="font-size:.85rem">${faltaHtml()}</span>
        </div>
      </div>`;
  }

  function faltaHtml() {
    const diferenca = centavos(total() - somaPartes());
    if (Math.abs(diferenca) < 0.005) return html`<span class="good">✓ fecha ${moeda(total())}</span>`;
    return diferenca > 0 ? html`<span class="bad">Falta ${moeda(diferenca)}</span>` : html`<span class="bad">Passou ${moeda(-diferenca)}</span>`;
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
    // Enquanto ninguém mexeu nos valores, as partes acompanham o total (item novo, desconto, entrega)
    if (dividido() && (!estado.partes.length || estado.partesAuto)) dividirIgual(estado.partes.length || estado.pessoas);
    const porCategoria = ORDEM_CATEGORIAS
      .map((cat) => [cat, estado.produtos.filter((p) => p.categoria === cat)])
      .filter(([, lista]) => lista.length);

    const cardapio = porCategoria.length ? porCategoria.map(([, lista]) => html`
      <div data-categoria>
      <h3 class="categoria-titulo">${lista[0].categoriaRotulo}</h3>
      <div class="produtos">
        ${lista.map((p) => {
          const noCarrinho = estado.itens.get(p.id) || 0;
          const restante = p.unidadesDisponiveis - noCarrinho;
          // 999 = só insumos sem trava (óleo): não há limite para mostrar
          const aviso = restante <= 0 ? (p.limitadoPor ? `Falta ${p.limitadoPor}` : 'Sem estoque')
            : p.unidadesDisponiveis >= 999 ? '' : `dá para fazer ${restante}`;
          return html`
            <button class="produto ${noCarrinho ? 'no-carrinho' : ''}" data-acao="add" data-id="${p.id}"
              data-nome="${semAcento(`${p.nome} ${p.categoriaRotulo}`)}" ${restante <= 0 ? 'disabled' : ''}
              ${restante <= 0 && p.limitadoPor ? html`title="Lance a compra ou a contagem de ${p.limitadoPor}"` : ''}>
              ${noCarrinho ? html`<span class="produto-qtd">${noCarrinho}</span>` : ''}
              <strong>${p.nome}</strong>
              <small>${aviso}</small>
              <span class="preco">${moeda(p.precoVenda)}</span>
            </button>`;
        })}
      </div>
      </div>`) : html`<div class="card empty">Nenhum produto ativo. Cadastre o cardápio primeiro.</div>`;

    const busca = html`
      <div class="pdv-busca">
        <span aria-hidden="true">🔍</span>
        <input type="search" data-campo="busca" value="${estado.busca}" placeholder="Buscar no cardápio (ex.: batata)"
          aria-label="Buscar no cardápio" autocomplete="off" enterkeyhint="search">
        <button type="button" class="btn btn-sm" data-acao="limpar-busca" aria-label="Limpar busca" ${estado.busca ? '' : 'hidden'}>✕</button>
      </div>
      <div class="card empty" data-busca-vazia hidden>Nenhum item com esse nome.</div>`;

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
        <section class="pdv-cardapio">${busca}${cardapio}</section>
        <button type="button" class="lupa-flutuante so-celular" data-acao="abrir-busca" aria-label="Buscar no cardápio">🔍</button>
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
            <div class="cupom-linha ${valorDesconto() ? '' : 'hidden'}" data-linha-desconto><span>Desconto</span>
              <span class="num" data-desconto>- ${moeda(valorDesconto())}</span></div>
            <div class="cupom-linha"><span>Entrega${bairro() ? ` (${bairro().nome})` : ''}</span>
              <span class="num">${!bairro() ? 'escolha o bairro' : estado.gratis ? 'grátis' : moeda(taxaEntrega())}</span></div>
            ${estado.gratis && bairro() && estado.entregadorId !== 'DONO' ? html`<p class="muted" style="margin:0;font-size:.78rem">
              A loja paga ${moeda(bairro().taxaEntrega)} ao motoboy por esta entrega.</p>` : ''}` : ''}
          ${!ehEntrega() && !naComanda ? html`
            <div class="${valorDesconto() ? '' : 'hidden'}" data-linha-desconto>
              <div class="cupom-linha"><span>Itens</span><span class="num">${moeda(totalItens())}</span></div>
              <div class="cupom-linha"><span>Desconto</span><span class="num" data-desconto>- ${moeda(valorDesconto())}</span></div>
            </div>` : ''}
          <div class="total"><span>Total</span><span class="num" data-total>${moeda(total())}</span></div>
          <button class="btn btn-primary btn-block" data-acao="lancar" ${podeEnviar ? '' : 'disabled'}>
            ${estado.enviando ? 'Enviando…' : naComanda ? '🍳 Enviar para a cozinha' : 'Lançar pedido'}
          </button>
          ${estado.itens.size ? html`<button class="btn btn-block" style="margin-top:8px" data-acao="limpar">Limpar</button>` : ''}
          ${painelUltimo()}
        </aside>
        <button class="barra-pedido so-celular" data-acao="gaveta" ${estado.itens.size || estado.ultimo ? '' : 'hidden'}>
          <span>${qtdItens() ? `${qtdItens()} ${qtdItens() > 1 ? 'itens' : 'item'}` : 'Ver pedido'}</span>
          <strong class="num" data-total>${moeda(total())}</strong>
        </button>
      </div>`);
    atualizarTroco();
  }

  function atualizarTroco() {
    const alvo = container.querySelector('[data-troco]');
    const campo = container.querySelector('[name=valorRecebido]');
    if (alvo && campo) alvo.innerHTML = String(trocoHtml(emDinheiro(), decimal(campo.value)));
  }

  // Preserva o que foi digitado nos campos entre redesenhos
  function lerCampos() {
    const f = container.querySelector('#dados-cliente');
    return f ? Object.fromEntries(new FormData(f)) : {};
  }
  function redesenhar() {
    const dados = lerCampos();
    desenhar();
    filtrar();
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
    const outroDia = !naComanda && estado.realizadoEm;
    if (ehEntrega() && ((!outroDia && !campos.clienteTelefone?.trim()) || !campos.enderecoEntrega?.trim() || !estado.bairroId)) {
      toast(outroDia ? 'Para entrega, informe endereço e bairro.' : 'Para entrega, informe telefone, endereço e bairro.', 'erro');
      return;
    }
    if (cortesia() && !campos.motivoCortesia?.trim()) {
      toast('Na cortesia, informe para quem foi ou o motivo.', 'erro');
      return;
    }
    if (!naComanda && dividido() && Math.abs(total() - somaPartes()) >= 0.005) {
      toast(`As partes somam ${moeda(somaPartes())}, mas o total é ${moeda(total())}.`, 'erro');
      return;
    }
    const recebido = !naComanda && emDinheiro() > 0 ? decimal(campos.valorRecebido) : null;
    if (recebido != null && recebido < emDinheiro() - 0.004) {
      toast(`O valor recebido é menor que o ${dividido() ? 'que é pago em dinheiro' : 'total'} (${moeda(emDinheiro())}).`, 'erro');
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
          desconto: valorDesconto() || null,
          pagamentos: dividido() ? estado.partes.map((p) => ({ forma: p.forma, valor: decimal(p.valor) })) : null,
          entrega: ehEntrega() ? {
            bairroId: estado.bairroId, referencia: campos.referencia || null, gratis: estado.gratis,
            entregadorId: estado.entregadorId === 'DONO' ? null : estado.entregadorId, peloDono: estado.entregadorId === 'DONO',
            motoboyJaPago: Boolean(outroDia && estado.motoboyJaPago && estado.entregadorId && estado.entregadorId !== 'DONO'),
          } : null,
          realizadoEm: outroDia ? `${estado.realizadoEm}:00` : null,
          semPromocao: outroDia ? true : estado.semPromocao,
        });
      const pedido = await api.post('/pedidos', corpo);
      estado.ultimo = pedido;
      estado.itens.clear();
      estado.canal = 'BALCAO';
      estado.entrega = false;
      estado.bairroId = null;
      estado.gratis = gratisHoje();
      estado.semPromocao = false;
      estado.desconto = '';
      estado.partes = [];
      estado.partesAuto = true;
      if (estado.forma === 'DIVIDIDO') estado.forma = 'PIX';
      toast(outroDia ? `Pedido #${pedido.id} lançado no dia ${new Date(pedido.criadoEm).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })}`
        : pedido.status === 'ENTREGUE'
        ? `Pedido #${pedido.id} registrado: só bebida, sai direto do balcão${pedido.comanda ? ` (na conta de ${pedido.comanda})` : ''}`
        : `Pedido #${pedido.id} enviado para a cozinha${pedido.comanda ? ` (${pedido.comanda})` : ''}`);
      await carregar(); // atualiza "dá para fazer N" e o consumo da comanda
      estado.enviando = false;
      estado.busca = ''; // próximo pedido começa com o cardápio inteiro
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
    'dividir-igual': () => {
      dividirIgual(Number(container.querySelector('[data-campo=pessoas]')?.value) || estado.pessoas);
      redesenhar();
    },
    'add-parte': () => {
      const falta = Math.max(centavos(total() - somaPartes()), 0);
      estado.partes.push({ forma: 'PIX', valor: falta.toFixed(2).replace('.', ',') });
      estado.partesAuto = false;
      redesenhar();
    },
    'remover-parte': (el) => {
      estado.partes.splice(Number(el.dataset.i), 1);
      estado.partesAuto = false;
      redesenhar();
    },
    limpar: () => { estado.itens.clear(); redesenhar(); },
    gaveta: () => { estado.gavetaAberta = !estado.gavetaAberta; redesenhar(); },
    'abrir-busca': () => {
      const campo = container.querySelector('[data-campo="busca"]');
      campo.scrollIntoView({ block: 'center' });
      campo.focus();
      campo.select();
    },
    'limpar-busca': () => {
      estado.busca = '';
      const campo = container.querySelector('[data-campo="busca"]');
      campo.value = '';
      filtrar();
      campo.focus();
    },
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
    if (campo === 'realizado') { estado.realizadoEm = ev.target.value; redesenhar(); }
    if (campo === 'motoboyPago') estado.motoboyJaPago = ev.target.checked;
    if (ev.target.dataset.parteForma != null) {
      estado.partes[Number(ev.target.dataset.parteForma)].forma = ev.target.value;
      redesenhar(); // mostra ou esconde o campo do dinheiro entregue
    }
    if (ev.target.name === 'clienteTelefone') buscarCliente(ev.target.value);
  };
  // Digitar atualiza totais e a conferência das partes sem redesenhar (o cursor continua no campo)
  const aoDigitar = (ev) => {
    const alvo = ev.target;
    if (alvo.dataset.campo === 'busca') {
      estado.busca = alvo.value;
      filtrar();
      return;
    }
    if (alvo.dataset.campo === 'desconto') {
      estado.desconto = alvo.value;
      container.querySelectorAll('[data-total]').forEach((el) => { el.textContent = moeda(total()); });
      container.querySelectorAll('[data-desconto]').forEach((el) => { el.textContent = valorDesconto() ? `- ${moeda(valorDesconto())}` : ''; });
      container.querySelectorAll('[data-linha-desconto]').forEach((el) => el.classList.toggle('hidden', !valorDesconto()));
      if (dividido() && estado.partesAuto) {
        dividirIgual(estado.partes.length);
        container.querySelectorAll('[data-parte-valor]').forEach((el) => { el.value = estado.partes[Number(el.dataset.parteValor)].valor; });
      }
    }
    if (alvo.dataset.campo === 'pessoas') estado.pessoas = Number(alvo.value) || estado.pessoas;
    if (alvo.dataset.parteValor != null) {
      estado.partes[Number(alvo.dataset.parteValor)].valor = alvo.value;
      estado.partesAuto = false;
    }
    const falta = container.querySelector('[data-falta]');
    if (falta) falta.innerHTML = String(faltaHtml());
    atualizarTroco();
  };
  container.addEventListener('change', aoMudarCampo);
  container.addEventListener('input', aoDigitar);
  // Enter num campo não pode "enviar" o formulário (recarregaria a página)
  const semSubmit = (ev) => ev.preventDefault();
  container.addEventListener('submit', semSubmit);
  // Na busca: Enter com um só resultado já adiciona; Esc limpa
  const teclaBusca = (ev) => {
    if (ev.target.dataset?.campo !== 'busca') return;
    if (ev.key === 'Escape') container.querySelector('[data-acao="limpar-busca"]')?.click();
    if (ev.key !== 'Enter') return;
    ev.preventDefault();
    const visiveis = [...container.querySelectorAll('[data-nome]:not([hidden]):not([disabled])')];
    if (visiveis.length === 1) visiveis[0].click();
  };
  container.addEventListener('keydown', teclaBusca);

  await carregar();
  estado.gratis = gratisHoje();
  desenhar();
  return () => {
    soltar();
    container.removeEventListener('change', aoMudarCampo);
    container.removeEventListener('input', aoDigitar);
    container.removeEventListener('submit', semSubmit);
    container.removeEventListener('keydown', teclaBusca);
  };
}
