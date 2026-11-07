// Cardápio online: o cliente escolhe, vê a taxa do bairro e o pedido vai direto para a cozinha.
// Página pública e independente do app da equipe (sem login, sem os módulos do app).
const CHAVE_DADOS = 'pdf.cliente';
const $ = (id) => document.getElementById(id);
const moeda = (v) => Number(v || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const escapar = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const decimal = (s) => {
  const v = Number(String(s ?? '').trim().replace(/\./g, '').replace(',', '.'));
  return Number.isFinite(v) && String(s ?? '').trim() !== '' ? v : null;
};

let cardapio = null;
const carrinho = new Map(); // produtoId -> quantidade

async function carregar() {
  try {
    const r = await fetch('/api/v1/publico/cardapio');
    if (!r.ok) throw new Error();
    cardapio = await r.json();
  } catch {
    $('situacao').textContent = 'Não foi possível carregar o cardápio. Atualize a página.';
    return;
  }
  document.title = `Fazer pedido · ${cardapio.loja}`;
  $('loja').textContent = cardapio.loja;
  $('situacao').textContent = cardapio.aberto
    ? `Aberto agora${cardapio.entregaGratisHoje ? ' · 🎉 entrega grátis hoje' : ''}` : 'Fechado agora: dá para olhar o cardápio, mas os pedidos só entram com a loja aberta.';
  $('situacao').className = `situacao ${cardapio.aberto ? 'aberta' : 'fechada'}`;
  desenharCardapio();
  preencherCheckout();
}

// Combos primeiro, bebidas por último
const ORDEM = ['COMBO', 'PORCAO', 'ESPETINHO', 'OUTRO', 'BEBIDA'];

function desenharCardapio() {
  const grupos = new Map();
  [...cardapio.itens].sort((a, b) => ORDEM.indexOf(a.categoria) - ORDEM.indexOf(b.categoria)).forEach((i) => {
    if (!grupos.has(i.categoriaRotulo)) grupos.set(i.categoriaRotulo, []);
    grupos.get(i.categoriaRotulo).push(i);
  });
  $('cardapio').innerHTML = [...grupos].map(([categoria, itens]) => `
    <h3>${escapar(categoria)}</h3>
    ${itens.map((i) => `
      <div class="produto ${i.disponivel ? '' : 'indisponivel'}">
        <div>
          <div class="nome">${escapar(i.nome)}</div>
          ${i.descricao ? `<div class="desc">${escapar(i.descricao)}</div>` : ''}
          <div class="preco">${i.disponivel ? moeda(i.preco) : 'Esgotado hoje'}</div>
        </div>
        ${i.disponivel ? `<div class="qtd">
          <button type="button" data-menos="${i.id}" aria-label="Tirar ${escapar(i.nome)}" ${carrinho.get(i.id) ? '' : 'disabled'}>−</button>
          <span>${carrinho.get(i.id) || 0}</span>
          <button type="button" class="mais" data-mais="${i.id}" aria-label="Adicionar ${escapar(i.nome)}">+</button>
        </div>` : ''}
      </div>`).join('')}`).join('');
  atualizarBarra();
}

const itemPorId = (id) => cardapio.itens.find((i) => i.id === id);
const subtotal = () => [...carrinho].reduce((s, [id, q]) => s + Number(itemPorId(id).preco) * q, 0);
const entrega = () => document.querySelector('[name=receber]:checked').value === 'entrega';
const bairro = () => cardapio.bairros.find((b) => b.id === Number($('checkout').bairroId.value));
const taxa = () => (entrega() && bairro() ? Number(bairro().taxa) : 0);

function atualizarBarra() {
  const qtd = [...carrinho.values()].reduce((s, q) => s + q, 0);
  $('barra').classList.toggle('hidden', !qtd || !$('checkout').classList.contains('hidden'));
  $('barra-qtd').textContent = `${qtd} ${qtd === 1 ? 'item' : 'itens'}`;
  $('barra-total').textContent = moeda(subtotal());
}

$('cardapio').addEventListener('click', (ev) => {
  const mais = ev.target.closest('[data-mais]');
  const menos = ev.target.closest('[data-menos]');
  if (!mais && !menos) return;
  const id = Number((mais || menos).dataset.mais || (mais || menos).dataset.menos);
  const q = (carrinho.get(id) || 0) + (mais ? 1 : -1);
  if (q <= 0) carrinho.delete(id); else carrinho.set(id, Math.min(q, 20));
  desenharCardapio();
});

function preencherCheckout() {
  const form = $('checkout');
  form.bairroId.innerHTML = `<option value="">Escolha o bairro</option>${cardapio.bairros.map((b) => `
    <option value="${b.id}">${escapar(b.nome)} · ${Number(b.taxa) ? moeda(b.taxa) : 'grátis'}</option>`).join('')}`;
  if (!cardapio.bairros.length) {
    form.querySelector('[value=retirada]').checked = true;
    form.querySelector('[value=entrega]').closest('label').classList.add('hidden');
  }
  $('endereco-loja').textContent = cardapio.endereco ? `Retire em: ${cardapio.endereco}` : '';
  try {
    const salvo = JSON.parse(localStorage.getItem(CHAVE_DADOS) || 'null');
    if (salvo) {
      ['nome', 'telefone', 'endereco', 'referencia'].forEach((k) => { if (salvo[k]) form[k].value = salvo[k]; });
      if (salvo.bairroId && cardapio.bairros.some((b) => b.id === salvo.bairroId)) form.bairroId.value = salvo.bairroId;
      form.salvarDados.checked = true;
    }
  } catch { /* navegador sem armazenamento */ }
  atualizarCheckout();
}

function atualizarCheckout() {
  const form = $('checkout');
  $('campos-entrega').classList.toggle('hidden', !entrega());
  $('endereco-loja').classList.toggle('hidden', entrega() || !cardapio.endereco);
  form.bairroId.required = entrega();
  form.endereco.required = entrega();
  $('campo-troco').classList.toggle('hidden', form.formaPagamento.value !== 'DINHEIRO');
  $('resumo-itens').innerHTML = [...carrinho].map(([id, q]) => {
    const i = itemPorId(id);
    return `<div class="linha"><span>${q}× ${escapar(i.nome)}</span><span>${moeda(Number(i.preco) * q)}</span></div>`;
  }).join('');
  const total = subtotal() + taxa();
  $('totais').innerHTML = `
    <div class="linha"><span>Itens</span><span>${moeda(subtotal())}</span></div>
    ${entrega() ? `<div class="linha"><span>Entrega${bairro() ? ` (${escapar(bairro().nome)})` : ''}</span>
      <span>${bairro() ? (taxa() ? moeda(taxa()) : 'grátis') : 'escolha o bairro'}</span></div>` : ''}
    <div class="linha total"><span>Total</span><span>${moeda(total)}</span></div>`;
}

function abrirCheckout(abrir) {
  $('cardapio').classList.toggle('hidden', abrir);
  $('checkout').classList.toggle('hidden', !abrir);
  atualizarBarra();
  atualizarCheckout();
  window.scrollTo(0, 0);
}

document.querySelector('[data-continuar]').addEventListener('click', () => abrirCheckout(true));
document.querySelector('[data-voltar]').addEventListener('click', () => abrirCheckout(false));
$('checkout').addEventListener('change', atualizarCheckout);
$('checkout').addEventListener('input', atualizarCheckout);

$('checkout').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const form = ev.target;
  const erro = $('erro');
  erro.classList.add('hidden');
  const falhar = (msg) => { erro.textContent = msg; erro.classList.remove('hidden'); };
  if (!cardapio.aberto) return falhar('A loja está fechada agora. Os pedidos entram quando ela abrir.');
  if (!form.nome.value.trim()) return falhar('Informe seu nome.');
  if (form.telefone.value.replace(/\D/g, '').length < 10) return falhar('Informe o telefone com DDD.');
  if (entrega() && !bairro()) return falhar('Escolha o bairro da entrega.');
  if (entrega() && !form.endereco.value.trim()) return falhar('Informe o endereço de entrega.');
  const troco = form.formaPagamento.value === 'DINHEIRO' ? decimal(form.trocoPara.value) : null;
  if (troco != null && troco < subtotal() + taxa()) return falhar(`O troco tem que ser para um valor acima de ${moeda(subtotal() + taxa())}.`);

  const corpo = {
    nome: form.nome.value.trim(),
    telefone: form.telefone.value.trim(),
    bairroId: entrega() ? bairro().id : null,
    endereco: entrega() ? form.endereco.value.trim() : null,
    referencia: entrega() ? form.referencia.value.trim() || null : null,
    formaPagamento: form.formaPagamento.value,
    trocoPara: troco,
    observacao: form.observacao.value.trim() || null,
    salvarDados: form.salvarDados.checked,
    itens: [...carrinho].map(([produtoId, quantidade]) => ({ produtoId, quantidade })),
    site: form.site.value,
  };
  const botao = form.querySelector('[type=submit]');
  botao.disabled = true;
  botao.textContent = 'Enviando…';
  try {
    const r = await fetch('/api/v1/publico/pedidos', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(corpo),
    });
    const resposta = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(resposta.detail || 'Não foi possível enviar o pedido. Tente de novo.');
    try {
      if (corpo.salvarDados) {
        localStorage.setItem(CHAVE_DADOS, JSON.stringify({ nome: corpo.nome, telefone: corpo.telefone,
          endereco: corpo.endereco, referencia: corpo.referencia, bairroId: corpo.bairroId }));
      } else {
        localStorage.removeItem(CHAVE_DADOS);
      }
    } catch { /* sem armazenamento */ }
    confirmar(resposta, corpo);
  } catch (e) {
    falhar(e.message);
  } finally {
    botao.disabled = false;
    botao.textContent = 'Enviar pedido';
  }
});

