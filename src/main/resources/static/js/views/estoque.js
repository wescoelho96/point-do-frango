import { api, sessao } from '../api.js';
import { abrirImportacao } from './nota-fiscal.js';
import { aoClicar, dataHora, decimal, html, modal, moeda, numero, pct, renderizar, rotulo, toast, toastErro } from '../ui.js';

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

const SEM_GRUPO = 'Sem grupo';
const SUGESTOES_GRUPO = ['Carnes', 'Congelados', 'Refrigerantes', 'Cervejas', 'Águas', 'Sucos', 'Óleos e molhos',
  'Embalagens', 'Limpeza', 'Descartáveis'];

export async function montar(container) {
  const admin = sessao.admin();
  let insumos = [];
  let produtos = [];
  let fornecedores = [];
  const filtro = { texto: '', grupo: '', repor: false };

  async function carregar() {
    [insumos, produtos, fornecedores] = await Promise.all([
      api.get('/insumos'), api.get('/produtos'), admin ? api.get('/fornecedores') : [],
    ]);
    desenhar();
  }

  const grupos = () => [...new Set(insumos.map((i) => i.grupo || SEM_GRUPO))]
    .sort((a, b) => (a === SEM_GRUPO) - (b === SEM_GRUPO) || a.localeCompare(b, 'pt-BR'));
  const marcas = () => [...new Set(insumos.map((i) => i.marca).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'pt-BR'));
  const semAcento = (x) => String(x ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();

  const insumo = (id) => insumos.find((i) => i.id === Number(id));

  function desenhar() {
    const alertas = insumos.filter((i) => i.ativo && i.abaixoDoMinimo);
    const valorTotal = insumos.reduce((soma, i) => soma + Number(i.valorEmEstoque ?? 0), 0);
    renderizar(container, html`
      <div class="page-head">
        <div>
          <h1>Estoque de insumos</h1>
          <p>${insumos.length} insumos${admin ? ` · ${moeda(valorTotal)} parados em mercadoria` : ''}${alertas.length
            ? html` · <span class="bad">⚠️ ${alertas.length} abaixo do mínimo</span>` : ''}</p>
        </div>
        <div class="row">
          <button class="btn" data-acao="consumo">🍽️ Consumo da equipe</button>
          ${admin ? html`<button class="btn" data-acao="cortesias">🎁 Cortesias</button>
            <button class="btn" data-acao="nota">🧾 Importar nota fiscal</button>
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
        <button class="btn btn-sm" data-acao="historico-consumo">Consumo da equipe (30 dias)</button>
      </div>
      <div class="card table-wrap" data-tabela></div>`);
    desenharTabela();
  }

  function filtrados() {
    const termo = semAcento(filtro.texto);
    return insumos.filter((i) => (!filtro.grupo || (i.grupo || SEM_GRUPO) === filtro.grupo)
      && (!filtro.repor || (i.ativo && i.abaixoDoMinimo))
      && (!termo || semAcento(`${i.nome} ${i.marca ?? ''} ${i.grupo ?? ''}`).includes(termo)));
  }

  function linhaInsumo(i) {
    return html`
      <tr class="${i.ativo ? '' : 'inativo'}">
        <td><strong>${i.nome}</strong> ${i.marca ? html`<span class="badge">${i.marca}</span>` : ''} ${!i.ativo ? html`<span class="badge">inativo</span>` : ''}
          ${i.embalagens.length || i.unidadeUsoNome ? html`<div class="muted" style="font-size:.78rem">
            ${i.embalagens.map((e) => e.nome).join(' · ')}${i.embalagens.length && i.unidadeUsoNome ? ' · ' : ''}${i.unidadeUsoNome
              ? `${numero(i.unidadeUsoPorUnidade, 2)} ${plural(i.unidadeUsoNome, 2)}/${i.sigla}` : ''}</div>` : ''}</td>
        <td class="num right">${numero(i.estoqueAtual)} ${i.sigla}
          ${i.ativo && i.abaixoDoMinimo ? html` <span class="badge badge-bad">⚠ repor</span>` : ''}
          ${equivalencias(i) ? html`<div class="muted" style="font-size:.78rem">${equivalencias(i)}</div>` : ''}</td>
        <td class="num right muted">${numero(i.estoqueMinimo)} ${i.sigla}</td>
        ${admin ? colunasDeCusto(i) : ''}
        <td class="right" style="white-space:nowrap">
          ${admin ? html`
            <button class="btn btn-sm" data-acao="entrada" data-id="${i.id}">📥 Entrada</button>
            <button class="btn btn-sm" data-acao="ajuste" data-id="${i.id}">📋 Contagem</button>
            <button class="btn btn-sm" data-acao="editar" data-id="${i.id}">Editar</button>
            <button class="btn btn-sm" data-acao="duplicar" data-id="${i.id}" title="Criar outro parecido (outro sabor ou tamanho)">⧉</button>` : ''}
          <button class="btn btn-sm" data-acao="rendimento" data-id="${i.id}">📊 Rende</button>
          <button class="btn btn-sm" data-acao="extrato" data-id="${i.id}">Extrato</button>
        </td>
      </tr>`;
  }

  function desenharTabela() {
    const lista = filtrados();
    const colunas = admin ? 7 : 4;
    const porGrupo = grupos().map((g) => [g, lista.filter((i) => (i.grupo || SEM_GRUPO) === g)]).filter(([, l]) => l.length);
    renderizar(container.querySelector('[data-tabela]'), lista.length ? html`
      <table>
        <thead><tr>
          <th>Insumo</th><th class="right">Saldo</th><th class="right">Mínimo</th>
          ${admin ? html`<th class="right">Custo médio</th><th class="right">Valor em estoque</th>
            <th class="right" title="Quanto sobra de lucro para cada kg/un deste insumo que vira produto">Lucro por kg / un</th>` : ''}<th></th>
        </tr></thead>
        <tbody>${porGrupo.map(([g, itens]) => html`
          ${porGrupo.length > 1 || g !== SEM_GRUPO ? html`<tr class="grupo-linha"><td colspan="${colunas}">${g}
            <span class="muted" style="font-weight:400">· ${itens.length} ${itens.length > 1 ? 'itens' : 'item'}${admin
              ? ` · ${moeda(itens.reduce((soma, i) => soma + Number(i.valorEmEstoque), 0))}` : ''}${itens.some((i) => i.ativo && i.abaixoDoMinimo)
              ? ` · ${itens.filter((i) => i.ativo && i.abaixoDoMinimo).length} a repor` : ''}</span></td></tr>` : ''}
          ${itens.map(linhaInsumo)}`)}
        </tbody>
      </table>` : html`<div class="empty">${insumos.length ? 'Nenhum insumo com esse filtro.' : 'Nenhum insumo cadastrado.'}</div>`);
  }

  function colunasDeCusto(i) {
    const margens = margensDoInsumo(i, produtos).filter((m) => m.principal);
    return html`
      <td class="num right">${moeda(i.custoUnitario)}/${i.sigla}</td>
      <td class="num right">${moeda(i.valorEmEstoque)}</td>
      <td class="num right">${margens.length ? html`<strong class="${margens.some((m) => m.lucro < 0) ? 'bad' : ''}">${faixa(margens, 'lucroPorUnidade')}</strong>/${i.sigla}
        <div class="muted" style="font-size:.78rem">${margens.length} produto(s) · estoque rende ${faixa(margens.map((m) => ({ v: m.porcoesNoEstoque * m.lucro })), 'v')}</div>`
        : html`<span class="muted" title="Não é o ingrediente principal de nenhum produto">—</span>`}</td>`;
  }

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
        <datalist id="lista-grupos">${[...new Set([...grupos().filter((g) => g !== SEM_GRUPO), ...SUGESTOES_GRUPO])].map((g) => html`<option value="${g}">`)}</datalist>
        <datalist id="lista-marcas">${marcas().map((m) => html`<option value="${m}">`)}</datalist>
        <label class="field">Controlar estoque em
          <select name="unidade">${UNIDADES.map((u) => html`<option value="${u}" ${i?.unidade === u ? 'selected' : ''}>${rotulo(u)}</option>`)}</select>
        </label>
        <label class="field"><span>Estoque mínimo (<span data-sigla>${sigla}</span>)</span><input name="estoqueMinimo" required inputmode="decimal" value="${i ? numero(i.estoqueMinimo) : '0'}"></label>
        <label class="field"><span>Custo por <span data-sigla>${sigla}</span> (R$)</span><input name="custoUnitario" required inputmode="decimal" value="${i ? numero(i.custoUnitario, 6) : ''}"></label>
        ${novo ? html`<label class="field"><span>Estoque inicial (<span data-sigla>${sigla}</span>)</span><input name="estoqueInicial" inputmode="decimal" value="0"></label>` : ''}
      </div>

      ${novo ? html`
        <div class="card" style="background:var(--surface-2)">
          <label class="row" style="gap:8px;font-weight:600"><input type="checkbox" name="vender" style="width:auto">
            Também vender no cardápio (bebida, item de revenda)</label>
          <div class="form-grid" style="margin-top:8px">
            <label class="field">Preço de venda (R$)<input name="precoVenda" inputmode="decimal" placeholder="Ex.: 16,00"></label>
            <label class="field">Categoria<select name="categoriaProduto">${['BEBIDA', 'OUTRO', 'PORCAO'].map((c) => html`<option value="${c}">${rotulo(c)}</option>`)}</select></label>
          </div>
          <p class="muted" style="margin:6px 0 0;font-size:.8rem">Cria o produto com ficha técnica de 1 unidade: cada venda baixa 1 do estoque.</p>
        </div>` : ''}

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

  function prepararFormInsumo(form) {
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
    grupo: d.grupo?.trim() || null, marca: d.marca?.trim() || null,
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
    const campo = ev.target.dataset.filtro;
    if (!campo) return;
    filtro[campo] = campo === 'repor' ? ev.target.checked : ev.target.value;
    desenharTabela();
  };
  container.addEventListener('input', aoFiltrar);
  container.addEventListener('change', aoFiltrar);

  const soltar = aoClicar(container, {
    novo: () => abrirCadastro(null),
    duplicar: (el) => {
      const i = insumo(el.dataset.id);
      abrirCadastro({ ...i, nome: `${i.nome} (cópia)`, embalagens: i.embalagens.map((e) => ({ ...e, id: null })) });
    },
    consumo: () => abrirConsumo(),
    nota: () => abrirImportacao(insumos, carregar),
    cortesias: () => abrirCortesias().catch(toastErro),
    'historico-consumo': () => abrirHistoricoConsumo().catch(toastErro),
    editar: (el) => {
      const i = insumo(el.dataset.id);
      modal({
        titulo: `Editar ${i.nome}`,
        corpo: html`${formInsumo(i)}
          <label class="row" style="gap:6px"><input type="checkbox" name="ativo" ${i.ativo ? 'checked' : ''} style="width:auto"> Ativo</label>`,
        aoAbrir: prepararFormInsumo,
        aoSalvar: async (d, form) => {
          await api.put(`/insumos/${i.id}`, corpoInsumo(d, form));
          const ativo = d.ativo === 'on';
          if (ativo !== i.ativo) await api.patch(`/insumos/${i.id}/ativo?valor=${ativo}`);
          toast('Insumo atualizado (fichas técnicas recalculadas)');
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
            <label class="field">Fornecedor / mercado
              <select name="fornecedorId"><option value="">—</option>
                ${fornecedores.filter((f) => f.ativo).map((f) => html`<option value="${f.id}">${f.nome}</option>`)}</select></label>
          </div>
          <label class="field">Observação<input name="observacao" maxlength="255" placeholder="Fornecedor, nota fiscal…"></label>`,
        textoSalvar: 'Registrar entrada',
        aoAbrir: (form) => {
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
            observacao: d.observacao,
            pagamento: d.pagamento === 'NAO' ? null : {
              forma: d.pagamento === 'CAIXA' ? 'DINHEIRO' : d.pagamento,
              doCaixa: d.pagamento === 'CAIXA',
              fornecedorId: d.fornecedorId ? Number(d.fornecedorId) : null,
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

  await carregar();
  return () => {
    soltar();
    container.removeEventListener('input', aoFiltrar);
    container.removeEventListener('change', aoFiltrar);
  };
}
