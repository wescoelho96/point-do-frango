import { api } from '../api.js';
import { decimal, html, modal, moeda, numero, toast } from '../ui.js';

const semAcento = (x) => String(x ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
const palavras = (x) => semAcento(x).split(/[^a-z0-9]+/).filter((p) => p.length >= 3);

/** Primeiro palpite de insumo pelo nome: o que tiver mais palavras em comum com a descrição da nota. */
function palpiteInsumo(descricao, insumos) {
  const daNota = new Set(palavras(descricao));
  let melhor = null;
  let pontos = 1; // pelo menos 2 palavras em comum
  insumos.forEach((i) => {
    const p = palavras(`${i.nome} ${i.marca ?? ''}`).filter((w) => daNota.has(w)).length;
    if (p > pontos) { melhor = i; pontos = p; }
  });
  return melhor;
}

/** "FD C/6", "CX 12UN", "PCT X 10": quantas unidades vêm em cada item da nota. */
function palpiteFator(item, insumo) {
  if (!insumo || insumo.sigla !== 'un') return 1;
  const m = /(?:C\/|CX|FD|PCT|PC|X)\s*(\d{1,3})\s*(?:UN|UNID|U)?\b/i.exec(item.descricao);
  return m ? Number(m[1]) : 1;
}

const NOVO = 'novo';
const UNIDADES = {
  kg: { unidade: 'QUILOGRAMA', sigla: 'kg' },
  L: { unidade: 'LITRO', sigla: 'L' },
  un: { unidade: 'UNIDADE', sigla: 'un' },
};

/** Item sem insumo cadastrado: unidade e conversão a partir da unidade e da descrição da nota ("2KG", "900ML"). */
function palpiteNovo(item) {
  if (/^KG/i.test(item.unidade)) return { ...UNIDADES.kg, fator: 1 };
  const m = /(\d+(?:[.,]\d+)?)\s*(KG|G|LT|L|ML)\b/i.exec(item.descricao);
  if (m) {
    const valor = Number(m[1].replace(',', '.'));
    const tipo = m[2].toUpperCase();
    if (tipo === 'KG') return { ...UNIDADES.kg, fator: valor };
    if (tipo === 'G') return { ...UNIDADES.kg, fator: valor / 1000 };
    if (tipo === 'ML') return { ...UNIDADES.L, fator: valor / 1000 };
    return { ...UNIDADES.L, fator: valor };
  }
  return { ...UNIDADES.un, fator: palpiteFator(item, UNIDADES.un) };
}

/** Conversão sugerida para um insumo existente: "2KG" vale para insumo em kg, "C/6" para insumo em unidades. */
function fatorPara(item, insumo) {
  const daNota = palpiteNovo(item);
  return daNota.sigla === insumo.sigla && daNota.sigla !== 'un' ? daNota.fator : palpiteFator(item, insumo);
}

function nomeDoItem(descricao) {
  return descricao.toLowerCase().replace(/(^|\s)\S/g, (l) => l.toUpperCase()).slice(0, 100);
}

/** Reaproveita o insumo de mesmo nome, se já existir; o custo vem da própria entrada da nota. */
async function criarInsumo(item, insumos) {
  const nome = nomeDoItem(item.descricao);
  const existente = insumos.find((i) => semAcento(i.nome) === semAcento(nome));
  if (existente) return existente.id;
  const { unidade } = palpiteNovo(item);
  const criado = await api.post('/insumos', { nome, unidade, estoqueMinimo: 0, custoUnitario: 0, estoqueInicial: 0 });
  insumos.push(criado);
  return criado.id;
}

function linhaItem(previa, insumos) {
  return previa.itens.map(({ item, insumoSugerido, fatorSugerido }) => {
    const insumo = insumos.find((i) => i.id === insumoSugerido) ?? palpiteInsumo(item.descricao, insumos);
    const fator = fatorSugerido ?? (insumo ? fatorPara(item, insumo) : palpiteNovo(item).fator);
    return html`
      <tr data-item="${item.numero}">
        <td>${item.descricao}<div class="muted" style="font-size:.78rem">${numero(item.quantidade)} ${item.unidade} · ${moeda(item.valor)}
          ${insumoSugerido ? html` · <span class="badge badge-good">lembrado da última compra</span>` : ''}</div></td>
        <td><select name="insumo-${item.numero}" aria-label="Insumo do item ${item.numero}">
          <option value="">— não é estoque (ignorar) —</option>
          <option value="${NOVO}" ${insumo ? '' : 'selected'}>+ Cadastrar como novo insumo</option>
          ${insumos.filter((i) => i.ativo).map((i) => html`<option value="${i.id}" ${insumo?.id === i.id ? 'selected' : ''}>${i.nome}</option>`)}
        </select></td>
        <td style="width:120px"><input name="fator-${item.numero}" inputmode="decimal" value="${String(fator).replace('.', ',')}"
          aria-label="Quantas unidades em cada" title="Quantas unidades do insumo vêm em 1 ${item.unidade} da nota"></td>
        <td class="num right muted" data-entra="${item.numero}"></td>
      </tr>`;
  });
}

/** NF-e de compra: XML (chave opcional, só para conferir), com cada item ligado a um insumo. */
export function abrirImportacao(insumos, aoImportar) {
  let previa = null;
  let xml = null;
  modal({
    titulo: 'Importar nota fiscal de compra (NF-e)',
    largo: true,
    textoSalvar: 'Dar entrada no estoque',
    corpo: html`
      <p class="text-2" style="margin:0">Os itens vêm do <strong>arquivo XML</strong> da nota (NF-e) ou do cupom (NFC-e): ele chega no e-mail
        do CNPJ informado no caixa e também pode ser baixado no portal da nota fiscal. Só com o número da DANFE dá para conferir a nota,
        mas não ler os itens. Sem XML, use <strong>Compra sem nota (cupom)</strong>.</p>
      <div class="form-grid">
        <label class="field" style="grid-column:1/-1">Chave de acesso da DANFE (44 números, opcional)
          <input name="chave" inputmode="numeric" autocomplete="off" placeholder="3526 1011 2223 ..."></label>
        <label class="field" style="grid-column:1/-1">Arquivo XML da nota
          <input name="arquivo" type="file" accept=".xml,text/xml,application/xml"></label>
      </div>
      <div data-info class="muted" style="font-size:.9rem"></div>
      <div class="table-wrap" data-itens></div>
      <div class="form-grid hidden" data-pagamento>
        <label class="field">Como foi pago
          <select name="pagamento">
            <option value="PIX">PIX / conta (por fora do caixa)</option>
            <option value="DEBITO">Débito</option>
            <option value="CREDITO">Crédito</option>
            <option value="CAIXA">Dinheiro da gaveta (caixa aberto)</option>
            <option value="NAO">Já lancei o pagamento (não lançar de novo)</option>
          </select></label>
      </div>`,
    aoAbrir: (form) => {
      const info = form.querySelector('[data-info]');
      const mostrar = (conteudo) => { info.innerHTML = String(conteudo); };

      const atualizarEntradas = () => previa?.itens.forEach(({ item }) => {
        const escolha = form[`insumo-${item.numero}`].value;
        const insumo = escolha === NOVO ? palpiteNovo(item) : insumos.find((i) => i.id === Number(escolha));
        const fator = decimal(form[`fator-${item.numero}`].value) || 0;
        const entra = form.querySelector(`[data-entra="${item.numero}"]`);
        if (!insumo) { entra.textContent = 'ignorado'; return; }
        entra.textContent = `${escolha === NOVO ? 'novo · ' : ''}entra ${numero(item.quantidade * fator)} ${insumo.sigla}`;
      });

      // Ao trocar o insumo, a conversão sugerida acompanha a unidade dele.
      form.addEventListener('change', (ev) => {
        const numeroItem = /^insumo-(\d+)$/.exec(ev.target.name ?? '')?.[1];
        const linha = numeroItem && previa?.itens.find(({ item }) => String(item.numero) === numeroItem);
        if (!linha) return;
        const escolha = ev.target.value;
        const existente = insumos.find((i) => i.id === Number(escolha));
        const fator = escolha === NOVO ? palpiteNovo(linha.item).fator : existente ? fatorPara(linha.item, existente) : 1;
        form[`fator-${numeroItem}`].value = String(fator).replace('.', ',');
      });

      form.chave.addEventListener('input', async () => {
        const chave = form.chave.value.replace(/\D/g, '');
        if (chave.length !== 44) {
          mostrar(chave.length ? html`<span class="muted">${chave.length} de 44 números</span>` : '');
          return;
        }
        try {
          const r = await api.get(`/notas/chave/${chave}`);
          const c = r.chave;
          mostrar(html`Nota nº <strong>${c.numero}</strong> · série ${c.serie} · CNPJ ${c.emitenteCnpj} · ${c.emissao.split('-').reverse().join('/')}
            · ${r.jaImportada ? html`<span class="bad">⚠️ já foi importada</span>` : html`<span class="good">✅ ainda não importada</span>`}`);
        } catch (e) { mostrar(html`<span class="bad">${e.message}</span>`); }
      });

      form.arquivo.addEventListener('change', async () => {
        const arquivo = form.arquivo.files[0];
        if (!arquivo) return;
        if (arquivo.size > 1_000_000) { mostrar(html`<span class="bad">Arquivo grande demais para uma NF-e.</span>`); return; }
        xml = await arquivo.text();
        try {
          previa = await api.post('/notas/previa', { xml });
        } catch (e) { previa = null; mostrar(html`<span class="bad">${e.message}</span>`); return; }
        const n = previa.nota;
        const chaveDigitada = form.chave.value.replace(/\D/g, '');
        mostrar(html`<strong>${previa.fornecedor}</strong> · NF-e nº ${n.numero} · ${new Date(n.emitidaEm).toLocaleDateString('pt-BR')}
          · total <strong>${moeda(n.valorTotal)}</strong> · ${n.itens.length} itens
          ${previa.jaImportada ? html`<div class="bad">⚠️ Esta nota já foi importada.</div>` : ''}
          ${previa.paraOutroCnpj ? html`<div class="bad">⚠️ A nota foi emitida para outro CNPJ, não o da loja (Config. → Loja).</div>` : ''}
          ${chaveDigitada.length === 44 && chaveDigitada !== n.chave ? html`<div class="bad">⚠️ O XML é de outra nota, não da chave digitada.</div>` : ''}`);
        form.querySelector('[data-itens]').innerHTML = String(html`
          <table><thead><tr><th>Item da nota</th><th>Insumo do estoque</th><th>Unid. em cada</th><th></th></tr></thead>
            <tbody>${linhaItem(previa, insumos)}</tbody></table>
          <p class="muted" style="margin:6px 0 0;font-size:.8rem">"Unid. em cada": um fardo com 6 Cocas = 6; produto vendido por kg = 1.
            O sistema lembra a escolha para a próxima nota deste fornecedor.</p>`);
        form.querySelector('[data-pagamento]').classList.remove('hidden');
        atualizarEntradas();
      });
      form.addEventListener('input', atualizarEntradas);
      form.addEventListener('change', atualizarEntradas);
    },
    aoSalvar: async (d) => {
      if (!previa) {
        throw new Error('A chave só confere a nota. Para dar entrada nos itens, escolha também o arquivo XML da nota.');
      }
      if (previa.jaImportada) throw new Error('Esta nota já foi importada.');
      const itens = [];
      for (const { item } of previa.itens) {
        let insumoId = d[`insumo-${item.numero}`] ? Number(d[`insumo-${item.numero}`]) : null;
        if (d[`insumo-${item.numero}`] === NOVO) {
          insumoId = await criarInsumo(item, insumos);
        }
        itens.push({ numero: item.numero, insumoId, fator: decimal(d[`fator-${item.numero}`]) });
      }
      const pagamento = d.pagamento === 'NAO' ? null
        : { forma: d.pagamento === 'CAIXA' ? 'DINHEIRO' : d.pagamento, doCaixa: d.pagamento === 'CAIXA' };
      const r = await api.post('/notas/importar', { xml, itens, pagamento });
      toast(`Nota importada: ${r.entradas} item(ns) no estoque${r.ignorados ? `, ${r.ignorados} ignorado(s)` : ''} · ${moeda(r.valorTotal)}`);
      await aoImportar();
    },
  });
}
