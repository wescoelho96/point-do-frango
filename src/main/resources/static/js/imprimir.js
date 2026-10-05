// Cupom em bobina térmica (58 ou 80 mm) pelo diálogo de impressão do sistema, sem driver próprio.
// No Android depende de um serviço de impressão (app do fabricante ou RawBT).
// O cupom é montado em #impressao e o @media print esconde o resto da tela.
import { api } from './api.js';
import { dataHora, html, moeda, renderizar, rotulo, telefone } from './ui.js';

const CHAVE_LARGURA = 'pdf.impressora.largura';
let loja = null;

export function larguraImpressora() {
  try { return localStorage.getItem(CHAVE_LARGURA) || '80'; } catch { return '80'; }
}

export function definirLarguraImpressora(mm) {
  try { localStorage.setItem(CHAVE_LARGURA, mm); } catch { /* modo privado */ }
}

/** Dados da loja para o cabeçalho (cache até recarregar a página). */
export async function dadosLoja(recarregar = false) {
  if (!loja || recarregar) loja = await api.get('/loja');
  return loja;
}

const linha = (esq, dir, classe = '') => html`<div class="cupom-linha ${classe}"><span>${esq}</span><span>${dir}</span></div>`;

function cabecalho(l) {
  return html`
    <div class="cupom-centro">
      <strong class="cupom-titulo">${l.nome}</strong>
      ${l.documento ? html`<div>${l.documento}</div>` : ''}
      ${l.endereco ? html`<div>${l.endereco}</div>` : ''}
      ${l.telefone ? html`<div>${l.telefone}</div>` : ''}
    </div>
    <hr>`;
}

function rodape(l) {
  return html`
    <hr>
    ${l.mensagemRodape ? html`<div class="cupom-centro">${l.mensagemRodape}</div>` : ''}
    <div class="cupom-centro cupom-pequeno">Não é documento fiscal</div>`;
}

function mandarParaImpressora(conteudo, papel = larguraImpressora()) {
  const el = document.getElementById('impressao');
  document.body.dataset.papel = papel;
  renderizar(el, conteudo);
  const limpar = () => { el.innerHTML = ''; window.removeEventListener('afterprint', limpar); };
  window.addEventListener('afterprint', limpar);
  window.print();
}

/**
 * Conta da comanda. Na prévia (antes de pagar) mostra o total com e sem a taxa de serviço,
 * para o cliente decidir; depois de fechada, mostra o que foi pago.
 */
export async function imprimirConta(c, { cobrarServico = true } = {}) {
  const l = await dadosLoja();
  const fechada = c.status === 'FECHADA';
  const servico = fechada ? Number(c.valorServico) : cobrarServico ? Number(c.valorServico) : 0;
  const total = fechada ? Number(c.valorTotal) : Number(c.consumo) + servico;
  mandarParaImpressora(html`
    ${cabecalho(l)}
    <div class="cupom-centro"><strong>${fechada ? 'COMPROVANTE DE PAGAMENTO' : 'CONFERÊNCIA DE CONTA'}</strong></div>
    ${linha('Comanda', c.identificacao)}
    ${linha('Aberta', dataHora(c.abertaEm))}
    ${fechada ? linha('Fechada', dataHora(c.fechadaEm)) : linha('Impressa', dataHora(new Date().toISOString()))}
    <hr>
    ${c.itens.map((i) => html`
      <div>${i.quantidade}× ${i.produto}${i.brinde ? ' (BRINDE)' : ''}</div>
      ${i.brinde ? '' : linha(`   ${moeda(i.precoUnitario)} cada`, moeda(i.subtotal), 'cupom-pequeno')}`)}
    <hr>
    ${linha('Consumo', moeda(c.consumo))}
    ${servico > 0
      ? linha(`Serviço (${Number(c.percentualServico).toLocaleString('pt-BR')}%) opcional`, moeda(servico))
      : linha('Serviço', 'não cobrado')}
    ${linha('TOTAL', moeda(total), 'cupom-total')}
    ${!fechada && servico > 0 ? html`<div class="cupom-pequeno">Sem a taxa de serviço: ${moeda(c.consumo)}</div>` : ''}
    ${fechada ? linha('Pagamento', rotulo(c.formaPagamento)) : ''}
    ${fechada && c.valorRecebido != null ? html`${linha('Recebido', moeda(c.valorRecebido))}${linha('Troco', moeda(c.troco))}` : ''}
    ${rodape(l)}`);
}

/** Folha A4, usada no relatório do contador. */
export function imprimirDocumento(conteudo) {
  mandarParaImpressora(conteudo, 'a4');
}

/** Ticket para a cozinha (sem valores). */
export function imprimirTicketCozinha(p) {
  mandarParaImpressora(html`
    <div class="cupom-centro"><strong class="cupom-titulo">PEDIDO #${p.id}</strong></div>
    ${linha(rotulo(p.canal), dataHora(p.criadoEm))}
    ${p.comanda || p.clienteNome ? html`<div><strong>${p.comanda || p.clienteNome}</strong></div>` : ''}
    <hr>
    ${p.itens.map((i) => html`<div class="cupom-item-cozinha">${i.quantidade}× ${i.produto}${i.brinde ? ' (BRINDE)' : ''}</div>`)}
    ${p.observacao ? html`<hr><div><strong>OBS:</strong> ${p.observacao}</div>` : ''}
    ${p.enderecoEntrega ? html`<hr><div><strong>ENTREGA:</strong> ${p.enderecoEntrega}${p.entrega ? ` · ${p.entrega.bairro}` : ''}</div>` : ''}`);
}

/**
 * Cupom de venda direta (balcão/WhatsApp). Na entrega vira a via do motoboy: endereço, telefone,
 * quanto cobrar e quanto levar de troco.
 */
export async function imprimirVenda(p) {
  const l = await dadosLoja();
  const e = p.entrega;
  const total = Number(p.totalACobrar ?? p.valorTotal);
  mandarParaImpressora(html`
    ${cabecalho(l)}
    <div class="cupom-centro"><strong>${e ? 'ENTREGA' : 'PEDIDO'} #${p.id}</strong></div>
    ${linha(rotulo(p.canal), dataHora(p.criadoEm))}
    ${p.clienteNome ? linha('Cliente', p.clienteNome) : ''}
    ${e && p.clienteTelefone ? linha('Telefone', telefone(p.clienteTelefone)) : ''}
    ${e ? html`<div><strong>${p.enderecoEntrega}</strong></div><div>Bairro: <strong>${e.bairro}</strong></div>` : ''}
    <hr>
    ${p.itens.map((i) => linha(`${i.quantidade}× ${i.produto}`, i.brinde ? 'brinde' : moeda(i.subtotal)))}
    <hr>
    ${e ? html`${linha('Subtotal', moeda(p.valorTotal))}${linha(`Entrega (${e.bairro})`, e.gratis ? 'GRÁTIS' : moeda(e.taxaCobrada))}` : ''}
    ${linha(e ? 'COBRAR' : 'TOTAL', moeda(total), 'cupom-total')}
    ${linha('Pagamento', rotulo(p.formaPagamento))}
    ${p.valorRecebido != null ? html`
      ${linha(e ? 'Troco para' : 'Recebido', moeda(p.valorRecebido))}
      ${linha(e ? 'LEVAR DE TROCO' : 'Troco', moeda(p.troco), e ? 'cupom-total' : '')}` : ''}
    ${p.observacao ? html`<hr><div><strong>OBS:</strong> ${p.observacao}</div>` : ''}
    ${rodape(l)}`);
}
