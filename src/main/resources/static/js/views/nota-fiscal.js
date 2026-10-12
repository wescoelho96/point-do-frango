import { api } from '../api.js';
import { campoParcelas, decimal, html, ligarParcelas, modal, moeda, numero, parcelasDe, toast } from '../ui.js';
import { analisarItem, casarInsumo, fatorPara } from '../item-compra.js';

const semAcento = (x) => String(x ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();

const NOVO = 'novo';
const SIGLA = { UNIDADE: 'un', QUILOGRAMA: 'kg', LITRO: 'L' };
const GRUPOS_SUGERIDOS = ['Carnes', 'Congelados', 'Refrigerantes', 'Cervejas', 'Águas', 'Sucos', 'Óleos e molhos',
  'Mercearia', 'Embalagens', 'Limpeza'];

/** Cria o insumo novo com o nome, grupo e unidade da linha; reaproveita se já existir com o mesmo nome. */
async function criarInsumo({ nome, grupo, unidade }, insumos) {
  const existente = insumos.find((i) => semAcento(i.nome) === semAcento(nome));
  if (existente) return existente.id;
  const criado = await api.post('/insumos', {
    nome, unidade, grupo: grupo?.trim() || null, estoqueMinimo: 0, custoUnitario: 0, estoqueInicial: 0,
  });
  insumos.push(criado);
  return criado.id;
}

function linhaItem(previa, insumos) {
  return previa.itens.map(({ item, insumoSugerido, fatorSugerido }) => {
    const a = analisarItem(item.descricao, item.unidade);
    const insumo = insumos.find((i) => i.id === insumoSugerido) ?? casarInsumo(item.descricao, insumos);
    const escolha = insumo ? String(insumo.id) : (a.ignorar ? '' : NOVO);
    const fator = fatorSugerido ?? (insumo ? fatorPara(item.descricao, item.unidade, insumo) : a.fator);
    return html`
      <tr data-item="${item.numero}">
        <td>${item.descricao}<div class="muted" style="font-size:.78rem">${numero(item.quantidade)} ${item.unidade} · ${moeda(item.valor)}
          ${a.grupo ? html` · <span class="badge">${a.grupo}</span>` : ''}
          ${insumoSugerido ? html` · <span class="badge badge-good">lembrado da última compra</span>` : ''}</div></td>
        <td><select name="insumo-${item.numero}" aria-label="Insumo do item ${item.numero}">
            <option value="" ${escolha === '' ? 'selected' : ''}>— não é estoque (ignorar) —</option>
            <option value="${NOVO}" ${escolha === NOVO ? 'selected' : ''}>+ Cadastrar como novo insumo</option>
            ${insumos.filter((i) => i.ativo).map((i) => html`<option value="${i.id}" ${escolha === String(i.id) ? 'selected' : ''}>${i.nome}</option>`)}
          </select>
          <div class="form-grid ${escolha === NOVO ? '' : 'hidden'}" data-novo="${item.numero}" style="margin-top:6px;gap:6px">
            <input name="nome-${item.numero}" maxlength="100" value="${a.nome}" aria-label="Nome do novo insumo" title="Nome do novo insumo">
            <input name="grupo-${item.numero}" maxlength="40" value="${a.grupo ?? ''}" list="grupos-compra" placeholder="Grupo" aria-label="Grupo">
            <select name="unidade-${item.numero}" aria-label="Controlar estoque em">
              ${Object.entries(SIGLA).map(([u, s]) => html`<option value="${u}" ${a.unidade === u ? 'selected' : ''}>em ${s}</option>`)}
            </select>
          </div></td>
        <td style="width:110px"><input name="fator-${item.numero}" inputmode="decimal" value="${String(fator).replace('.', ',')}"
          aria-label="Quantas unidades em cada" title="Quantas unidades do insumo vêm em 1 ${item.unidade} da nota"></td>
        <td class="num right muted" data-entra="${item.numero}"></td>
      </tr>`;
  });
}

async function carregarLeitorQr() {
  if (window.jsQR) return window.jsQR;
  await new Promise((ok, falha) => {
    const script = document.createElement('script');
    script.src = '/js/vendor/jsQR.js';
    script.onload = ok;
    script.onerror = () => falha(new Error('Não foi possível carregar o leitor de QR Code.'));
    document.head.appendChild(script);
  });
  return window.jsQR;
}

/** Lê o QR Code da foto: leitor nativo do navegador quando existe (Android), senão jsQR em alguns tamanhos. */
async function lerQrCode(arquivo) {
  const imagem = await createImageBitmap(arquivo);
  if ('BarcodeDetector' in window) {
    try {
      const [achado] = await new window.BarcodeDetector({ formats: ['qr_code'] }).detect(imagem);
      if (achado?.rawValue) return achado.rawValue;
    } catch { /* segue para o jsQR */ }
  }
  const jsQR = await carregarLeitorQr();
  const W = imagem.width;
  const H = imagem.height;
  // Cupom térmico sai "granulado": reduzir a foto suaviza o ruído, por isso os tamanhos menores vêm primeiro.
  // Depois tenta recortes, para QR Code pequeno numa foto do cupom inteiro.
  const tentativas = [
    ...[600, 500, 700, 800, 450, 1000, 1300, 1600].map((lado) => [0, 0, W, H, lado]),
    ...[600, 800, 1000].flatMap((lado) => [[0, H * 0.4, W, H * 0.6, lado], [0, 0, W, H * 0.6, lado], [W * 0.15, H * 0.2, W * 0.7, H * 0.6, lado]]),
  ];
  for (const [sx, sy, sw, sh, lado] of tentativas) {
    const escala = Math.min(1, lado / Math.max(sw, sh));
    const largura = Math.round(sw * escala);
    const altura = Math.round(sh * escala);
    const tela = document.createElement('canvas');
    tela.width = largura;
    tela.height = altura;
    const ctx = tela.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(imagem, sx, sy, sw, sh, 0, 0, largura, altura);
    const lido = jsQR(ctx.getImageData(0, 0, largura, altura).data, largura, altura, { inversionAttempts: 'attemptBoth' });
    if (lido?.data) return lido.data;
  }
  throw new Error('Não encontrei o QR Code na foto. Fotografe só o QR Code, de perto, reto e com boa luz.');
}

/** Compra pela NF-e (XML), pelo cupom NFC-e (foto ou link do QR Code) ou só conferência pela chave. */
export function abrirImportacao(insumos, aoImportar) {
  let previa = null;
  let fonte = null; // { xml } ou { url }: o servidor relê a origem ao importar
  modal({
    titulo: 'Importar nota fiscal ou cupom',
    largo: true,
    textoSalvar: 'Dar entrada no estoque',
    corpo: html`
      <p class="text-2" style="margin:0"><strong>Cupom (NFC-e):</strong> fotografe o QR Code e os itens vêm da SEFAZ.
        <strong>Nota (NF-e):</strong> escolha o arquivo XML que chega no e-mail do CNPJ. Sem nenhum dos dois, use <strong>Compra sem nota</strong>.</p>
      <div class="form-grid">
        <label class="field">📷 Foto do QR Code do cupom (câmera ou galeria)
          <input name="foto" type="file" accept="image/*"></label>
        <label class="field">Arquivo XML da nota ou do cupom
          <input name="arquivo" type="file" accept=".xml,text/xml,application/xml"></label>
        <label class="field" style="grid-column:1/-1">Ou cole o link do QR Code
          <input name="link" inputmode="url" autocomplete="off" placeholder="https://www.nfce.fazenda.sp.gov.br/...?p=3526..."></label>
        <label class="field" style="grid-column:1/-1">Chave de acesso da DANFE (44 números, só para conferir)
          <input name="chave" inputmode="numeric" autocomplete="off" placeholder="3526 1011 2223 ..."></label>
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
        ${campoParcelas()}
      </div>`,
    aoAbrir: (form) => {
      ligarParcelas(form, 'pagamento', () => previa?.nota.valorTotal);
      const info = form.querySelector('[data-info]');
      const mostrar = (conteudo) => { info.innerHTML = String(conteudo); };

      const atualizarEntradas = () => previa?.itens.forEach(({ item }) => {
        const escolha = form[`insumo-${item.numero}`].value;
        const sigla = escolha === NOVO ? SIGLA[form[`unidade-${item.numero}`].value]
          : insumos.find((i) => i.id === Number(escolha))?.sigla;
        const quantidade = item.quantidade * (decimal(form[`fator-${item.numero}`].value) || 0);
        const entra = form.querySelector(`[data-entra="${item.numero}"]`);
        if (!sigla) { entra.textContent = 'ignorado'; return; }
        entra.textContent = `${escolha === NOVO ? 'novo · ' : ''}${numero(quantidade)} ${sigla}`
          + (quantidade ? ` · ${moeda(item.valor / quantidade)}/${sigla}` : '');
      });

      // Trocar o insumo (ou a unidade do novo) recalcula quantas unidades entram por item da nota.
      form.addEventListener('change', (ev) => {
        const [, campo, numeroItem] = /^(insumo|unidade)-(\d+)$/.exec(ev.target.name ?? '') ?? [];
        const linha = numeroItem && previa?.itens.find(({ item }) => String(item.numero) === numeroItem);
        if (!linha) return;
        const { item } = linha;
        const escolha = form[`insumo-${numeroItem}`].value;
        form.querySelector(`[data-novo="${numeroItem}"]`).classList.toggle('hidden', escolha !== NOVO);
        const existente = insumos.find((i) => i.id === Number(escolha));
        const sigla = escolha === NOVO ? SIGLA[form[`unidade-${numeroItem}`].value] : existente?.sigla;
        const fator = sigla ? fatorPara(item.descricao, item.unidade, { sigla }) : 1;
        if (campo === 'insumo' || campo === 'unidade') form[`fator-${numeroItem}`].value = String(fator).replace('.', ',');
      });

      const exibirPrevia = (tipo) => {
        const n = previa.nota;
        const chaveDigitada = form.chave.value.replace(/\D/g, '');
        mostrar(html`<strong>${previa.fornecedor}</strong> · ${tipo} nº ${n.numero} · ${new Date(n.emitidaEm).toLocaleDateString('pt-BR')}
          · total <strong>${moeda(n.valorTotal)}</strong> · ${n.itens.length} itens
          ${previa.jaImportada ? html`<div class="bad">⚠️ Esta compra já foi importada.</div>` : ''}
          ${previa.paraOutroCnpj ? html`<div class="bad">⚠️ A nota foi emitida para outro CNPJ, não o da loja (Config. → Loja).</div>` : ''}
          ${chaveDigitada.length === 44 && chaveDigitada !== n.chave ? html`<div class="bad">⚠️ A compra lida é de outra nota, não da chave digitada.</div>` : ''}`);
        form.querySelector('[data-itens]').innerHTML = String(html`
          <table><thead><tr><th>Item</th><th>Insumo do estoque</th><th>Unid. em cada</th><th>Entra</th></tr></thead>
            <tbody>${linhaItem(previa, insumos)}</tbody></table>
          <datalist id="grupos-compra">${GRUPOS_SUGERIDOS.map((g) => html`<option value="${g}">`)}</datalist>
          <p class="muted" style="margin:6px 0 0;font-size:.8rem">Grupo, pack e medida saem da descrição: "15X269ML" = 15 unidades,
            "C/50" = 50, "2KG" = 2 kg. Confira a coluna <strong>Entra</strong> (quantidade e custo de cada unidade).
            O sistema lembra as escolhas para a próxima compra deste fornecedor.</p>`);
        form.querySelector('[data-pagamento]').classList.remove('hidden');
        atualizarEntradas();
      };

      const carregar = async (novaFonte, tipo, aguarde) => {
        mostrar(html`<span class="muted">${aguarde}</span>`);
        try {
          previa = novaFonte.xml ? await api.post('/notas/previa', novaFonte) : await api.post('/notas/cupom/previa', novaFonte);
          fonte = novaFonte;
          exibirPrevia(tipo);
        } catch (e) {
          previa = null;
          fonte = null;
          form.querySelector('[data-itens]').innerHTML = '';
          mostrar(html`<span class="bad">${e.message}</span>`);
        }
      };

      form.foto.addEventListener('change', async () => {
        const foto = form.foto.files[0];
        if (!foto) return;
        mostrar(html`<span class="muted">Procurando o QR Code na foto…</span>`);
        try {
          const url = await lerQrCode(foto);
          form.link.value = url;
          await carregar({ url }, 'Cupom', 'Buscando os itens do cupom na SEFAZ…');
        } catch (e) { mostrar(html`<span class="bad">${e.message}</span>`); }
      });

      form.link.addEventListener('change', () => {
        const url = form.link.value.trim();
        if (url) carregar({ url }, 'Cupom', 'Buscando os itens do cupom na SEFAZ…');
      });

      form.arquivo.addEventListener('change', async () => {
        const arquivo = form.arquivo.files[0];
        if (!arquivo) return;
        if (arquivo.size > 1_000_000) { mostrar(html`<span class="bad">Arquivo grande demais para uma NF-e.</span>`); return; }
        await carregar({ xml: await arquivo.text() }, 'Nota', 'Lendo o XML…');
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
      form.addEventListener('input', atualizarEntradas);
      form.addEventListener('change', atualizarEntradas);
    },
    aoSalvar: async (d) => {
      if (!previa) {
        throw new Error('Fotografe o QR Code do cupom ou escolha o XML da nota. Só a chave confere a nota, mas não traz os itens.');
      }
      if (previa.jaImportada) throw new Error('Esta compra já foi importada.');
      const itens = [];
      for (const { item } of previa.itens) {
        let insumoId = d[`insumo-${item.numero}`] ? Number(d[`insumo-${item.numero}`]) : null;
        if (d[`insumo-${item.numero}`] === NOVO) {
          const nome = d[`nome-${item.numero}`]?.trim();
          if (!nome) throw new Error(`Dê um nome ao novo insumo do item ${item.numero} (${item.descricao}).`);
          insumoId = await criarInsumo({ nome, grupo: d[`grupo-${item.numero}`], unidade: d[`unidade-${item.numero}`] }, insumos);
        }
        itens.push({ numero: item.numero, insumoId, fator: decimal(d[`fator-${item.numero}`]) });
      }
      const pagamento = d.pagamento === 'NAO' ? null
        : { forma: d.pagamento === 'CAIXA' ? 'DINHEIRO' : d.pagamento, doCaixa: d.pagamento === 'CAIXA', parcelas: parcelasDe(d, 'pagamento') };
      const r = await api.post('/notas/importar', { ...fonte, itens, pagamento });
      toast(`Compra importada: ${r.entradas} item(ns) no estoque${r.ignorados ? `, ${r.ignorados} ignorado(s)` : ''} · ${moeda(r.valorTotal)}`);
      await aoImportar();
    },
  });
}
