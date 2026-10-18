import { api, sessao } from '../api.js';
import { abrirImportacao } from './nota-fiscal.js';
import { aoClicar, campoParcelas, dataHora, decimal, hojeISO, html, ligarParcelas, modal, moeda, numero, parcelasDe, pct, renderizar, rotulo, toast, toastErro } from '../ui.js';
import { abasEstoque, compras, inventario, LOCAIS, painel, seloStatus, sugestaoDeCompra, textoDaLista } from './estoque-abas.js';
import { ativarTooltips } from './potes.js';

const UNIDADES = ['QUILOGRAMA', 'LITRO', 'UNIDADE', 'GRAMA', 'MILILITRO'];

// Plural só para exibição
export const plural = (nome, qtd) => (Number(qtd) > 1 && /[aeiou]$/i.test(nome) ? `${nome}s` : nome);

/** Saldo traduzido: "≈ 1,5 × Saco 6 kg · 216 iscas" */
export function equivalencias(i, quantidade = i.estoqueAtual) {
  const partes = [];
  const principal = i.embalagens?.[0];
  if (principal) partes.push(`≈ ${numero(quantidade / principal.conteudo, 1)} × ${principal.nome}`);
  if (i.unidadeUsoNome) {
    const n = Math.floor(Number(quantidade) * Number(i.unidadeUsoPorUnidade) + 1e-6);
    partes.push(`${n} ${plural(i.unidadeUsoNome, n)}`);
  }
  return partes.join(' · ');
}

/**
 * Lucro que cada produto deixa por unidade do insumo (por kg de frango, por exemplo).
 * lucro da porção = preço − custo de TODOS os insumos da ficha (frango, batata, óleo, embalagem...)
 * porções por kg  = 1 ÷ quanto a porção usa deste insumo (12 iscas de um pacote de 12 = 1 porção)
 * custoKg (opcional) simula outro preço de compra no lugar do custo médio atual.
 */
export function margensDoInsumo(i, produtos, custoKg = null) {
  return produtos.filter((p) => p.ativo).flatMap((p) => p.fichaTecnica
    .filter((f) => f.insumoId === i.id)
    .map((f) => {
      const usa = Number(f.quantidade);
      const custoPorcao = custoKg == null ? Number(p.custoAtual)
        : Number(p.custoAtual) - Number(f.custo) + usa * custoKg;
      const lucro = Number(p.precoVenda) - custoPorcao;
      const porcoes = 1 / usa;
      return {
        produto: p.nome, descricao: f.descricao, preco: Number(p.precoVenda), custoPorcao, custoInsumo: usa * (custoKg ?? Number(i.custoUnitario)),
        lucro, margem: (lucro / Number(p.precoVenda)) * 100, porcoes, lucroPorUnidade: lucro * porcoes,
        porcoesNoEstoque: Math.floor(Number(i.estoqueAtual) / usa + 1e-6), usa,
        // "lucro por kg" só faz sentido para o ingrediente principal (frango), não para o sal
        principal: Number(f.custo) >= Math.max(...p.fichaTecnica.map((x) => Number(x.custo))),
      };
    }))
    .sort((a, b) => b.lucroPorUnidade - a.lucroPorUnidade);
}

const faixa = (lista, campo) => {
  if (!lista.length) return '';
  const min = Math.min(...lista.map((m) => m[campo]));
  const max = Math.max(...lista.map((m) => m[campo]));
  return Math.abs(max - min) < 0.005 ? moeda(max) : `${moeda(min)} a ${moeda(max)}`;
};

const faixaPct = (lista) => {
  const min = Math.min(...lista.map((m) => m.margem));
  const max = Math.max(...lista.map((m) => m.margem));
  return Math.abs(max - min) < 0.5 ? pct(max) : `${pct(min)} a ${pct(max)}`;
};

const SEM_GRUPO = 'Sem grupo';
const SUGESTOES_GRUPO = ['Carnes', 'Congelados', 'Refrigerantes', 'Cervejas', 'Águas', 'Sucos', 'Óleos e molhos',
  'Embalagens', 'Limpeza', 'Descartáveis'];

