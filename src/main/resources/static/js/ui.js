// Helpers de interface.
// XSS: todo valor interpolado na tag `html` é escapado. raw() serve só para HTML montado
// aqui mesmo, nunca para dado vindo do banco.

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const escapar = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ESCAPES[c]);

class Raw { constructor(v) { this.v = v; } toString() { return this.v; } }
export const raw = (v) => new Raw(v);

export function html(partes, ...valores) {
  return raw(partes.reduce((acc, parte, i) => {
    if (i === 0) return parte;
    const v = valores[i - 1];
    const s = Array.isArray(v) ? v.map((x) => (x instanceof Raw ? x.v : escapar(x))).join('')
      : v instanceof Raw ? v.v : escapar(v);
    return acc + s + parte;
  }, ''));
}

export function renderizar(el, conteudo) { el.innerHTML = String(conteudo); }

// ---------- Formatação ----------
const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
export const moeda = (v) => BRL.format(Number(v ?? 0));
export const numero = (v, casas = 3) =>
  Number(v ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: 0, maximumFractionDigits: casas });
export const pct = (v) => `${Number(v ?? 0).toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%`;
export const hora = (iso) => new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
export const dataHora = (iso) => new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
export const dataCurta = (isoDate) => {
  const [a, m, d] = isoDate.split('-');
  return `${d}/${m}`;
};
/** "11987654321" vira "(11) 98765-4321" */
export function telefone(t) {
  const d = String(t ?? '').replace(/\D/g, '');
  if (d.length === 11) return `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}`;
  if (d.length === 10) return `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`;
  return t ?? '';
}

export function hojeISO() {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}
export function somarDias(isoDate, dias) {
  const d = new Date(`${isoDate}T12:00:00`);
  d.setDate(d.getDate() + dias);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

export const ROTULOS = {
  BALCAO: 'Balcão', MESA: 'Mesa', WHATSAPP: 'WhatsApp', TELEFONE: 'Telefone', IFOOD: 'iFood', NOVENTA_NOVE_FOOD: '99Food',
  DINHEIRO: 'Dinheiro', PIX: 'PIX', DEBITO: 'Débito', CREDITO: 'Crédito', A_RECEBER: 'A receber (comanda aberta)', PAGO_NO_APP: 'Pago no app',
  EM_PREPARO: 'Em preparo', PRONTO: 'Pronto', ENTREGUE: 'Entregue', CANCELADO: 'Cancelado',
  ENTRADA: 'Entrada', SAIDA_VENDA: 'Venda', ESTORNO_VENDA: 'Estorno', AJUSTE: 'Ajuste',
  QUILOGRAMA: 'Quilograma (kg)', GRAMA: 'Grama (g)', LITRO: 'Litro (L)', MILILITRO: 'Mililitro (ml)', UNIDADE: 'Unidade (un)',
  PORCAO: 'Porção', ESPETINHO: 'Espetinho', COMBO: 'Combo', BEBIDA: 'Bebida', OUTRO: 'Outro',
  ADMIN: 'Administrador', ATENDENTE: 'Atendente', COZINHA: 'Cozinha',
  ABERTA: 'Aberta', FECHADA: 'Fechada', CANCELADA: 'Cancelada',
  ABERTO: 'Aberto', FECHADO: 'Fechado', SAIDA: 'Saída', SUPRIMENTO: 'Suprimento',
  MOTOBOY: 'Motoboy', FUNCIONARIO: 'Funcionário', FORNECEDOR: 'Compra / fornecedor', SANGRIA: 'Sangria (retirada)',
  INSUMOS: 'Compra de insumos / mercado', SALARIO: 'Salário', PRO_LABORE: 'Meu salário (pró-labore)',
  IMPOSTO: 'Imposto (DAS MEI / Simples)', CONTA_FIXA: 'Conta fixa', EQUIPAMENTO: 'Equipamento / manutenção',
  CONSUMO_INTERNO: 'Consumo da equipe', CORTESIA: '🎁 Cortesia', MEI: 'MEI', ME: 'ME (Simples Nacional)',
  PAGA: 'Paga', PARCIAL: 'Parcial', PENDENTE: 'Pendente', ATRASADA: 'Atrasada',
};
export const rotulo = (k) => ROTULOS[k] ?? k;

// Mesma ordem do DayOfWeek do Java; getDay() do JS começa no domingo
export const DIAS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
export const NOME_DIA = { MONDAY: 'Seg', TUESDAY: 'Ter', WEDNESDAY: 'Qua', THURSDAY: 'Qui', FRIDAY: 'Sex', SATURDAY: 'Sáb', SUNDAY: 'Dom' };
export const diaDeHoje = () => DIAS[(new Date().getDay() + 6) % 7];
export const textoDias = (dias) => DIAS.filter((d) => dias.includes(d)).map((d) => NOME_DIA[d]).join(', ');

// ---------- Toast ----------
export function toast(mensagem, tipo = 'ok') {
  const el = document.createElement('div');
  el.className = `toast ${tipo === 'erro' ? 'erro' : ''}`;
  el.textContent = mensagem;
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), tipo === 'erro' ? 7000 : 3500);
}
export const toastErro = (e) => toast(e?.message || 'Erro inesperado', 'erro');

