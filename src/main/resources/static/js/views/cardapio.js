import { api } from '../api.js';
import { aoClicar, DIAS, decimal, html, modal, moeda, NOME_DIA, numero, pct, renderizar, rotulo, textoDias, toast } from '../ui.js';
import { plural } from './estoque.js';

const CATEGORIAS = ['PORCAO', 'ESPETINHO', 'COMBO', 'BEBIDA', 'OUTRO'];

// Faixas de CMV (custo ÷ preço) usuais em food service
function classeCmv(cmv) {
  if (cmv <= 35) return ['badge-good', 'saudável'];
  if (cmv <= 45) return ['badge-warn', 'atenção'];
  return ['badge-bad', 'alto'];
}

export async function montar(container) {
  let produtos = [];
  let insumos = [];
  let promocoes = [];

  async function carregar() {
    [produtos, insumos, promocoes] = await Promise.all([api.get('/produtos'), api.get('/insumos'), api.get('/promocoes')]);
    desenhar();
  }

  function secaoPromocoes() {
    return html`
      <div class="page-head" style="margin:28px 0 12px">
        <div><h2>🎁 Promoções com brinde</h2>
          <p class="muted" style="margin:0;font-size:.85rem">"Comprou X, ganha Y" nos dias escolhidos. O PDV dá o brinde sozinho:
            sai do estoque com preço zero e o custo dele entra no lucro do pedido.</p></div>
        <button class="btn btn-primary btn-sm" data-acao="nova-promocao">+ Promoção</button>
      </div>
      <div class="card table-wrap">${promocoes.length ? html`<table>
        <thead><tr><th>Promoção</th><th>Comprou</th><th>Ganha</th><th>Dias</th><th class="right">Custo do brinde</th><th></th></tr></thead>
        <tbody>${promocoes.map((p) => {
          const brinde = produtos.find((x) => x.id === p.produtoBrindeId);
          return html`
            <tr class="${p.ativa ? '' : 'inativo'}">
              <td><strong>${p.nome}</strong>${p.ativa ? '' : html` <span class="badge">pausada</span>`}</td>
              <td>${p.quantidadeCompra}× ${p.produtoCompra}</td>
              <td>${p.quantidadeBrinde}× ${p.produtoBrinde}</td>
              <td>${textoDias(p.dias)}</td>
              <td class="num right">${brinde ? moeda(Number(brinde.custoAtual) * p.quantidadeBrinde) : '—'}</td>
              <td class="right" style="white-space:nowrap"><button class="btn btn-sm" data-acao="editar-promocao" data-id="${p.id}">Editar</button></td>
            </tr>`;
        })}</tbody></table>` : html`<div class="empty">Nenhuma promoção. Ex.: Combo Especial ganha Coca 2 L de sexta a domingo.</div>`}
      </div>`;
  }

  function abrirPromocao(p) {
    const opcoes = (sel) => produtos.map((x) => html`<option value="${x.id}" ${x.id === sel ? 'selected' : ''}>${x.nome}</option>`);
    modal({
      titulo: p ? `Editar ${p.nome}` : 'Nova promoção',
      corpo: html`
        <div class="form-grid">
          <label class="field" style="grid-column:1/-1">Nome<input name="nome" required maxlength="100" value="${p?.nome ?? ''}" placeholder="Ex.: Combo Especial ganha Coca 2 L"></label>
          <label class="field">Quem compra<select name="produtoCompraId">${opcoes(p?.produtoCompraId)}</select></label>
          <label class="field">Quantidade<input name="quantidadeCompra" type="number" min="1" max="20" value="${p?.quantidadeCompra ?? 1}"></label>
          <label class="field">Ganha<select name="produtoBrindeId">${opcoes(p?.produtoBrindeId ?? produtos.find((x) => x.categoria === 'BEBIDA')?.id)}</select></label>
          <label class="field">Quantidade<input name="quantidadeBrinde" type="number" min="1" max="20" value="${p?.quantidadeBrinde ?? 1}"></label>
        </div>
        <div><div class="muted" style="font-size:.8rem;margin-bottom:4px">Vale nos dias</div>
          <div class="segmented">${DIAS.map((d) => html`
            <label class="seg-opcao"><input type="checkbox" name="dia" value="${d}" ${p?.dias.includes(d) ? 'checked' : ''}><span>${NOME_DIA[d]}</span></label>`)}</div></div>
        ${p ? html`<label class="row" style="gap:6px"><input type="checkbox" name="ativa" ${p.ativa ? 'checked' : ''} style="width:auto"> Ativa</label>
          <div><button type="button" class="btn btn-sm btn-danger" data-excluir>Excluir promoção</button></div>` : ''}`,
      aoAbrir: (form) => form.querySelector('[data-excluir]')?.addEventListener('click', async () => {
        await api.del(`/promocoes/${p.id}`);
        document.getElementById('modal').close();
        await carregar();
      }),
      aoSalvar: async (d, form) => {
        const dias = [...form.querySelectorAll('[name=dia]:checked')].map((c) => c.value);
        if (!dias.length) throw new Error('Escolha pelo menos um dia.');
        const corpo = {
          nome: d.nome, produtoCompraId: Number(d.produtoCompraId), quantidadeCompra: Number(d.quantidadeCompra),
          produtoBrindeId: Number(d.produtoBrindeId), quantidadeBrinde: Number(d.quantidadeBrinde), dias,
          ativa: p ? d.ativa === 'on' : true,
        };
        if (p) await api.put(`/promocoes/${p.id}`, corpo); else await api.post('/promocoes', corpo);
        toast('Promoção salva');
        await carregar();
      },
    });
  }

  function desenhar() {
    renderizar(container, html`
      <div class="page-head">
        <div><h1>Cardápio e ficha técnica</h1>
          <p>O custo de cada produto sai da ficha técnica × custo médio dos insumos. CMV = quanto do preço vai para repor o estoque.</p></div>
        <button class="btn btn-primary" data-acao="novo">+ Novo produto</button>
      </div>
      <div class="card table-wrap">
        <table>
          <thead><tr>
            <th>Produto</th><th>Categoria</th><th class="right">Preço</th><th class="right">Custo</th>
            <th class="right">Margem</th><th>CMV</th><th class="right">Dá para fazer</th><th></th>
          </tr></thead>
          <tbody>
            ${produtos.map((p) => {
              const [classe, texto] = classeCmv(p.cmvPercentual);
              return html`
                <tr class="${p.ativo ? '' : 'inativo'}">
                  <td><strong>${p.nome}</strong>${p.ativo ? '' : html` <span class="badge">fora do cardápio</span>`}${p.vaiParaCozinha ? ''
                    : html` <span class="badge" title="Pedido só com este item não passa pela cozinha">🥤 sai do balcão</span>`}
                    <div class="muted" style="font-size:.8rem">${p.fichaTecnica.map((f) => `${f.descricao} ${f.insumo}`).join(' · ')}</div></td>
                  <td>${p.categoriaRotulo}</td>
                  <td class="num right">${moeda(p.precoVenda)}</td>
                  <td class="num right">${moeda(p.custoAtual)}</td>
                  <td class="num right ${Number(p.margemValor) < 0 ? 'bad' : ''}">${moeda(p.margemValor)} <span class="muted">${pct(p.margemPercentual)}</span></td>
                  <td><span class="badge ${classe}">${pct(p.cmvPercentual)} · ${texto}</span></td>
                  <td class="num right">${p.unidadesDisponiveis}</td>
                  <td class="right"><button class="btn btn-sm" data-acao="editar" data-id="${p.id}">Editar</button></td>
                </tr>`;
            })}
          </tbody>
        </table>
        ${produtos.length ? '' : html`<div class="empty">Nenhum produto cadastrado.</div>`}
      </div>
      ${secaoPromocoes()}`);
  }

  // ---------- Ficha técnica: em que unidade cada linha foi digitada ----------
  // Opções por insumo: kg · g · iscas · "porções por Saco 2 kg"...
  function modos(ins) {
    const lista = [{ v: 'UNIDADE_BASE', rotulo: ins.sigla }];
    if (ins.subunidade) lista.push({ v: 'SUBUNIDADE', rotulo: ins.subunidade });
    if (ins.unidadeUsoNome) lista.push({ v: 'UNIDADE_USO', rotulo: plural(ins.unidadeUsoNome, 2) });
    ins.embalagens.forEach((e) => lista.push({ v: `RENDIMENTO_EMBALAGEM:${e.id}`, rotulo: `porções por ${e.nome}` }));
    return lista;
  }
  const modoPadrao = (ins) => (ins.subunidade ? 'SUBUNIDADE' : 'UNIDADE_BASE');

  // Mesma conversão do Insumo.converterParaBase (Java), só para a pré-visualização
  function paraBase(ins, valorModo, q) {
    if (!q) return 0;
    const [modo, embId] = valorModo.split(':');
    if (modo === 'SUBUNIDADE') return q / 1000;
    if (modo === 'UNIDADE_USO') return q / Number(ins.unidadeUsoPorUnidade);
    if (modo === 'RENDIMENTO_EMBALAGEM') return Number(ins.embalagens.find((e) => e.id === Number(embId))?.conteudo || 0) / q;
    return q;
  }

  function opcoesModo(ins, selecionado) {
    return modos(ins).map((m) => html`<option value="${m.v}" ${m.v === selecionado ? 'selected' : ''}>${m.rotulo}</option>`);
  }

  function linhaFicha(item) {
    const ins = item ? insumos.find((i) => i.id === item.insumoId) : insumos.find((i) => i.ativo) ?? insumos[0];
    const modoAtual = item ? (item.modo === 'RENDIMENTO_EMBALAGEM' ? `${item.modo}:${item.embalagemId}` : item.modo) : modoPadrao(ins);
    return html`
      <div class="ficha-linha" data-linha style="grid-template-columns:minmax(0,1.3fr) 80px minmax(0,1fr) 32px">
        <select name="insumoId" aria-label="Insumo">
          ${insumos.map((i) => html`<option value="${i.id}" ${ins?.id === i.id ? 'selected' : ''}>${i.nome}</option>`)}
        </select>
        <input name="quantidade" inputmode="decimal" placeholder="Qtd" aria-label="Quantidade" value="${item ? numero(item.quantidadeInformada) : ''}">
        <select name="modo" aria-label="Unidade">${opcoesModo(ins, modoAtual)}</select>
        <button type="button" class="btn btn-icon" data-remover aria-label="Remover insumo">✕</button>
        <div class="muted" style="grid-column:1/-1;font-size:.8rem;margin-top:-2px" data-info></div>
      </div>`;
  }

  function abrirEditor(p) {
    modal({
      titulo: p ? `Editar ${p.nome}` : 'Novo produto',
      largo: true,
      corpo: html`
        <div class="form-grid">
          <label class="field" style="grid-column:1/-1">Nome<input name="nome" required maxlength="100" value="${p?.nome ?? ''}"></label>
          <label class="field">Categoria
            <select name="categoria">${CATEGORIAS.map((c) => html`<option value="${c}" ${p?.categoria === c ? 'selected' : ''}>${rotulo(c)}</option>`)}</select>
          </label>
          <label class="field">Preço de venda (R$)<input name="precoVenda" required inputmode="decimal" value="${p ? numero(p.precoVenda, 2) : ''}"></label>
          <label class="field" style="grid-column:1/-1">Descrição<input name="descricao" maxlength="255" value="${p?.descricao ?? ''}"></label>
        </div>
        <div>
          <h3 style="margin-bottom:4px">Ficha técnica (por unidade vendida)</h3>
          <p class="muted" style="margin:0 0 8px;font-size:.85rem">Digite como você pensa: 300 g de batata, 12 iscas, ou "7 porções por Saco 2 kg". O sistema converte para a unidade do estoque.</p>
          <div class="stack" data-ficha>${(p?.fichaTecnica.length ? p.fichaTecnica : [null]).map(linhaFicha)}</div>
          <button type="button" class="btn btn-sm" style="margin-top:8px" data-adicionar>+ Insumo</button>
        </div>
        <div class="card" style="background:var(--surface-2)" data-resumo></div>
        <label class="row" style="gap:6px"><input type="checkbox" name="vaiParaCozinha" ${(p ? p.vaiParaCozinha : true) ? 'checked' : ''} style="width:auto">
          Vai para a cozinha (precisa de preparo). Desmarque para bebidas: pedido só com elas já sai do balcão.</label>
        ${p ? html`<label class="row" style="gap:6px"><input type="checkbox" name="ativo" ${p.ativo ? 'checked' : ''} style="width:auto"> Disponível no PDV</label>` : ''}`,
      aoAbrir: (form) => {
        // produto novo: bebida já vem sem cozinha
        if (!p) form.categoria.addEventListener('change', () => { form.vaiParaCozinha.checked = form.categoria.value !== 'BEBIDA'; });
        const ficha = form.querySelector('[data-ficha]');
        const atualizar = () => {
          let custo = 0;
          ficha.querySelectorAll('[data-linha]').forEach((l) => {
            const ins = insumos.find((i) => i.id === Number(l.querySelector('[name=insumoId]').value));
            const base = paraBase(ins, l.querySelector('[name=modo]').value, decimal(l.querySelector('[name=quantidade]').value));
            const c = Number(ins.custoUnitario) * base;
            custo += c;
            const rende = ins.embalagens.map((e) => `1 ${e.nome} rende ${numero(e.conteudo / base, 1)}`);
            l.querySelector('[data-info]').textContent = base
              ? [`= ${numero(base, 4)} ${ins.sigla}`, moeda(c), ...rende].join(' · ') : '';
          });
          const preco = decimal(form.precoVenda.value) || 0;
          const cmv = preco ? (custo / preco) * 100 : 0;
          form.querySelector('[data-resumo]').innerHTML = String(html`
            Custo: <strong>${moeda(custo)}</strong> · Margem: <strong>${moeda(preco - custo)}</strong> ·
            CMV: <span class="badge ${classeCmv(cmv)[0]}">${pct(cmv)}</span>`);
        };
        form.addEventListener('input', atualizar);
        form.addEventListener('change', (ev) => {
          // As opções de unidade dependem do insumo escolhido
          if (ev.target.name === 'insumoId') {
            const ins = insumos.find((i) => i.id === Number(ev.target.value));
            ev.target.closest('[data-linha]').querySelector('[name=modo]').innerHTML = String(html`${opcoesModo(ins, modoPadrao(ins))}`);
          }
          atualizar();
        });
        form.querySelector('[data-adicionar]').addEventListener('click', () => {
          ficha.insertAdjacentHTML('beforeend', String(linhaFicha(null)));
          atualizar();
        });
        ficha.addEventListener('click', (ev) => {
          if (ev.target.closest('[data-remover]') && ficha.children.length > 1) {
            ev.target.closest('[data-linha]').remove();
            atualizar();
          }
        });
        atualizar();
      },
      aoSalvar: async (d, form) => {
        const fichaTecnica = [...form.querySelectorAll('[data-linha]')].map((l) => {
          const [modo, embalagemId] = l.querySelector('[name=modo]').value.split(':');
          return {
            insumoId: Number(l.querySelector('[name=insumoId]').value),
            modo,
            embalagemId: embalagemId ? Number(embalagemId) : null,
            quantidade: decimal(l.querySelector('[name=quantidade]').value),
          };
        }).filter((f) => f.quantidade);
        const corpo = {
          nome: d.nome, categoria: d.categoria, descricao: d.descricao || null,
          precoVenda: decimal(d.precoVenda), fichaTecnica, vaiParaCozinha: d.vaiParaCozinha === 'on',
        };
        if (p) {
          await api.put(`/produtos/${p.id}`, corpo);
          const ativo = d.ativo === 'on';
          if (ativo !== p.ativo) await api.patch(`/produtos/${p.id}/ativo?valor=${ativo}`);
        } else {
          await api.post('/produtos', corpo);
        }
        toast('Cardápio atualizado');
        await carregar();
      },
    });
  }

  const soltar = aoClicar(container, {
    novo: () => abrirEditor(null),
    'nova-promocao': () => abrirPromocao(null),
    'editar-promocao': (el) => abrirPromocao(promocoes.find((p) => p.id === Number(el.dataset.id))),
    editar: (el) => abrirEditor(produtos.find((p) => p.id === Number(el.dataset.id))),
  });

  await carregar();
  return soltar;
}