function confirmar(p, corpo) {
  carrinho.clear();
  $('checkout').classList.add('hidden');
  $('barra').classList.add('hidden');
  const telefoneLoja = (cardapio.telefone || '').replace(/\D/g, '');
  const texto = `Olá! Fiz o pedido nº ${p.numero} pelo site (${moeda(p.total)}, ${p.entrega ? 'entrega' : 'retirada'}).`;
  const whatsapp = telefoneLoja ? `https://wa.me/${telefoneLoja.length <= 11 ? `55${telefoneLoja}` : telefoneLoja}?text=${encodeURIComponent(texto)}` : null;
  $('confirmacao').innerHTML = `
    <div class="confirmado">
      <p class="grande">✅</p>
      <h2>Pedido nº ${escapar(p.numero)} recebido!</h2>
      <p>Já foi para a cozinha. ${p.entrega ? 'Vamos levar no endereço informado.' : 'Avisamos quando estiver pronto para retirar.'}</p>
      <div class="caixa" style="text-align:left">
        <div class="linha"><span>Itens</span><span>${moeda(p.itens)}</span></div>
        ${p.entrega ? `<div class="linha"><span>Entrega</span><span>${Number(p.taxaEntrega) ? moeda(p.taxaEntrega) : 'grátis'}</span></div>` : ''}
        <div class="linha total"><span>Total</span><span>${moeda(p.total)}</span></div>
        ${p.troco != null ? `<div class="linha"><span>Troco (para ${moeda(corpo.trocoPara)})</span><span>${moeda(p.troco)}</span></div>` : ''}
      </div>
      ${whatsapp ? `<a class="botao-principal" href="${whatsapp}" target="_blank" rel="noopener">💬 Avisar a loja no WhatsApp</a>` : ''}
    </div>`;
  $('confirmacao').classList.remove('hidden');
  window.scrollTo(0, 0);
}

carregar();
