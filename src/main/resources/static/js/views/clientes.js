import { api } from '../api.js';
import { aoClicar, dataHora, html, modal, renderizar, telefone, toast, toastErro } from '../ui.js';

export async function montar(container) {
  let clientes = [];
  let bairros = [];
  let termo = '';
  let espera = null;

  async function carregar() {
    [clientes, bairros] = await Promise.all([
      api.get(`/clientes${termo ? `?termo=${encodeURIComponent(termo)}` : ''}`), api.get('/bairros'),
    ]);
    desenharLista();
  }

  function desenhar() {
    renderizar(container, html`
      <div class="page-head">
        <div><h1>Clientes</h1><p>Cadastrados automaticamente nos pedidos de entrega (WhatsApp/telefone), quando o atendente marca "salvar cliente".</p></div>
        <input type="search" data-campo="busca" placeholder="Buscar por nome ou telefone" maxlength="50" style="max-width:280px" value="${termo}">
      </div>
      <div class="card table-wrap" data-lista></div>
      <p class="muted" style="font-size:.8rem;margin-top:12px">LGPD: guardamos só nome, telefone e endereço, para entregar. Se o cliente pedir,
        use "Apagar dados": o cadastro some e os pedidos antigos dele ficam sem nome, telefone e endereço (os valores continuam no financeiro).
        Clientes que não pedem há mais de 1 ano são apagados automaticamente.</p>`);
  }

  function desenharLista() {
    const alvo = container.querySelector('[data-lista]');
    renderizar(alvo, clientes.length ? html`<table>
      <thead><tr><th>Cliente</th><th>Endereço</th><th>Último pedido</th><th></th></tr></thead>
      <tbody>${clientes.map((c) => html`
        <tr>
          <td><strong>${c.nome}</strong><div class="muted" style="font-size:.8rem">${telefone(c.telefone)}</div></td>
          <td>${c.endereco || html`<span class="muted">—</span>`}${c.referencia ? html`<div class="muted" style="font-size:.78rem">${c.referencia}</div>` : ''}
            ${c.bairro ? html`<div class="muted" style="font-size:.78rem">${c.bairro}</div>` : ''}</td>
          <td class="num">${dataHora(c.ultimoPedidoEm)}</td>
          <td class="right" style="white-space:nowrap">
            <button class="btn btn-sm" data-acao="editar" data-id="${c.id}">Editar</button>
            <button class="btn btn-sm btn-danger" data-acao="apagar" data-id="${c.id}">Apagar dados</button>
          </td>
        </tr>`)}</tbody></table>` : html`<div class="empty">${termo ? 'Nenhum cliente encontrado.' : 'Nenhum cliente cadastrado ainda.'}</div>`);
  }

  const cliente = (id) => clientes.find((c) => c.id === Number(id));

  const soltar = aoClicar(container, {
    editar: (el) => {
      const c = cliente(el.dataset.id);
      modal({
        titulo: `Editar ${c.nome}`,
        corpo: html`
          <p class="muted" style="margin:0">Telefone: ${telefone(c.telefone)}</p>
          <div class="form-grid">
            <label class="field" style="grid-column:1/-1">Nome<input name="nome" required maxlength="100" value="${c.nome}"></label>
            <label class="field" style="grid-column:1/-1">Endereço<input name="endereco" maxlength="200" value="${c.endereco ?? ''}"></label>
            <label class="field">Referência<input name="referencia" maxlength="150" value="${c.referencia ?? ''}"></label>
            <label class="field">Bairro<select name="bairroId"><option value="">—</option>
              ${bairros.map((b) => html`<option value="${b.id}" ${b.id === c.bairroId ? 'selected' : ''}>${b.nome}</option>`)}</select></label>
          </div>`,
        aoSalvar: async (d) => {
          await api.put(`/clientes/${c.id}`, {
            nome: d.nome, endereco: d.endereco || null, referencia: d.referencia || null, bairroId: d.bairroId ? Number(d.bairroId) : null,
          });
          toast('Cliente atualizado');
          await carregar();
        },
      });
    },
    apagar: (el) => {
      const c = cliente(el.dataset.id);
      modal({
        titulo: `Apagar dados de ${c.nome}`,
        corpo: html`<p class="text-2">O cadastro é excluído e os pedidos antigos deste cliente ficam sem nome, telefone e endereço.
          Não dá para desfazer.</p>`,
        textoSalvar: 'Apagar definitivamente',
        perigo: true,
        aoSalvar: async () => {
          await api.del(`/clientes/${c.id}`);
          toast('Dados do cliente apagados');
          await carregar();
        },
      });
    },
  });

  const aoBuscar = (ev) => {
    if (ev.target.dataset.campo !== 'busca') return;
    clearTimeout(espera);
    espera = setTimeout(() => { termo = ev.target.value.trim(); carregar().catch(toastErro); }, 300);
  };
  container.addEventListener('input', aoBuscar);

  desenhar();
  await carregar();
  return () => { soltar(); clearTimeout(espera); container.removeEventListener('input', aoBuscar); };
}