// ---------- Modal ----------
// Abre um <dialog> com um formulário. `aoSalvar(dados, form)` recebe os campos;
// se lançar erro, o modal continua aberto e mostra a mensagem.
export function modal({ titulo, corpo, textoSalvar = 'Salvar', aoSalvar, aoAbrir, perigo = false, largo = false }) {
  const dlg = document.getElementById('modal');
  dlg.classList.toggle('largo', largo);
  renderizar(dlg, html`
    <form method="dialog" class="modal-form">
      <div class="modal-body">
        <h2>${titulo}</h2>
        ${corpo}
        <p class="bad hidden" data-erro role="alert"></p>
      </div>
      <div class="modal-actions">
        <button type="button" class="btn" data-fechar>${aoSalvar ? 'Cancelar' : 'Fechar'}</button>
        ${aoSalvar ? html`<button type="submit" class="btn ${perigo ? 'btn-danger' : 'btn-primary'}">${textoSalvar}</button>` : ''}
      </div>
    </form>`);
  const form = dlg.querySelector('form');
  const erro = dlg.querySelector('[data-erro]');
  dlg.querySelector('[data-fechar]').addEventListener('click', () => dlg.close());
  form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const botao = form.querySelector('[type=submit]');
    botao.disabled = true;
    try {
      await aoSalvar(Object.fromEntries(new FormData(form)), form);
      dlg.close();
    } catch (e) {
      erro.textContent = e.message;
      erro.classList.remove('hidden');
    } finally {
      botao.disabled = false;
    }
  });
  aoAbrir?.(form);
  dlg.showModal();
  form.querySelector('input, select, textarea')?.focus();
}

// Formato brasileiro: "0,300" = 0.3, "1.500" = 1500, "1.500,50" = 1500.5
export const decimal = (v) => {
  if (v === '' || v == null) return null;
  let s = String(v).trim().replace(/\s|R\$/g, '');
  if (s.includes(',')) s = s.replace(/\./g, '').replace(',', '.');
  else if (/^[1-9]\d{0,2}(\.\d{3})+$/.test(s)) s = s.replace(/\./g, '');
  return Number(s);
};

// Um listener por tela; a ação vem de data-acao.
export function aoClicar(container, acoes) {
  const handler = (ev) => {
    const alvo = ev.target.closest('[data-acao]');
    if (!alvo || !container.contains(alvo)) return;
    const fn = acoes[alvo.dataset.acao];
    if (fn) fn(alvo, ev);
  };
  container.addEventListener('click', handler);
  return () => container.removeEventListener('click', handler);
}