export async function montar(container, params = {}) {
  const admin = sessao.admin();
  // Atendente vê só a lista de itens; o dono abre na visão geral.
  const aba = admin ? (['painel', 'itens', 'compras', 'inventario'].includes(params.aba) ? params.aba : 'painel') : 'itens';
  let indicadores = new Map();
  let lotes = [];
  let margens = [];
  let furo = [];
  let diasCompra = 7;
  let diasFuro = 30;
  let localContagem = '';
  let insumos = [];
  let produtos = [];
  let fornecedores = [];
  let reposicao = new Map();
  const filtro = { texto: '', grupo: '', local: '', repor: false, chegou: false };
  let chegadas = new Map();

  async function carregar() {
    let paraRepor;
    let ultimas;
    [insumos, produtos, fornecedores, paraRepor, ultimas] = await Promise.all([
      api.get('/insumos'), api.get('/produtos'), admin ? api.get('/fornecedores') : [],
      admin ? api.get('/insumos/reposicao') : [], api.get('/insumos/ultimas-entradas'),
    ]);
    reposicao = new Map(paraRepor.map((r) => [r.insumoId, r]));
    chegadas = new Map(ultimas.map((e) => [e.insumoId, e]));
    if (admin) {
      const [ind, extras] = await Promise.all([api.get('/insumos/indicadores?dias=30'), carregarDaAba()]);
      indicadores = new Map(ind.map((x) => [x.insumoId, x]));
      [lotes, margens, furo] = extras;
    }
    desenhar();
  }

  /** O que só a visão geral e o inventário usam: carregado só quando a aba está aberta. */
  async function carregarDaAba() {
    const desde = (n) => { const d = new Date(`${hojeISO()}T12:00:00`); d.setDate(d.getDate() - (n - 1)); return d.toISOString().slice(0, 10); };
    if (aba === 'painel') {
      return Promise.all([api.get('/insumos/validades'), api.get('/painel/margens'),
        api.get(`/insumos/furo?inicio=${desde(30)}&fim=${hojeISO()}`)]);
    }
    if (aba === 'inventario') {
      return Promise.all([api.get('/insumos/validades'), [], api.get(`/insumos/furo?inicio=${desde(diasFuro)}&fim=${hojeISO()}`)]);
    }
    return [[], [], []];
  }

  const grupos = () => [...new Set(insumos.map((i) => i.grupo || SEM_GRUPO))]
    .sort((a, b) => (a === SEM_GRUPO) - (b === SEM_GRUPO) || a.localeCompare(b, 'pt-BR'));
  const marcas = () => [...new Set(insumos.map((i) => i.marca).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'pt-BR'));
  const semAcento = (x) => String(x ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();

  const insumo = (id) => insumos.find((i) => i.id === Number(id));

  function desenhar() {
    const alertas = insumos.filter((i) => i.ativo && i.abaixoDoMinimo);
    const valorTotal = insumos.reduce((soma, i) => soma + Number(i.valorEmEstoque ?? 0), 0);
    const totalRepor = [...reposicao.values()].reduce((soma, r) => soma + Number(r.valor), 0);
    if (aba !== 'itens') {
      desenharAba(valorTotal, totalRepor);
      return;
    }
    renderizar(container, html`
      ${admin ? abasEstoque(aba) : ''}
      <div class="page-head">
        <div>
          <h1>Estoque de insumos</h1>
          <p>${insumos.length} insumos${admin ? ` · ${moeda(valorTotal)} parados em mercadoria` : ''}${admin && totalRepor
            ? html` · <strong>${moeda(totalRepor)}</strong> para separar e repor o que foi vendido` : ''}${alertas.length
            ? html` · <span class="bad">⚠️ ${alertas.length} abaixo do mínimo</span>` : ''}</p>
        </div>
        <div class="row">
          <button class="btn" data-acao="consumo">🍽️ Consumo da equipe</button>
          ${admin ? html`<button class="btn" data-acao="cortesias">🎁 Cortesias</button>
            <button class="btn" data-acao="nota">📷 Importar nota ou cupom</button>
            <button class="btn btn-primary" data-acao="novo">+ Novo insumo</button>` : ''}
        </div>
      </div>
      <div class="filtros">
        <input type="search" data-filtro="texto" placeholder="Buscar: coca, 600 ml, heineken…" value="${filtro.texto}" aria-label="Buscar insumo">
        <select data-filtro="grupo" style="width:auto" aria-label="Grupo">
          <option value="">Todos os grupos</option>
          ${grupos().map((g) => html`<option value="${g}" ${filtro.grupo === g ? 'selected' : ''}>${g}</option>`)}
        </select>
        <label class="row" style="gap:6px;font-size:.9rem"><input type="checkbox" data-filtro="repor" ${filtro.repor ? 'checked' : ''} style="width:auto"> Só o que precisa repor</label>
        <select data-filtro="local" style="width:auto" aria-label="Local">
          <option value="">Todos os locais</option>
          ${[...new Set(insumos.map((i) => i.local).filter(Boolean))].sort().map((l) => html`<option ${filtro.local === l ? 'selected' : ''}>${l}</option>`)}
        </select>
        <label class="row" style="gap:6px;font-size:.9rem"><input type="checkbox" data-filtro="chegou" ${filtro.chegou ? 'checked' : ''} style="width:auto"> Só o que chegou (3 dias)</label>
        <button class="btn btn-sm" data-acao="historico-consumo">Consumo da equipe (30 dias)</button>
      </div>
      <div class="card table-wrap" data-tabela></div>`);
    desenharTabela();
  }

  function desenharAba(valorTotal, totalRepor) {
    const titulo = { painel: 'Visão geral do estoque', compras: 'Compras', inventario: 'Inventário' }[aba];
    const conteudo = aba === 'painel' ? painel({ insumos, indicadores, lotes, margens, furo, totalRepor })
      : aba === 'compras' ? compras({ sugestao: sugestaoDeCompra(insumos, indicadores, diasCompra), paraDias: diasCompra })
        : inventario({ insumos, localContagem, furo, diasFuro, lotes });
    renderizar(container, html`
      ${abasEstoque(aba)}
      <div class="page-head"><div><h1>${titulo}</h1>
        <p>${insumos.filter((i) => i.ativo).length} itens ativos · ${moeda(valorTotal)} em mercadoria</p></div></div>
      ${conteudo}`);
  }

  function filtrados() {
    const termo = semAcento(filtro.texto);
    return insumos.filter((i) => (!filtro.grupo || (i.grupo || SEM_GRUPO) === filtro.grupo)
      && (!filtro.repor || (i.ativo && i.abaixoDoMinimo))
      && (!filtro.chegou || chegadas.has(i.id))
      && (!filtro.local || i.local === filtro.local)
      && (!termo || semAcento(`${i.nome} ${i.marca ?? ''} ${i.grupo ?? ''}`).includes(termo)));
  }

  function linhaInsumo(i) {
    return html`
      <tr class="${i.ativo ? '' : 'inativo'}">
        <td><strong>${i.nome}</strong> ${i.marca ? html`<span class="badge">${i.marca}</span>` : ''} ${i.local ? html`<span class="badge">📍 ${i.local}</span>` : ''} ${!i.ativo ? html`<span class="badge">inativo</span>` : ''}
          ${i.embalagens.length || i.unidadeUsoNome ? html`<div class="muted" style="font-size:.78rem">
            ${i.embalagens.map((e) => e.nome).join(' · ')}${i.embalagens.length && i.unidadeUsoNome ? ' · ' : ''}${i.unidadeUsoNome
              ? `${numero(i.unidadeUsoPorUnidade, 2)} ${plural(i.unidadeUsoNome, 2)}/${i.sigla}` : ''}</div>` : ''}</td>
        <td class="num right">${numero(i.estoqueAtual)} ${i.sigla}
          ${i.ativo && i.abaixoDoMinimo ? html` <span class="badge badge-bad">⚠ repor</span>` : ''}
          ${i.ativo && indicadores.has(i.id) ? html`<div>${seloStatus(indicadores.get(i.id).status)}${indicadores.get(i.id).coberturaDias != null
            ? html` <span class="sub">dá para ${numero(indicadores.get(i.id).coberturaDias, 1)} dia(s)</span>` : ''}</div>` : ''}
          <div class="sub">mín. ${numero(i.estoqueMinimo)} ${i.sigla}${equivalencias(i) ? ` · ${equivalencias(i)}` : ''}</div>
          ${chegadas.has(i.id) ? linhaChegada(chegadas.get(i.id), i.sigla) : ''}</td>
        ${admin ? colunasDeCusto(i) : ''}
        <td class="right acoes">
          ${admin ? html`
            <button class="btn btn-sm" data-acao="entrada" data-id="${i.id}" title="Registrar compra">📥 Entrada</button>
            <button class="btn btn-sm" data-acao="editar" data-id="${i.id}">Editar</button>
            <button class="btn btn-sm btn-icon" data-acao="ajuste" data-id="${i.id}" title="Contagem (inventário)" aria-label="Contagem">📋</button>
            <button class="btn btn-sm btn-icon" data-acao="duplicar" data-id="${i.id}" title="Criar outro parecido (outro sabor ou tamanho)" aria-label="Duplicar">⧉</button>` : ''}
          <button class="btn btn-sm btn-icon" data-acao="rendimento" data-id="${i.id}" title="Quanto rende" aria-label="Quanto rende">📊</button>
          <button class="btn btn-sm btn-icon" data-acao="extrato" data-id="${i.id}" title="Extrato" aria-label="Extrato">🧾</button>
        </td>
      </tr>`;
  }

  function desenharTabela() {
    const lista = filtrados();
    const colunas = admin ? 6 : 3;
    const porGrupo = grupos().map((g) => [g, lista.filter((i) => (i.grupo || SEM_GRUPO) === g)]).filter(([, l]) => l.length);
    renderizar(container.querySelector('[data-tabela]'), lista.length ? html`
      <table>
        <thead><tr>
          <th>Insumo</th><th class="right">Saldo</th>
          ${admin ? html`<th class="right">Custo médio</th>
            <th class="right" title="Quanto sobra de lucro para cada kg/un deste insumo que vira produto">Lucro por kg / un</th>
            <th class="right" title="Custo do que foi vendido desde a última compra deste item. Zera ao registrar uma nova entrada.">Separar p/ repor</th>` : ''}<th></th>
        </tr></thead>
        <tbody>${porGrupo.map(([g, itens]) => html`
          ${porGrupo.length > 1 || g !== SEM_GRUPO ? html`<tr class="grupo-linha"><td colspan="${colunas}">${g}
            <span class="muted" style="font-weight:400">· ${itens.length} ${itens.length > 1 ? 'itens' : 'item'}${admin
              ? ` · ${moeda(itens.reduce((soma, i) => soma + Number(i.valorEmEstoque), 0))}` : ''}${admin && itens.some((i) => reposicao.has(i.id))
              ? ` · ${moeda(itens.reduce((soma, i) => soma + Number(reposicao.get(i.id)?.valor ?? 0), 0))} a separar` : ''}${itens.some((i) => i.ativo && i.abaixoDoMinimo)
              ? ` · ${itens.filter((i) => i.ativo && i.abaixoDoMinimo).length} a repor` : ''}</span></td></tr>` : ''}
          ${itens.map(linhaInsumo)}`)}
        </tbody>
      </table>` : html`<div class="empty">${insumos.length ? 'Nenhum insumo com esse filtro.' : 'Nenhum insumo cadastrado.'}</div>`);
  }

  function colunasDeCusto(i) {
    const margens = margensDoInsumo(i, produtos).filter((m) => m.principal);
    return html`
      <td class="num right">${moeda(i.custoUnitario)}/${i.sigla}<div class="sub">${moeda(i.valorEmEstoque)} em estoque</div></td>
      <td class="num right">${margens.length ? html`<strong class="${margens.some((m) => m.lucro < 0) ? 'bad' : ''}">${faixa(margens, 'lucroPorUnidade')}</strong>/${i.sigla}
        <div class="sub" title="${margens.length} produto(s) usam este insumo">margem ${faixaPct(margens)} · estoque rende ${faixa(margens.map((m) => ({ v: m.porcoesNoEstoque * m.lucro })), 'v')}</div>`
        : html`<span class="muted" title="Não é o ingrediente principal de nenhum produto">—</span>`}</td>
      ${celulaReposicao(i)}`;
  }

  /** O que entrou por último e de onde veio (cupom, nota, compra sem nota). */
  function linhaChegada(e, sigla) {
    const dia = new Date(e.quando);
    const hoje = new Date();
    const ontem = new Date(Date.now() - 86_400_000);
    const quando = dia.toDateString() === hoje.toDateString() ? 'hoje'
      : dia.toDateString() === ontem.toDateString() ? 'ontem' : dia.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
    return html`<div class="sub good" title="${e.origem || 'Entrada de estoque'}">📥 +${numero(e.quantidade)} ${sigla} ${quando}${e.origem
      ? ` · ${e.origem.length > 32 ? `${e.origem.slice(0, 32)}…` : e.origem}` : ''}</div>`;
  }

  function celulaReposicao(i) {
    const r = reposicao.get(i.id);
    return html`<td class="num right">${r ? html`<strong>${moeda(r.valor)}</strong>
      <div class="sub" title="Vendido desde a última compra deste item">${numero(r.vendido)} ${i.sigla} vendidos</div>`
      : html`<span class="muted">—</span>`}</td>`;
  }

  /** Produtos cuja ficha técnica tem só este insumo. */
  const soDesteInsumo = (i) => produtos.filter((p) => p.fichaTecnica.length === 1 && p.fichaTecnica[0].insumoId === i.id);

  /** Revenda: cada venda baixa 1 unidade do insumo (bebida, item pronto). Iscas por kg não entram aqui. */
  const produtoDeRevenda = (i) => (i.sigla === 'un'
    ? soDesteInsumo(i).find((p) => Number(p.fichaTecnica[0].quantidade) === 1) : undefined);

  function linhaEmbalagem(e, sigla) {
    return html`
      <div class="ficha-linha" data-embalagem style="grid-template-columns:1fr 130px 32px">
        <input type="hidden" name="embId" value="${e?.id ?? ''}">
        <input name="embNome" placeholder="Ex.: Saco 2 kg" maxlength="50" value="${e?.nome ?? ''}" aria-label="Nome da embalagem">
        <input name="embConteudo" inputmode="decimal" placeholder="Conteúdo (${sigla})" value="${e ? numero(e.conteudo) : ''}" aria-label="Conteúdo">
        <button type="button" class="btn btn-icon" data-remover-emb aria-label="Remover embalagem">✕</button>
      </div>`;
  }

  function formInsumo(i, novo = !i) {
    const sigla = i?.sigla ?? 'kg';
    return html`
      <div class="form-grid">
        <label class="field" style="grid-column:1/-1">Nome<input name="nome" required maxlength="100" value="${i?.nome ?? ''}" placeholder="Ex.: Coca-Cola Zero 2 L"></label>
        <label class="field">Grupo<input name="grupo" maxlength="40" list="lista-grupos" value="${i?.grupo ?? ''}" placeholder="Ex.: Refrigerantes"></label>
        <label class="field">Marca<input name="marca" maxlength="50" list="lista-marcas" value="${i?.marca ?? ''}" placeholder="Ex.: Coca-Cola"></label>
        <label class="field">Local<input name="local" maxlength="40" list="lista-locais" value="${i?.local ?? ''}" placeholder="Ex.: Freezer"></label>
        <datalist id="lista-locais">${[...new Set([...LOCAIS, ...insumos.map((x) => x.local).filter(Boolean)])].map((l) => html`<option value="${l}">`)}</datalist>
        <datalist id="lista-grupos">${[...new Set([...grupos().filter((g) => g !== SEM_GRUPO), ...SUGESTOES_GRUPO])].map((g) => html`<option value="${g}">`)}</datalist>
        <datalist id="lista-marcas">${marcas().map((m) => html`<option value="${m}">`)}</datalist>
        <label class="field">Controlar estoque em
          <select name="unidade">${UNIDADES.map((u) => html`<option value="${u}" ${i?.unidade === u ? 'selected' : ''}>${rotulo(u)}</option>`)}</select>
        </label>
        <label class="field"><span>Estoque mínimo (<span data-sigla>${sigla}</span>)</span><input name="estoqueMinimo" required inputmode="decimal" value="${i ? numero(i.estoqueMinimo) : '0'}">
          ${i && Number(indicadores.get(i.id)?.minimoSugerido) > 0 ? html`<button type="button" class="btn btn-sm" data-minimo-sugerido="${numero(indicadores.get(i.id).minimoSugerido)}"
            title="3 dias do consumo médio dos últimos 30 dias">Sugerido: ${numero(indicadores.get(i.id).minimoSugerido)} ${sigla}</button>` : ''}</label>
        <label class="field"><span>Custo por <span data-sigla>${sigla}</span> (R$)</span><input name="custoUnitario" required inputmode="decimal" value="${i ? numero(i.custoUnitario, 6) : ''}"></label>
        <div class="field" style="grid-column:1/-1"><span>Calcular o custo pelo valor pago (opcional)</span>
          <div class="row" style="gap:8px;align-items:center;flex-wrap:wrap">
            Paguei R$ <input name="pagoValor" inputmode="decimal" placeholder="45,00" style="width:110px" aria-label="Valor pago">
            por <input name="pagoQuantidade" inputmode="decimal" placeholder="6" style="width:80px" aria-label="Quantidade comprada"> <span data-sigla>${sigla}</span>
            <span class="muted" data-pago-resultado></span>
          </div></div>
        ${novo ? html`<label class="field"><span>Estoque inicial (<span data-sigla>${sigla}</span>)</span><input name="estoqueInicial" inputmode="decimal" value="0"></label>`
          : html`<label class="field"><span>Em estoque agora (<span data-sigla>${sigla}</span>)</span>
              <input name="saldoAtual" inputmode="decimal" value="${numero(i.estoqueAtual)}" title="Mudou? Fica registrado como contagem no extrato"></label>`}
      </div>

      ${secaoVenda(novo ? null : i)}

      <div>
        <h3>Como chega do fornecedor</h3>
        <p class="muted" style="margin:2px 0 8px;font-size:.85rem">Ex.: "Saco 2 kg" com conteúdo 2. Permite lançar a compra por saco e ver o saldo em sacos.</p>
        <div class="stack" data-embalagens>${(i?.embalagens ?? []).map((e) => linhaEmbalagem(e, sigla))}</div>
        <button type="button" class="btn btn-sm" style="margin-top:8px" data-add-emb>+ Embalagem</button>
      </div>

      <div>
        <h3>Contagem na cozinha (opcional)</h3>
        <p class="muted" style="margin:2px 0 8px;font-size:.85rem">Se você conta este insumo em unidades. Ex.: isca de frango → "isca", 12 por kg. Aí a ficha técnica aceita "12 iscas".</p>
        <div class="form-grid">
          <label class="field">Nome da unidade<input name="unidadeUsoNome" maxlength="30" placeholder="isca" value="${i?.unidadeUsoNome ?? ''}"></label>
          <label class="field"><span>Quantas em 1 <span data-sigla>${sigla}</span></span><input name="unidadeUsoPorUnidade" inputmode="decimal" placeholder="12" value="${i?.unidadeUsoPorUnidade ? numero(i.unidadeUsoPorUnidade, 2) : ''}"></label>
        </div>
      </div>`;
  }

  function secaoVenda(i) {
    const revenda = i ? produtoDeRevenda(i) : null;
    // Ingrediente de prato (frango, batata): o lucro aparece por produto, não por preço do insumo.
    if (!revenda && i && margensDoInsumo(i, produtos).length) {
      return html`<div class="card" style="background:var(--surface-2)">${tabelaLucro(i)}</div>`;
    }
    const usa = revenda ? Number(revenda.fichaTecnica[0].quantidade) : 1;
    return html`
      <div class="card" style="background:var(--surface-2)">
        ${revenda ? html`<strong>Venda no cardápio: ${revenda.nome}</strong>` : html`
          <label class="row" style="gap:8px;font-weight:600"><input type="checkbox" name="vender" style="width:auto">
            Também vender no cardápio (bebida, item de revenda)</label>`}
        <div class="form-grid" style="margin-top:8px">
          <label class="field">Preço de venda (R$)<input name="precoVenda" inputmode="decimal" placeholder="Ex.: 10,00"
            value="${revenda ? numero(revenda.precoVenda, 2) : ''}"></label>
          ${revenda ? '' : html`<label class="field">Categoria<select name="categoriaProduto">${['BEBIDA', 'OUTRO', 'PORCAO'].map((c) => html`<option value="${c}">${rotulo(c)}</option>`)}</select></label>`}
        </div>
        <p style="margin:6px 0 0" data-lucro-unidade data-usa="${usa}"></p>
        ${revenda ? '' : html`<p class="muted" style="margin:6px 0 0;font-size:.8rem">Cria o produto com ficha técnica de 1 unidade: cada venda baixa 1 do estoque.</p>`}
      </div>`;
  }

  function prepararFormInsumo(form) {
    form.querySelector('[data-minimo-sugerido]')?.addEventListener('click', (ev) => {
      form.estoqueMinimo.value = ev.currentTarget.dataset.minimoSugerido;
    });
    const lucro = form.querySelector('[data-lucro-unidade]');
    const atualizarLucro = () => {
      if (!lucro) return;
      const custo = (decimal(form.custoUnitario.value) || 0) * Number(lucro.dataset.usa);
      const preco = decimal(form.precoVenda.value) || 0;
      if (!preco) { lucro.textContent = ''; return; }
      const ganho = preco - custo;
      lucro.innerHTML = String(html`Custo ${moeda(custo)} · lucro por unidade
        <strong class="${ganho < 0 ? 'bad' : 'good'}">${moeda(ganho)}</strong> <span class="muted">(${pct((ganho / preco) * 100)})</span>`);
    };
    const pagoResultado = form.querySelector('[data-pago-resultado]');
    const calcularPago = () => {
      const valor = decimal(form.pagoValor.value);
      const quantidade = decimal(form.pagoQuantidade.value);
      if (!valor || !quantidade) { pagoResultado.textContent = ''; return; }
      const custo = valor / quantidade;
      form.custoUnitario.value = custo.toFixed(4).replace(/0{1,2}$/, '').replace('.', ',');
      pagoResultado.textContent = `= ${moeda(custo)} cada`;
    };
    form.pagoValor.addEventListener('input', calcularPago);
    form.pagoQuantidade.addEventListener('input', calcularPago);
    form.addEventListener('input', atualizarLucro);
    atualizarLucro();

    const lista = form.querySelector('[data-embalagens]');
    const siglaAtual = () => ({ QUILOGRAMA: 'kg', LITRO: 'L', UNIDADE: 'un', GRAMA: 'g', MILILITRO: 'ml' })[form.unidade.value];
    form.unidade.addEventListener('change', () => form.querySelectorAll('[data-sigla]').forEach((s) => { s.textContent = siglaAtual(); }));
    form.querySelector('[data-add-emb]').addEventListener('click', () => {
      lista.insertAdjacentHTML('beforeend', String(linhaEmbalagem(null, siglaAtual())));
      lista.lastElementChild.querySelector('[name=embNome]').focus();
    });
    lista.addEventListener('click', (ev) => {
      if (ev.target.closest('[data-remover-emb]')) ev.target.closest('[data-embalagem]').remove();
    });
  }

  const corpoInsumo = (d, form) => ({
    nome: d.nome, unidade: d.unidade, estoqueMinimo: decimal(d.estoqueMinimo),
    custoUnitario: decimal(d.custoUnitario), estoqueInicial: decimal(d.estoqueInicial),
    unidadeUsoNome: d.unidadeUsoNome?.trim() || null,
    unidadeUsoPorUnidade: d.unidadeUsoNome?.trim() ? decimal(d.unidadeUsoPorUnidade) : null,
    grupo: d.grupo?.trim() || null, marca: d.marca?.trim() || null, local: d.local?.trim() || null,
    embalagens: [...form.querySelectorAll('[data-embalagem]')]
      .map((l) => ({
        id: l.querySelector('[name=embId]').value ? Number(l.querySelector('[name=embId]').value) : null,
        nome: l.querySelector('[name=embNome]').value.trim(),
        conteudo: decimal(l.querySelector('[name=embConteudo]').value),
      }))
      .filter((e) => e.nome || e.conteudo),
  });

  function tabelaLucro(i) {
    const margens = margensDoInsumo(i, produtos);
    if (!margens.length) return '';
    return html`
      <h3 style="margin-bottom:0">Lucro por ${i.sigla} de ${i.nome}</h3>
      <p class="muted" style="margin:0;font-size:.85rem">Custo de 1 ${i.sigla}: <strong>${moeda(i.custoUnitario)}</strong> (média das suas compras).
        Lucro da porção = preço − custo de tudo que vai nela. Comprou mais caro, a margem cai sozinha.</p>
      <div class="table-wrap"><table>
        <thead><tr><th>Produto</th><th class="right">Preço</th><th class="right">Custo da porção</th>
          <th class="right">Lucro por porção</th><th class="right">Porções por ${i.sigla}</th><th class="right">Lucro por ${i.sigla}</th>
          <th class="right">Com o estoque (${numero(i.estoqueAtual)} ${i.sigla})</th></tr></thead>
        <tbody>${margens.map((m) => html`
          <tr>
            <td>${m.produto}<div class="muted" style="font-size:.78rem">usa ${m.descricao} (${moeda(m.custoInsumo)})</div></td>
            <td class="num right">${moeda(m.preco)}</td>
            <td class="num right">${moeda(m.custoPorcao)}</td>
            <td class="num right ${m.lucro < 0 ? 'bad' : ''}"><strong>${moeda(m.lucro)}</strong> <span class="muted">${pct(m.margem)}</span></td>
            <td class="num right">${numero(m.porcoes, 2)}</td>
            <td class="num right"><strong>${moeda(m.lucroPorUnidade)}</strong></td>
            <td class="num right">${m.porcoesNoEstoque} porç. · <strong>${moeda(m.porcoesNoEstoque * m.lucro)}</strong></td>
          </tr>`)}</tbody>
      </table></div>`;
  }

  async function abrirRendimento(i) {
    const r = await api.get(`/produtos/rendimento?insumoId=${i.id}`);
    modal({
      titulo: `Quanto rende${admin ? ' e quanto lucra' : ''}: ${i.nome}`,
      largo: true,
      corpo: html`
        <p class="text-2" style="margin:0">Em estoque: <strong>${numero(r.estoqueAtual)} ${r.sigla}</strong>${equivalencias(i) ? html` (${equivalencias(i)})` : ''}</p>
        ${admin ? tabelaLucro(i) : ''}
        ${admin && r.usos.length ? html`<h3 style="margin-bottom:0">Rendimento por embalagem</h3>` : ''}
        ${r.usos.length ? html`
          <div class="table-wrap"><table>
            <thead><tr><th>Produto</th><th>Usa</th>${i.embalagens.map((e) => html`<th class="right">1 ${e.nome} rende</th>`)}<th class="right">Dá para fazer agora</th></tr></thead>
            <tbody>${r.usos.map((u) => html`
              <tr class="${u.ativo ? '' : 'inativo'}">
                <td>${u.produto}</td>
                <td style="white-space:nowrap">${u.descricao} <span class="muted" style="font-size:.78rem">(= ${numero(u.quantidade, 4)} ${r.sigla})</span></td>
                ${u.rendimentos.map((x) => html`<td class="num right"><strong>${numero(x.rende, 2)}</strong></td>`)}
                <td class="num right">${u.unidadesComEstoqueAtual}</td>
              </tr>`)}</tbody>
          </table></div>
          <p class="muted" style="margin:0;font-size:.85rem">Rendimento com casas decimais (ex.: 6,67) quer dizer que sobra um pedaço: 6 porções completas e um resto.
            Se na prática sai uma porção a mais, ajuste a ficha para "rende 7 por saco".</p>`
          : html`<p class="muted">Nenhum produto usa este insumo ainda.</p>`}`,
    });
  }

  /** Com base, duplica um insumo parecido (ex.: Coca 2 L para Coca 600 ml). */
  function abrirCadastro(base) {
    modal({
      titulo: base ? 'Novo insumo (cópia)' : 'Novo insumo',
      largo: true,
      corpo: formInsumo(base, true),
      aoAbrir: prepararFormInsumo,
      aoSalvar: async (d, form) => {
        if (d.vender === 'on' && !decimal(d.precoVenda)) throw new Error('Informe o preço de venda.');
        const criado = await api.post('/insumos', corpoInsumo(d, form));
        if (d.vender === 'on') {
          await api.post('/produtos', {
            nome: criado.nome, categoria: d.categoriaProduto, descricao: null, precoVenda: decimal(d.precoVenda),
            fichaTecnica: [{ insumoId: criado.id, modo: 'UNIDADE_BASE', quantidade: 1 }],
          });
        }
        toast(d.vender === 'on' ? 'Insumo cadastrado e já está no cardápio' : 'Insumo cadastrado');
        await carregar();
      },
    });
  }

  function opcoesConsumo() {
    const ativos = produtos.filter((p) => p.ativo);
    return html`
      <option value="">Escolha…</option>
      <optgroup label="Pratos do cardápio (baixa pela ficha técnica)">${ativos.filter((p) => p.categoria !== 'BEBIDA')
        .map((p) => html`<option value="p:${p.id}">${p.nome}</option>`)}</optgroup>
      ${grupos().map((g) => html`<optgroup label="${g}">${insumos.filter((i) => i.ativo && (i.grupo || SEM_GRUPO) === g)
        .map((i) => html`<option value="i:${i.id}">${i.nome} (${i.sigla})</option>`)}</optgroup>`)}`;
  }

  const linhaConsumo = () => html`
    <div class="consumo-linha" data-consumo>
      <select name="item" aria-label="O que foi consumido">${opcoesConsumo()}</select>
      <input name="qtd" inputmode="decimal" value="1" aria-label="Quantidade">
      <button type="button" class="btn btn-icon" data-remover-consumo aria-label="Remover linha">✕</button>
    </div>`;

  /** Janta da equipe: escolhe pratos (baixam pela ficha) e/ou bebidas e insumos avulsos. */
  function abrirConsumo() {
    modal({
      titulo: 'Consumo da equipe',
      largo: true,
      corpo: html`
        <p class="text-2" style="margin:0">Tira do estoque o que a equipe comeu e bebeu. Não vira venda nem entra no lucro;
          o custo aparece em Financeiro → Saídas do mês.</p>
        <label class="field">Motivo<input name="motivo" required maxlength="150" value="Janta da equipe"></label>
        <div class="stack" data-linhas>${linhaConsumo()}</div>
        <div><button type="button" class="btn btn-sm" data-add-consumo>+ Item</button></div>`,
      textoSalvar: 'Tirar do estoque',
      aoAbrir: (form) => {
        const linhas = form.querySelector('[data-linhas]');
        form.querySelector('[data-add-consumo]').addEventListener('click', () => linhas.insertAdjacentHTML('beforeend', String(linhaConsumo())));
        linhas.addEventListener('click', (ev) => {
          if (ev.target.closest('[data-remover-consumo]') && linhas.children.length > 1) ev.target.closest('[data-consumo]').remove();
        });
      },
      aoSalvar: async (d, form) => {
        const itens = [...form.querySelectorAll('[data-consumo]')]
          .map((l) => ({ item: l.querySelector('[name=item]').value, qtd: decimal(l.querySelector('[name=qtd]').value) }))
          .filter((x) => x.item && x.qtd);
        if (!itens.length) throw new Error('Escolha pelo menos um item.');
        const prato = itens.find((x) => x.item.startsWith('p:') && !Number.isInteger(x.qtd));
        if (prato) throw new Error('Pratos do cardápio vão em quantidade inteira (ex.: 2 porções).');
        const baixas = await api.post('/consumo-interno', {
          motivo: d.motivo,
          produtos: itens.filter((x) => x.item.startsWith('p:')).map((x) => ({ produtoId: Number(x.item.slice(2)), quantidade: x.qtd })),
          insumos: itens.filter((x) => x.item.startsWith('i:')).map((x) => ({ insumoId: Number(x.item.slice(2)), quantidade: x.qtd })),
        });
        const custo = baixas.reduce((soma, b) => soma + Number(b.custo ?? 0), 0);
        toast(`Saiu do estoque: ${baixas.length} insumo(s)${admin ? ` · custo ${moeda(custo)}` : ''}`);
        await carregar();
      },
    });
  }

  /** Pedidos dados de graça: quando, para quem, o que saiu do estoque e quanto custou. */
  async function abrirCortesias() {
    const lista = await api.get('/pedidos/cortesias?dias=60');
    const custo = lista.reduce((soma, p) => soma + Number(p.distribuicao?.reposicaoEstoque ?? 0), 0);
    modal({
      titulo: 'Cortesias: últimos 60 dias',
      largo: true,
      corpo: lista.length ? html`
        <p class="text-2" style="margin:0">${lista.length} pedido(s) dado(s) de graça · custo dos insumos <strong>${moeda(custo)}</strong></p>
        <div class="table-wrap"><table>
          <thead><tr><th>Pedido</th><th>Quando</th><th>Para quem / motivo</th><th>Itens</th><th class="right">Custo</th></tr></thead>
          <tbody>${lista.map((p) => html`<tr>
            <td class="num">#${p.id}</td><td class="num">${dataHora(p.criadoEm)}</td>
            <td>${p.motivoCortesia}${p.clienteNome ? html`<div class="muted" style="font-size:.78rem">${p.clienteNome}${p.entrega ? ` · entrega ${p.entrega.bairro}` : ''}</div>` : ''}</td>
            <td>${p.itens.map((i) => `${i.quantidade}× ${i.produto}`).join(', ')}</td>
            <td class="num right">${moeda(p.distribuicao?.reposicaoEstoque)}</td></tr>`)}</tbody></table></div>
        <p class="muted" style="margin:0;font-size:.8rem">Para lançar uma cortesia: PDV → forma de pagamento "🎁 Cortesia".</p>`
        : html`<p class="muted">Nenhuma cortesia nos últimos 60 dias. Para lançar: PDV → forma de pagamento "🎁 Cortesia".</p>`,
    });
  }

  async function abrirHistoricoConsumo() {
    const baixas = await api.get('/consumo-interno?dias=30');
    const total = baixas.reduce((soma, b) => soma + Number(b.custo ?? 0), 0);
    modal({
      titulo: 'Consumo da equipe: últimos 30 dias',
      largo: true,
      corpo: baixas.length ? html`
        ${admin ? html`<p class="text-2" style="margin:0">Custo total: <strong>${moeda(total)}</strong></p>` : ''}
        <div class="table-wrap"><table>
          <thead><tr><th>Quando</th><th>Motivo</th><th>Insumo</th><th class="right">Qtd</th>${admin ? html`<th class="right">Custo</th>` : ''}<th>Quem</th></tr></thead>
          <tbody>${baixas.map((b) => html`<tr>
            <td class="num">${dataHora(b.quando)}</td><td>${b.motivo}</td><td>${b.insumo}</td>
            <td class="num right">${numero(b.quantidade)} ${b.sigla}</td>${admin ? html`<td class="num right">${moeda(b.custo)}</td>` : ''}
            <td class="muted">${b.usuario}</td></tr>`)}</tbody></table></div>`
        : html`<p class="muted">Nenhum consumo registrado nos últimos 30 dias.</p>`,
    });
  }

  const aoFiltrar = (ev) => {
    const alvo = ev.target;
    if (alvo.matches('[data-contado]')) {
      const i = insumo(alvo.dataset.contado);
      const contado = decimal(alvo.value);
      const celula = container.querySelector(`[data-diferenca="${i.id}"]`);
      const dif = contado == null ? null : contado - Number(i.estoqueAtual);
      celula.textContent = dif == null ? '—' : `${dif > 0 ? '+' : ''}${numero(dif)} ${i.sigla}`;
      celula.className = `num right ${dif == null || Math.abs(dif) < 0.0005 ? 'muted' : dif < 0 ? 'bad' : 'good'}`;
      return;
    }
    if (ev.type === 'change' && alvo.matches('[data-dias-compra]')) {
      diasCompra = Math.min(60, Math.max(1, Number(alvo.value) || 7));
      desenhar();
      return;
    }
    if (ev.type === 'change' && alvo.matches('[data-local-contagem]')) {
      localContagem = alvo.value;
      desenhar();
      return;
    }
    if (ev.type === 'change' && alvo.matches('[data-dias-furo]')) {
      diasFuro = Number(alvo.value);
      carregar().catch(toastErro);
      return;
    }
    const campo = alvo.dataset.filtro;
    if (!campo) return;
    filtro[campo] = ev.target.type === 'checkbox' ? ev.target.checked : ev.target.value;
    desenharTabela();
  };
  container.addEventListener('input', aoFiltrar);
  container.addEventListener('change', aoFiltrar);

  function linhaCompra() {
    return html`
      <div class="ficha-linha" data-item-compra style="grid-template-columns:2fr 1.3fr 80px 100px 130px 32px">
        <select name="cInsumo" aria-label="Insumo">
          <option value="">Escolha o insumo…</option>
          ${grupos().map((g) => html`<optgroup label="${g}">${insumos.filter((i) => i.ativo && (i.grupo || SEM_GRUPO) === g)
            .map((i) => html`<option value="${i.id}">${i.nome}</option>`)}</optgroup>`)}
        </select>
        <select name="cForma" aria-label="Como chegou"><option value="">Avulso</option></select>
        <input name="cQtd" inputmode="decimal" placeholder="Qtd" aria-label="Quantidade">
        <input name="cValor" inputmode="decimal" placeholder="R$ total" aria-label="Valor pago neste item">
        <input name="cValidade" type="date" aria-label="Validade (opcional)" title="Validade (opcional)">
        <button type="button" class="btn btn-icon" data-remover-item aria-label="Remover item">✕</button>
      </div>`;
  }

  /** Cupom de mercado ou compra sem nota: vários itens, uma entrada para cada, um só pagamento. */
  function abrirCompra() {
    modal({
      titulo: 'Compra sem nota (cupom)',
      largo: true,
      textoSalvar: 'Dar entrada no estoque',
      corpo: html`
        <p class="text-2" style="margin:0">Cupom com QR Code? Use <strong>Importar nota ou cupom</strong> e fotografe o QR Code.
          Sem QR Code, lance cada item com a quantidade e o valor pago nele.
          O custo médio de cada insumo é recalculado e o total entra uma vez só nas saídas.</p>
        <div class="muted" style="font-size:.8rem;display:grid;grid-template-columns:2fr 1.3fr 80px 100px 130px 32px;gap:8px">
          <span>Insumo</span><span>Como chegou</span><span>Quantidade</span><span>Valor (R$)</span><span>Validade</span><span></span></div>
        <div class="stack" data-itens-compra>${[1, 2, 3].map(() => linhaCompra())}</div>
        <div class="row" style="justify-content:space-between">
          <button type="button" class="btn btn-sm" data-add-item>+ Item</button>
          <strong data-total-compra>Total: ${moeda(0)}</strong>
        </div>
        <div class="form-grid">
          <label class="field">Onde comprou<input name="observacao" maxlength="200" placeholder="Ex.: Cupom Assaí 08/10"></label>
          <label class="field">Como foi pago
            <select name="pagamento">
              <option value="PIX">PIX / conta (por fora do caixa)</option>
              <option value="CAIXA">Dinheiro da gaveta (caixa aberto)</option>
              <option value="DEBITO">Débito</option>
              <option value="CREDITO">Crédito</option>
              <option value="NAO">Não lançar nas saídas</option>
            </select></label>
          ${campoParcelas()}
          <label class="field">Fornecedor / mercado
            <select name="fornecedorId"><option value="">—</option>
              ${fornecedores.filter((f) => f.ativo).map((f) => html`<option value="${f.id}">${f.nome}</option>`)}</select></label>
        </div>`,
      aoAbrir: (form) => {
        ligarParcelas(form, 'pagamento', () => [...form.querySelectorAll('[name=cValor]')].reduce((s, el) => s + (decimal(el.value) || 0), 0));
        const lista = form.querySelector('[data-itens-compra]');
        const totalEl = form.querySelector('[data-total-compra]');
        const atualizarTotal = () => {
          const total = [...lista.querySelectorAll('[name=cValor]')].reduce((s, el) => s + (decimal(el.value) || 0), 0);
          totalEl.textContent = `Total: ${moeda(total)}`;
        };
        form.querySelector('[data-add-item]').addEventListener('click', () => {
          lista.insertAdjacentHTML('beforeend', String(linhaCompra()));
          lista.lastElementChild.querySelector('[name=cInsumo]').focus();
        });
        lista.addEventListener('click', (ev) => {
          if (ev.target.closest('[data-remover-item]')) { ev.target.closest('[data-item-compra]').remove(); atualizarTotal(); }
        });
        lista.addEventListener('change', (ev) => {
          if (ev.target.name !== 'cInsumo') return;
          const i = insumo(ev.target.value);
          const forma = ev.target.closest('[data-item-compra]').querySelector('[name=cForma]');
          forma.innerHTML = String(html`${(i?.embalagens ?? []).map((e) => html`<option value="${e.id}">${e.nome}</option>`)}
            <option value="">${i ? `Avulso, em ${i.sigla}` : 'Avulso'}</option>`);
        });
        lista.addEventListener('input', atualizarTotal);
      },
      aoSalvar: async (d, form) => {
        const itens = [...form.querySelectorAll('[data-item-compra]')]
          .filter((l) => l.querySelector('[name=cInsumo]').value)
          .map((l) => {
            const nome = l.querySelector('[name=cInsumo]').selectedOptions[0].textContent;
            const quantidade = decimal(l.querySelector('[name=cQtd]').value);
            const valorTotal = decimal(l.querySelector('[name=cValor]').value);
            if (!quantidade || valorTotal == null) throw new Error(`Informe a quantidade e o valor de ${nome}.`);
            const embalagemId = l.querySelector('[name=cForma]').value ? Number(l.querySelector('[name=cForma]').value) : null;
            const validade = l.querySelector('[name=cValidade]').value || null;
            return embalagemId
              ? { insumoId: Number(l.querySelector('[name=cInsumo]').value), embalagemId, quantidadeEmbalagens: quantidade, valorTotal, validade }
              : { insumoId: Number(l.querySelector('[name=cInsumo]').value), quantidade, valorTotal, validade };
          });
        if (!itens.length) throw new Error('Escolha pelo menos um insumo.');
        const r = await api.post('/insumos/compras', {
          itens,
          observacao: d.observacao,
          pagamento: d.pagamento === 'NAO' ? null : {
            forma: d.pagamento === 'CAIXA' ? 'DINHEIRO' : d.pagamento,
            doCaixa: d.pagamento === 'CAIXA',
            fornecedorId: d.fornecedorId ? Number(d.fornecedorId) : null,
            parcelas: parcelasDe(d, 'pagamento'),
          },
        });
        toast(`Compra lançada: ${r.itens} item(ns) · ${moeda(r.valorTotal)}`);
        await carregar();
      },
    });
  }

  /** Notas e cupons importados, com a opção de desfazer uma importação que entrou errada. */
  async function abrirImportadas() {
    const notas = await api.get('/notas');
    modal({
      titulo: 'Compras importadas',
      largo: true,
      corpo: notas.length ? html`
        <p class="text-2" style="margin:0">Desfazer tira do estoque o que essa compra deu entrada, remove o pagamento das saídas
          e apaga os itens que só existiam por causa dela. Depois é só importar de novo.</p>
        <div class="table-wrap"><table>
          <thead><tr><th>Compra</th><th>Emitida</th><th class="right">Total</th><th>Importada</th><th></th></tr></thead>
          <tbody>${notas.map((n) => html`
            <tr>
              <td><strong>${n.chave.slice(20, 22) === '65' ? 'Cupom' : 'NF-e'} nº ${n.numero}</strong><div class="sub">${n.emitente}</div></td>
              <td class="num">${new Date(n.emitidaEm).toLocaleDateString('pt-BR')}</td>
              <td class="num right">${moeda(n.valorTotal)}</td>
              <td class="num">${dataHora(n.importadaEm)}<div class="sub">${n.importadaPor}</div></td>
              <td class="right"><button type="button" class="btn btn-sm btn-danger" data-desfazer="${n.id}">Desfazer</button></td>
            </tr>`)}</tbody>
        </table></div>` : html`<p class="muted">Nenhuma nota ou cupom importado ainda.</p>`,
      aoAbrir: (form) => form.addEventListener('click', (ev) => {
        const id = ev.target.closest('[data-desfazer]')?.dataset.desfazer;
        if (!id) return;
        const n = notas.find((x) => String(x.id) === id);
        document.getElementById('modal').close();
        modal({
          titulo: `Desfazer importação nº ${n.numero}`,
          corpo: html`<p class="text-2" style="margin:0">${n.emitente} · ${moeda(n.valorTotal)}. O estoque volta ao que era antes dessa
            compra e o pagamento sai das saídas. Se algum item já foi usado depois, ajuste pela contagem antes.</p>`,
          textoSalvar: 'Desfazer importação',
          perigo: true,
          aoSalvar: async () => {
            const r = await api.del(`/notas/${id}`);
            toast(`Importação desfeita: ${r.entradas} entrada(s) estornada(s)${r.insumosRemovidos
              ? `, ${r.insumosRemovidos} item(ns) removido(s)` : ''}${r.saidasRemovidas ? ', pagamento removido das saídas' : ''}`);
            if (r.negativos.length) {
              toast(`Já tinham vendido parte do que chegou e ficaram negativos até importar de novo: ${r.negativos.join(', ')}`);
            }
            await carregar();
          },
        });
      }),
    });
  }

  /** Cadastro duplicado: passa saldo, custo, validades e ligações de nota para o item que fica. */
  function abrirJuntar(i) {
    const opcoes = insumos.filter((x) => x.ativo && x.id !== i.id && x.unidade === i.unidade);
    modal({
      titulo: `Juntar ${i.nome}`,
      corpo: html`
        <p class="text-2" style="margin:0">Os ${numero(i.estoqueAtual)} ${i.sigla} de <strong>${i.nome}</strong> passam para o item escolhido,
          pelo custo médio. ${i.nome} fica zerado e inativo; as próximas notas desse produto já caem no item escolhido.</p>
        <label class="field">Juntar em
          <select name="destino" required><option value="">Escolha…</option>
            ${opcoes.map((x) => html`<option value="${x.id}">${x.nome} (${numero(x.estoqueAtual)} ${x.sigla})</option>`)}</select></label>`,
      textoSalvar: 'Juntar',
      aoSalvar: async (d) => {
        if (!d.destino) throw new Error('Escolha o item que vai ficar.');
        const r = await api.post(`/insumos/${i.id}/juntar?destino=${d.destino}`);
        toast(`Juntado em ${r.nome}: agora ${numero(r.estoqueAtual)} ${r.sigla}`);
        await carregar();
      },
    });
  }

  function confirmarExclusao(i) {
    const juntos = soDesteInsumo(i).map((p) => p.nome).join(', ');
    modal({
      titulo: `Excluir ${i.nome}`,
      corpo: html`<p class="text-2" style="margin:0">O insumo sai do estoque${juntos ? html` e <strong>${juntos}</strong> sai do cardápio` : ''}.
        Não dá para desfazer. Item que já foi vendido não pode ser excluído: desmarque "Ativo" para tirá-lo da lista.</p>`,
      textoSalvar: 'Excluir',
      perigo: true,
      aoSalvar: async () => {
        await api.del(`/insumos/${i.id}`);
        toast(`${i.nome} excluído`);
        await carregar();
      },
    });
  }

  const soltar = aoClicar(container, {
    novo: () => abrirCadastro(null),
    duplicar: (el) => {
      const i = insumo(el.dataset.id);
      abrirCadastro({ ...i, nome: `${i.nome} (cópia)`, embalagens: i.embalagens.map((e) => ({ ...e, id: null })) });
    },
    consumo: () => abrirConsumo(),
    nota: () => abrirImportacao(insumos, carregar),
    compra: () => abrirCompra(),
    importadas: () => abrirImportadas().catch(toastErro),
    'copiar-lista': async () => {
      const lista = sugestaoDeCompra(insumos, indicadores, diasCompra);
      const porGrupo = Object.entries(lista.reduce((acc, x) => { (acc[x.i.grupo || 'Sem grupo'] ??= []).push(x); return acc; }, {}));
      try {
        await navigator.clipboard.writeText(textoDaLista(porGrupo, diasCompra, lista.reduce((t, x) => t + x.custo, 0)));
        toast('Lista copiada');
      } catch { toastErro(new Error('Não foi possível copiar. Use o botão do WhatsApp.')); }
    },
    'salvar-contagem': async () => {
      const itens = [...container.querySelectorAll('[data-contado]')].filter((el) => el.value.trim() !== '')
        .map((el) => ({ insumoId: Number(el.dataset.contado), quantidadeContada: decimal(el.value) }));
      if (!itens.length) { toastErro(new Error('Preencha o contado de pelo menos um item.')); return; }
      try {
        const motivo = container.querySelector('[data-motivo-contagem]').value.trim() || 'Contagem';
        const r = await api.post('/insumos/contagem', { motivo, itens });
        toast(`Contagem salva: ${itens.length} item(ns) conferido(s), ${r.ajustados} com diferença`);
        await carregar();
      } catch (e) { toastErro(e); }
    },
    'nova-validade': () => modal({
      titulo: 'Registrar validade',
      corpo: html`<div class="form-grid">
        <label class="field" style="grid-column:1/-1">Item<select name="insumoId" required>
          <option value="">Escolha…</option>
          ${insumos.filter((i) => i.ativo).map((i) => html`<option value="${i.id}">${i.nome} (${numero(i.estoqueAtual)} ${i.sigla})</option>`)}</select></label>
        <label class="field">Quantidade<input name="quantidade" required inputmode="decimal"></label>
        <label class="field">Validade<input name="validade" type="date" required></label></div>`,
      aoSalvar: async (d) => {
        if (!d.insumoId) throw new Error('Escolha o item.');
        await api.post(`/insumos/${d.insumoId}/validades`, { quantidade: decimal(d.quantidade), validade: d.validade });
        toast('Validade registrada');
        await carregar();
      },
    }),
    'remover-validade': async (el) => {
      try { await api.del(`/insumos/validades/${el.dataset.id}`); await carregar(); } catch (e) { toastErro(e); }
    },
    cortesias: () => abrirCortesias().catch(toastErro),
    'historico-consumo': () => abrirHistoricoConsumo().catch(toastErro),
    editar: (el) => {
      const i = insumo(el.dataset.id);
      modal({
        titulo: `Editar ${i.nome}`,
        largo: true,
        corpo: html`${formInsumo(i)}
          <div class="row" style="justify-content:space-between">
            <label class="row" style="gap:6px"><input type="checkbox" name="ativo" ${i.ativo ? 'checked' : ''} style="width:auto"> Ativo</label>
            <div class="row">
              <button type="button" class="btn btn-sm" data-juntar title="Cadastro duplicado (ex.: o mesmo frango de outra marca)">Juntar com outro item</button>
              <button type="button" class="btn btn-sm btn-danger" data-excluir>Excluir insumo</button>
            </div>
          </div>`,
        aoAbrir: (form) => {
          prepararFormInsumo(form);
          form.querySelector('[data-juntar]').addEventListener('click', () => {
            document.getElementById('modal').close();
            abrirJuntar(i);
          });
          form.querySelector('[data-excluir]').addEventListener('click', () => {
            document.getElementById('modal').close();
            confirmarExclusao(i);
          });
        },
        aoSalvar: async (d, form) => {
          const revenda = produtoDeRevenda(i);
          const preco = decimal(d.precoVenda);
          if (d.vender === 'on' && !preco) throw new Error('Informe o preço de venda.');
          await api.put(`/insumos/${i.id}`, corpoInsumo(d, form));
          const saldo = decimal(d.saldoAtual);
          if (saldo != null && Math.abs(saldo - Number(i.estoqueAtual)) > 0.0005) {
            if (saldo < 0) throw new Error('A quantidade em estoque não pode ser negativa.');
            await api.post(`/insumos/${i.id}/ajustes`, { quantidadeContada: saldo, motivo: 'Correção no cadastro' });
          }
          const ativo = d.ativo === 'on';
          if (ativo !== i.ativo) await api.patch(`/insumos/${i.id}/ativo?valor=${ativo}`);
          if (revenda && preco && preco !== Number(revenda.precoVenda)) {
            await api.patch(`/produtos/${revenda.id}/preco`, { precoVenda: preco });
          } else if (!revenda && d.vender === 'on') {
            await api.post('/produtos', {
              nome: d.nome, categoria: d.categoriaProduto, descricao: null, precoVenda: preco,
              fichaTecnica: [{ insumoId: i.id, modo: 'UNIDADE_BASE', quantidade: 1 }],
            });
          }
          toast('Insumo atualizado');
          await carregar();
        },
      });
    },
    entrada: (el) => {
      const i = insumo(el.dataset.id);
      const temEmbalagem = i.embalagens.length > 0;
      modal({
        titulo: `Entrada de ${i.nome}`,
        corpo: html`
          <p class="text-2" style="margin:0">Saldo atual: <strong>${numero(i.estoqueAtual)} ${i.sigla}</strong> a ${moeda(i.custoUnitario)}/${i.sigla}.
          O custo médio é recalculado com o valor desta compra.</p>
          <div class="form-grid">
            <label class="field">Como chegou
              <select name="forma">
                ${i.embalagens.map((e) => html`<option value="${e.id}">${e.nome}</option>`)}
                <option value="">Avulso, em ${i.sigla}</option>
              </select>
            </label>
            <label class="field"><span data-rotulo-qtd>${temEmbalagem ? 'Quantas embalagens' : `Quantidade (${i.sigla})`}</span>
              <input name="quantidade" required inputmode="decimal"></label>
            <label class="field">Valor total pago (R$)<input name="valorTotal" required inputmode="decimal"></label>
            <label class="field">Validade (opcional)<input name="validade" type="date"></label>
          </div>
          <div class="card" style="background:var(--surface-2);font-size:.9rem" data-resumo>Informe a quantidade.</div>
          ${admin ? html`<div class="muted" style="font-size:.85rem" data-lucro-compra></div>` : ''}
          <div class="form-grid">
            <label class="field">Como foi pago
              <select name="pagamento">
                <option value="PIX">PIX / conta (por fora do caixa)</option>
                <option value="CAIXA">Dinheiro da gaveta (caixa aberto)</option>
                <option value="DEBITO">Débito</option>
                <option value="CREDITO">Crédito</option>
                <option value="NAO">Não lançar nas saídas</option>
              </select></label>
            ${campoParcelas()}
            <label class="field">Fornecedor / mercado
              <select name="fornecedorId"><option value="">—</option>
                ${fornecedores.filter((f) => f.ativo).map((f) => html`<option value="${f.id}">${f.nome}</option>`)}</select></label>
          </div>
          <label class="field">Observação<input name="observacao" maxlength="255" placeholder="Fornecedor, nota fiscal…"></label>`,
        textoSalvar: 'Registrar entrada',
        aoAbrir: (form) => {
          ligarParcelas(form, 'pagamento', () => decimal(form.valorTotal.value));
          const atualizar = () => {
            const emb = i.embalagens.find((e) => String(e.id) === form.forma.value);
            form.querySelector('[data-rotulo-qtd]').textContent = emb ? 'Quantas embalagens' : `Quantidade (${i.sigla})`;
            const q = decimal(form.quantidade.value) || 0;
            const total = emb ? q * emb.conteudo : q;
            const valor = decimal(form.valorTotal.value) || 0;
            const novoSaldo = Number(i.estoqueAtual) + total;
            form.querySelector('[data-resumo]').innerHTML = String(total ? html`
              Entram <strong>${numero(total)} ${i.sigla}</strong>${valor ? html` a <strong>${moeda(valor / total)}/${i.sigla}</strong>` : ''}.
              Novo saldo: ${numero(novoSaldo)} ${i.sigla}${equivalencias(i, novoSaldo) ? html` (${equivalencias(i, novoSaldo)})` : ''}.`
              : html`Informe a quantidade.`);
            const lucro = form.querySelector('[data-lucro-compra]');
            if (lucro) {
              const margens = total && valor ? margensDoInsumo(i, produtos, valor / total).filter((m) => m.principal) : [];
              lucro.innerHTML = String(margens.length ? html`Com o preço desta compra, cada ${i.sigla} deixa
                <strong>${faixa(margens, 'lucroPorUnidade')}</strong> de lucro. Os ${numero(total)} ${i.sigla} rendem
                <strong>${faixa(margens.map((m) => ({ v: Math.floor(total / m.usa + 1e-6) * m.lucro })), 'v')}</strong>.` : '');
            }
          };
          form.addEventListener('input', atualizar);
          form.addEventListener('change', atualizar);
        },
        aoSalvar: async (d) => {
          const porEmbalagem = !!d.forma;
          const r = await api.post(`/insumos/${i.id}/entradas`, {
            quantidade: porEmbalagem ? null : decimal(d.quantidade),
            embalagemId: porEmbalagem ? Number(d.forma) : null,
            quantidadeEmbalagens: porEmbalagem ? decimal(d.quantidade) : null,
            valorTotal: decimal(d.valorTotal),
            validade: d.validade || null,
            observacao: d.observacao,
            pagamento: d.pagamento === 'NAO' ? null : {
              forma: d.pagamento === 'CAIXA' ? 'DINHEIRO' : d.pagamento,
              doCaixa: d.pagamento === 'CAIXA',
              fornecedorId: d.fornecedorId ? Number(d.fornecedorId) : null,
              parcelas: parcelasDe(d, 'pagamento'),
            },
          });
          toast(`Novo saldo: ${numero(r.estoqueAtual)} ${r.sigla} · custo médio ${moeda(r.custoUnitario)}/${r.sigla}`);
          await carregar();
        },
      });
    },
    ajuste: (el) => {
      const i = insumo(el.dataset.id);
      modal({
        titulo: `Contagem de ${i.nome}`,
        corpo: html`
          <p class="text-2" style="margin:0">O sistema acha que tem <strong>${numero(i.estoqueAtual)} ${i.sigla}</strong>${equivalencias(i) ? ` (${equivalencias(i)})` : ''}.
          Informe quanto você contou de verdade; a diferença vira um ajuste no extrato (perda, quebra, vencimento).</p>
          <div class="form-grid">
            <label class="field">Quantidade contada (${i.sigla})<input name="quantidadeContada" required inputmode="decimal"></label>
            <label class="field">Motivo<input name="motivo" required maxlength="255" placeholder="Inventário semanal"></label>
          </div>`,
        textoSalvar: 'Ajustar saldo',
        aoSalvar: async (d) => {
          await api.post(`/insumos/${i.id}/ajustes`, { quantidadeContada: decimal(d.quantidadeContada), motivo: d.motivo });
          toast('Saldo ajustado');
          await carregar();
        },
      });
    },
    rendimento: (el) => abrirRendimento(insumo(el.dataset.id)).catch(toastErro),
    extrato: async (el) => {
      const i = insumo(el.dataset.id);
      try {
        const movs = await api.get(`/insumos/${i.id}/movimentacoes`);
        modal({
          titulo: `Extrato de ${i.nome}`,
          corpo: movs.length ? html`
            <div class="table-wrap"><table>
              <thead><tr><th>Quando</th><th>Tipo</th><th class="right">Qtd</th><th class="right">Saldo</th><th>Ref.</th></tr></thead>
              <tbody>${movs.map((m) => html`
                <tr>
                  <td class="num">${dataHora(m.criadoEm)}</td>
                  <td>${rotulo(m.tipo)}</td>
                  <td class="num right ${m.quantidade < 0 ? 'bad' : 'good'}">${m.quantidade > 0 ? '+' : ''}${numero(m.quantidade)}</td>
                  <td class="num right">${numero(m.saldoApos)}</td>
                  <td class="muted">${m.pedidoId ? `Pedido #${m.pedidoId}` : m.observacao || ''}${m.usuario ? ` · ${m.usuario}` : ''}</td>
                </tr>`)}</tbody>
            </table></div>` : html`<p class="muted">Sem movimentações.</p>`,
        });
      } catch (e) { toastErro(e); }
    },
  });

  const soltarTooltips = ativarTooltips(container);
  await carregar();
  return () => {
    soltar();
    soltarTooltips();
    container.removeEventListener('input', aoFiltrar);
    container.removeEventListener('change', aoFiltrar);
  };
}
