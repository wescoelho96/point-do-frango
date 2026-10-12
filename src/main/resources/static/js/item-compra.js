// Interpreta a descrição abreviada de um item de nota ou cupom ("CERV OR MUL 15X269ML"):
// grupo do estoque, quantidade por pack, medida, nome legível e qual insumo já cadastrado corresponde.

const semAcento = (x) => String(x ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

const ABREVIACOES = {
  cerv: 'cerveja', ref: 'refrigerante', refri: 'refrigerante', bat: 'batata', cong: 'congelada', ol: 'oleo',
  sj: 'soja', mol: 'molho', far: 'farinha', gd: 'guardanapo', cx: 'caixa', sac: 'saco', toal: 'toalha',
  umed: 'umedecida', det: 'detergente', lar: 'laranja', laran: 'laranja', fgo: 'frango', chedd: 'cheddar',
  or: 'original', pap: 'papel', cap: 'capsula', neu: 'neutro', suquita: 'sukita', refrigerantes: 'refrigerante',
  salg: 'salgado', trad: 'tradicional', choc: 'chocolate', morang: 'morango',
};
const DESCARTAR = new Set(['mul', 'lv', 'tpl', 'rcp', 'vde', 'un', 'unid', 'pc', 'pct', 'fd', 'de', 'da', 'do', 'com', 'e',
  'lata', 'garrafa', 'pet', 'gf', 'la', 'am', 'kg', 'g', 'ml', 'l', 'lt', 'c', 'x']);
const COM_ACENTO = { oleo: 'Óleo', agua: 'Água', guarana: 'Guaraná', acucar: 'Açúcar', file: 'Filé', linguica: 'Linguiça',
  itubaina: 'Itubaína', capsula: 'Cápsula', aneis: 'Anéis' };

const GRUPOS = [
  ['ignorar', /talco|pirulito|\bbala\b|chiclete|shampoo|sabonete|toalha umedecida|papel higienico|^t papel|saco assai|sacola|\bfralda/],
  ['Limpeza', /detergente|limpol|\bsabao\b|desinfet|sanitaria|esponja|\balcool\b|multiuso/],
  ['Cervejas', /cerveja|heineken|budweiser|brahma|\bskol\b|stella|amstel|\bspaten\b|itaipava|petra/],
  ['Refrigerantes', /refrigerante|cocacola|guarana|sukita|sprite|fanta|itubaina|pepsi|schweppes|soda/],
  ['Águas', /\bagua\b/],
  ['Sucos', /\bsuco\b|del valle|tampico/],
  ['Carnes', /frango|sassami|\bfile\b|peito|coxa|\bcarne\b|bacon|calabresa|linguica|filezinho/],
  ['Congelados', /batata|anel|aneis|onion|nugget|kibe|empanad|congelad/],
  ['Óleos e molhos', /\boleo\b|molho|catchup|ketchup|maionese|mostarda|barbecue|cheddar/],
  ['Mercearia', /farinha|trigo|\bsal\b|acucar|tempero|\bfuba\b|panko|\bovo/],
  ['Embalagens', /caixa|embalag|\bsaco\b|tampa|\bpote\b|marmit|\bcopo\b|guardanapo|canudo|\bpapel\b|bobina|\bsache/],
];
const BEBIDAS = new Set(['Cervejas', 'Refrigerantes', 'Águas', 'Sucos']);

/** Texto normalizado: sem acento, abreviações expandidas, "coca cola" num token só. */
function normalizar(descricao) {
  let t = semAcento(descricao)
    .replace(/s\s*\/\s*ac(ucar)?\b/g, ' zero ')
    .replace(/coca[\s-]*cola|\bcoca\b/g, ' cocacola ')
    .replace(/(\d)\s*lt\b/g, '$1l')
    .replace(/(\d)\s*k\b/g, '$1kg');
  t = t.split(/\s+/).map((p) => ABREVIACOES[p] ?? p).join(' ');
  return t;
}

const numeroBr = (s) => Number(String(s).replace(',', '.'));

/** Medida em kg ou litros (0,5 kg, 0,9 L) e quantas unidades vêm no pack. */
function medidas(texto) {
  let pack = null;
  let medida = null;
  const packComMedida = /(\d{1,3})\s*x\s*(\d+(?:[.,]\d+)?)\s*(ml|l|g|kg)\b/.exec(texto);
  if (packComMedida) {
    pack = Number(packComMedida[1]);
    medida = converter(numeroBr(packComMedida[2]), packComMedida[3]);
  } else {
    const m = /(\d+(?:[.,]\d+)?)\s*(ml|l|g|kg)\b/.exec(texto);
    if (m) medida = converter(numeroBr(m[1]), m[2]);
    const p = /\bc\s*\/\s*(\d{1,3})\b/.exec(texto) ?? /\b(?:caixa|fardo|pack|pct|fd|emb)\s*(?:c\s*\/\s*)?(\d{1,3})\b/.exec(texto)
      ?? /\b(\d{1,3})\s*(?:un|unid|unidades)\b/.exec(texto);
    if (p) pack = Number(p[1]);
  }
  return { pack: pack && pack > 1 ? pack : null, medida };
}

function converter(valor, unidade) {
  if (unidade === 'kg') return { tipo: 'kg', valor };
  if (unidade === 'g') return { tipo: 'kg', valor: valor / 1000 };
  if (unidade === 'ml') return { tipo: 'L', valor: valor / 1000 };
  return { tipo: 'L', valor };
}

const rotuloMedida = (m) => (m.tipo === 'kg'
  ? (m.valor < 1 ? `${Math.round(m.valor * 1000)} g` : `${String(m.valor).replace('.', ',')} kg`)
  : (m.valor < 1 ? `${Math.round(m.valor * 1000)} ml` : `${String(m.valor).replace('.', ',')} L`));

/** Palavras que identificam o produto (sem medidas, packs e siglas de embalagem). */
function palavrasDe(texto) {
  return texto
    .replace(/\d{1,3}\s*x\s*\d+(?:[.,]\d+)?\s*(ml|l|g|kg)\b/g, ' ')
    .replace(/\d+(?:[.,]\d+)?\s*(ml|l|g|kg)\b/g, ' ')
    .replace(/\bc\s*\/\s*\w+/g, ' ')
    .split(/[^a-z0-9]+/)
    .filter((p) => p.length >= 2 && !DESCARTAR.has(p) && !/^\d+$/.test(p) && !/^n\d+$/.test(p));
}

function capitalizar(palavra) {
  if (COM_ACENTO[palavra]) return COM_ACENTO[palavra];
  if (palavra === 'cocacola') return 'Coca-Cola';
  return palavra.charAt(0).toUpperCase() + palavra.slice(1);
}

/**
 * Tudo o que dá para tirar da descrição: grupo, se deve ser ignorado, nome do insumo novo,
 * em que unidade controlar e quantas unidades do insumo entram por item da nota.
 */
export function analisarItem(descricao, unidadeDaNota = '') {
  const texto = normalizar(descricao);
  const { pack, medida } = medidas(texto);
  const achado = GRUPOS.find(([, regra]) => regra.test(texto));
  const grupo = achado && achado[0] !== 'ignorar' ? achado[0] : null;
  const ignorar = !!achado && (achado[0] === 'ignorar' || achado[0] === 'Limpeza');
  const porPeso = /^kg/i.test(unidadeDaNota);

  let unidade = 'UNIDADE';
  let sigla = 'un';
  let fator = pack ?? 1;
  if (porPeso) {
    unidade = 'QUILOGRAMA'; sigla = 'kg'; fator = 1;
  } else if (!BEBIDAS.has(grupo) && medida) {
    // Batata 2 kg, óleo 900 ml: controla em kg/L; bebida é vendida por unidade, então fica em un.
    unidade = medida.tipo === 'kg' ? 'QUILOGRAMA' : 'LITRO';
    sigla = medida.tipo;
    fator = medida.valor * (pack ?? 1);
  }

  const palavras = palavrasDe(texto);
  const comMedida = sigla === 'un' && medida ? ` ${rotuloMedida(medida)}` : '';
  const nome = `${palavras.map(capitalizar).join(' ')}${comMedida}`.trim().slice(0, 100) || descricao.slice(0, 100);
  return { grupo, ignorar, pack, medida, unidade, sigla, fator: Math.round(fator * 1000) / 1000, nome };
}

/** Quantas unidades do insumo já cadastrado entram por item da nota, conforme a unidade dele. */
export function fatorPara(descricao, unidadeDaNota, insumo) {
  const a = analisarItem(descricao, unidadeDaNota);
  if (insumo.sigla === 'un') return a.pack ?? 1;
  if (/^kg/i.test(unidadeDaNota)) return 1;
  if (a.medida && a.medida.tipo === insumo.sigla) return Math.round(a.medida.valor * (a.pack ?? 1) * 1000) / 1000;
  return 1;
}

/**
 * Insumo cadastrado que corresponde ao item: palavras em comum, mesma medida (350 ml não casa com 2 L)
 * e penalidade para palavras do insumo que não estão na nota (Sukita Uva não casa com Sukita Laranja).
 */
export function casarInsumo(descricao, insumos) {
  const texto = normalizar(descricao);
  const daNota = new Set(palavrasDe(texto));
  const medidaNota = medidas(texto).medida;
  let melhor = null;
  let melhorPontos = 1.99;
  insumos.filter((i) => i.ativo).forEach((i) => {
    const textoInsumo = normalizar(i.nome);
    const doInsumo = palavrasDe(textoInsumo);
    const medidaInsumo = medidas(textoInsumo).medida;
    let pontos = 0;
    if (medidaNota && medidaInsumo) {
      if (medidaNota.tipo !== medidaInsumo.tipo || Math.abs(medidaNota.valor - medidaInsumo.valor) > 1e-6) return;
      pontos += 1;
    }
    const comuns = doInsumo.filter((p) => daNota.has(p)).length;
    if (!comuns) return;
    pontos += comuns - 0.5 * (doInsumo.length - comuns);
    if (pontos > melhorPontos) { melhor = i; melhorPontos = pontos; }
  });
  return melhor;
}
