import { api, sessao } from '../api.js';
import { aoClicar, DIAS, decimal, html, modal, moeda, NOME_DIA, numero, pct, renderizar, rotulo, toast, toastErro } from '../ui.js';
import { dadosLoja, definirLarguraImpressora, imprimirVenda, larguraImpressora } from '../imprimir.js';

// Mesmos nomes do index.html; aparecem como sugestão quando o campo está vazio
const MENUS = [
  ['pdv', 'PDV'], ['comandas', 'Comandas'], ['cozinha', 'Cozinha'], ['pedidos', 'Pedidos'], ['entregas', 'Entregas'],
  ['caixa', 'Caixa'], ['clientes', 'Clientes'], ['estoque', 'Estoque'], ['cardapio', 'Cardápio'],
  ['financeiro', 'Financeiro'], ['config', 'Config.'],
];

const nomeMenu = (t) => {
  const b = document.querySelector(`.nav-btn[data-view="${t}"] span`);
  return b?.textContent || MENUS.find(([c]) => c === t)?.[1] || t;
};

// Telas que cada perfil pode receber (o backend bloqueia o resto por perfil, ex.: financeiro e clientes só o dono)
const TELAS_DO_PERFIL = {
  ADMIN: MENUS.map(([t]) => t),
  ATENDENTE: ['pdv', 'comandas', 'cozinha', 'pedidos', 'entregas', 'caixa', 'estoque'],
  COZINHA: ['cozinha'],
};

export async function montar(container) {
  let cfg; let despesas; let usuarios; let loja; let equipe;

  async function carregar() {
    [cfg, despesas, usuarios, loja, equipe] = await Promise.all([
      api.get('/financeiro/configuracao'), api.get('/financeiro/despesas'), api.get('/usuarios'), api.get('/loja'),
      api.get('/colaboradores'),
    ]);
    desenhar();
  }

  // Mesma conta do ConfiguracaoFinanceira.diasDeTrabalho, para o mês atual
  function diasDeTrabalho(dias) {
    const hoje = new Date();
    const ultimo = new Date(hoje.getFullYear(), hoje.getMonth() + 1, 0).getDate();
    let n = 0;
    for (let d = 1; d <= ultimo; d++) {
      // getDay: 0 = domingo; DIAS começa na segunda
      if (dias.includes(DIAS[(new Date(hoje.getFullYear(), hoje.getMonth(), d).getDay() + 6) % 7])) n++;
    }
    return n;
  }

  // Mesmo cálculo do RateioFinanceiro.java, só para a pré-visualização
  function exemplo(c, totalContas) {
    const bruto = 100; const cmv = 30;
    const taxa = bruto * c.taxaCredito / 100;
    if (c.contasPrimeiro) {
      const sobra = bruto - taxa - cmv;
      const pro = sobra * c.percentualProLabore / 100;
      const dias = diasDeTrabalho(c.diasTrabalho);
      const cota = dias ? totalContas / dias : totalContas;
      return html`Contas de ${moeda(totalContas)} ÷ ${dias} dias de trabalho neste mês =
        <strong>${moeda(cota)} por dia</strong> para as contas.<br>
        Venda de <strong>${moeda(bruto)}</strong> no crédito com custo de ${moeda(cmv)} →
        taxa ${moeda(taxa)} · reposição ${moeda(cmv)} · sobra ${moeda(sobra)}.<br>
        Enquanto a cota do dia não está paga, a sobra vai para <strong>contas</strong>.
        Depois: pró-labore <strong>${moeda(pro)}</strong> · reserva ${moeda(sobra - pro)} a cada venda como essa.`;
    }
    const contas = bruto * c.percentualContasFixas / 100;
    const lucro = bruto - taxa - cmv - contas;
    const pro = lucro > 0 ? lucro * c.percentualProLabore / 100 : 0;
    return html`Venda de <strong>${moeda(bruto)}</strong> no crédito com custo de ${moeda(cmv)} →
      taxa ${moeda(taxa)} · reposição ${moeda(cmv)} · contas ${moeda(contas)} ·
      pró-labore <strong>${moeda(pro)}</strong> · reserva ${moeda(Math.max(lucro, 0) - pro)}
      ${lucro < 0 ? html` · <span class="bad">prejuízo de ${moeda(-lucro)}</span>` : ''}`;
  }

  const totalDasContas = () => despesas.filter((d) => d.ativa).reduce((s, d) => s + Number(d.valorMensal), 0);

  function mensagemWhatsapp() {
    return `Olá! Bem-vindo ao ${loja.nome} 🍗\nFaça seu pedido por aqui, já com a taxa de entrega do seu bairro:\n`
      + `${location.origin}/pedir\nO pedido vai direto para a cozinha. Qualquer dúvida, é só responder esta mensagem.`;
  }

  async function copiar(texto) {
    try { await navigator.clipboard.writeText(texto); toast('Copiado'); } catch { toast('Não deu para copiar: selecione e copie à mão', 'erro'); }
  }

  function desenhar() {
    const totalContas = despesas.filter((d) => d.ativa).reduce((s, d) => s + Number(d.valorMensal), 0);
    renderizar(container, html`
      <div class="page-head"><div><h1>Configurações</h1><p>Regras do rateio, contas fixas, cupom, impressora e acessos.</p></div></div>
      <div class="grid grid-2">
        <form class="card stack" id="form-loja">
          <h2>Loja e cupom</h2>
          <div class="form-grid">
            <label class="field" style="grid-column:1/-1">Nome da loja<input name="nome" required maxlength="100" value="${loja.nome}"></label>
            <label class="field">CNPJ / CPF<input name="documento" maxlength="30" value="${loja.documento ?? ''}"></label>
            <label class="field">Telefone<input name="telefone" maxlength="30" value="${loja.telefone ?? ''}"></label>
            <label class="field" style="grid-column:1/-1">Endereço<input name="endereco" maxlength="200" value="${loja.endereco ?? ''}"></label>
            <label class="field" style="grid-column:1/-1">Mensagem no fim do cupom<input name="mensagemRodape" maxlength="200" value="${loja.mensagemRodape ?? ''}"></label>
            <label class="field">Taxa de serviço (%)<input name="percentualServico" inputmode="decimal" required value="${numero(loja.percentualServico, 2)}"></label>
            <label class="row" style="gap:8px;align-self:end"><input type="checkbox" name="servicoMarcado" ${loja.servicoMarcado ? 'checked' : ''} style="width:auto"> Já vem marcada ao fechar a conta</label>
          </div>
          <p class="muted" style="margin:0;font-size:.8rem">A taxa de serviço é opcional para o cliente: dá para desmarcar no fechamento.
            Ela não entra nos potes do financeiro (pela Lei 13.419/2017, a gorjeta é da equipe).</p>
          <div><button class="btn btn-primary" type="submit">Salvar dados da loja</button></div>
        </form>

        <div class="card stack">
          <h2>🌐 Cardápio online</h2>
          <p class="text-2" style="margin:0">O cliente escolhe, vê a taxa de entrega do bairro e o pedido vai direto para a cozinha
            (canal "Site" na tela de Pedidos). Só aceita pedidos com o caixa aberto.</p>
          <div class="row" style="flex-wrap:nowrap;gap:6px">
            <input value="${location.origin}/pedir" readonly aria-label="Link do cardápio online" data-link-site>
            <button type="button" class="btn" data-acao="copiar-link">Copiar</button>
            <a class="btn" href="/pedir" target="_blank" rel="noopener">Abrir</a>
          </div>
          <label class="field">Mensagem automática do WhatsApp Business
            <textarea rows="4" readonly data-msg-site>${mensagemWhatsapp()}</textarea></label>
          <p class="muted" style="margin:0;font-size:.8rem">No app WhatsApp Business: Ferramentas comerciais → Mensagem de saudação
            (e Mensagem de ausência) → cole o texto acima.</p>
          <div><button type="button" class="btn btn-sm" data-acao="copiar-msg">Copiar mensagem</button></div>
        </div>

        <div class="card stack">
          <h2>Impressora deste aparelho</h2>
          <p class="text-2" style="margin:0">Cada celular/PC guarda a sua. Use a largura da bobina da impressora térmica.</p>
          <div class="segmented">${['80', '58'].map((mm) => html`
            <button type="button" data-acao="largura" data-v="${mm}" class="${larguraImpressora() === mm ? 'on' : ''}">${mm} mm</button>`)}</div>
          <p class="muted" style="margin:0;font-size:.8rem">No Android, instale o app/serviço de impressão da impressora (Bluetooth ou Wi-Fi).
            Ao imprimir, escolha a impressora térmica no diálogo do sistema.</p>
          <div><button type="button" class="btn" data-acao="teste-impressao">🖨️ Imprimir teste</button></div>
        </div>

        <form class="card stack" id="form-rateio">
          <h2>Rateio de cada venda</h2>
          <label class="row" style="gap:8px;font-weight:600"><input type="checkbox" name="contasPrimeiro" style="width:auto"
            ${cfg.contasPrimeiro ? 'checked' : ''}> Contas por dia trabalhado (recomendado)</label>
          <p class="muted" style="margin:0;font-size:.82rem">As contas fixas do mês (${moeda(totalContas)}) viram uma cota por dia
            de trabalho. A sobra de cada venda (o que fica depois da taxa e do custo dos insumos) paga a cota do dia e o que
            faltou nos dias anteriores; o resto é o seu pró-labore e a reserva daquele dia.
            Desmarcado, cada venda guarda um percentual fixo para as contas.</p>
          <div class="stack" data-dias-trabalho ${cfg.contasPrimeiro ? '' : 'hidden'} style="gap:4px">
            <span style="font-weight:600;font-size:.9rem">Dias em que a loja abre</span>
            <div class="segmented">${DIAS.map((d) => html`
              <label class="seg-opcao"><input type="checkbox" name="diaTrabalho" value="${d}" ${(cfg.diasTrabalho ?? DIAS).includes(d) ? 'checked' : ''}><span>${NOME_DIA[d]}</span></label>`)}</div>
          </div>
          <div class="form-grid">
            <label class="field" data-percentual-contas ${cfg.contasPrimeiro ? 'hidden' : ''}>Contas fixas (% do faturamento)<input name="percentualContasFixas" inputmode="decimal" required value="${numero(cfg.percentualContasFixas, 2)}"></label>
            <label class="field">Pró-labore (% do lucro)<input name="percentualProLabore" inputmode="decimal" required value="${numero(cfg.percentualProLabore, 2)}"></label>
          </div>
          <p class="muted" style="margin:0;font-size:.85rem">O que sobra do lucro (${pct(cfg.percentualReserva)}) vai para a reserva de emergência.</p>
          <h3>Taxas de pagamento (%)</h3>
          <div class="form-grid">
            <label class="field">PIX<input name="taxaPix" inputmode="decimal" required value="${numero(cfg.taxaPix, 2)}"></label>
            <label class="field">Débito<input name="taxaDebito" inputmode="decimal" required value="${numero(cfg.taxaDebito, 2)}"></label>
            <label class="field">Crédito<input name="taxaCredito" inputmode="decimal" required value="${numero(cfg.taxaCredito, 2)}"></label>
          </div>
          <h3>Apps de delivery (% sugerido no lançamento)</h3>
          <div class="form-grid">
            <label class="field">iFood<input name="taxaIfood" inputmode="decimal" required value="${numero(cfg.taxaIfood, 2)}"></label>
            <label class="field">99Food<input name="taxaNoventaNove" inputmode="decimal" required value="${numero(cfg.taxaNoventaNove, 2)}"></label>
          </div>
          <p class="muted" style="margin:0;font-size:.8rem">Comissão + taxa de pagamento online do seu plano (ex.: iFood Plano Básico ≈ 12% + 3,2%).
            Serve só para sugerir o valor; no lançamento vale o que estiver no extrato do app.</p>
          <div class="card" style="background:var(--surface-2);font-size:.9rem" data-exemplo>${exemplo(cfg, totalContas)}</div>
          <p class="muted" style="margin:0;font-size:.8rem">Vale para os próximos pedidos. Os já lançados mantêm os valores da hora da venda.</p>
          <div><button class="btn btn-primary" type="submit">Salvar rateio</button></div>
        </form>

        <div class="card stack">
          <div class="row"><h2>Contas fixas do mês</h2><span class="spacer"></span>
            <button class="btn btn-sm btn-primary" data-acao="nova-despesa">+ Conta</button></div>
          <table>
            <thead><tr><th>Conta</th><th>Vence</th><th class="right">Valor/mês</th><th></th></tr></thead>
            <tbody>${despesas.map((d) => html`
              <tr class="${d.ativa ? '' : 'inativo'}">
                <td>${d.descricao}${d.valorVariavel ? html` <span class="badge" title="O valor acompanha o último pagamento">varia</span>` : ''}</td>
                <td class="num">${d.diaVencimento ? `dia ${d.diaVencimento}` : '—'}</td>
                <td class="num right">${moeda(d.valorMensal)}</td>
                <td class="right"><button class="btn btn-sm" data-acao="editar-despesa" data-id="${d.id}">Editar</button></td>
              </tr>`)}</tbody>
            <tfoot><tr><th colspan="2">Total</th><th class="num right">${moeda(totalContas)}</th><th></th></tr></tfoot>
          </table>
        </div>

        <div class="card stack">
          <div class="row"><h2>Equipe</h2><span class="spacer"></span>
            <button class="btn btn-sm btn-primary" data-acao="novo-colaborador">+ Pessoa</button></div>
          <p class="muted" style="margin:0;font-size:.85rem">Motoboy recebe a diária + a taxa de cada entrega. Funcionário recebe a diária. Os pagamentos saem do Caixa.</p>
          ${equipe.length ? html`<table><tbody>${equipe.map((c) => html`
            <tr class="${c.ativo ? '' : 'inativo'}">
              <td><strong>${c.nome}</strong></td><td>${rotulo(c.funcao)}</td>
              <td class="num right">${moeda(c.valorDiaria)}/dia</td>
              <td class="right"><button class="btn btn-sm" data-acao="editar-colaborador" data-id="${c.id}">Editar</button></td>
            </tr>`)}</tbody></table>` : html`<p class="muted">Ninguém cadastrado.</p>`}
        </div>

        <form class="card stack" id="form-entrega-gratis">
          <h2>Dias de entrega grátis</h2>
          <p class="muted" style="margin:0;font-size:.85rem">Nesses dias o PDV já marca "entrega grátis": o cliente não paga a taxa.
            Se um motoboy entregar, a loja paga a taxa a ele; se você mesmo entregar, não tem custo.</p>
          <div class="segmented">${DIAS.map((d) => html`
            <label class="seg-opcao"><input type="checkbox" name="dia" value="${d}" ${(loja.diasEntregaGratis ?? []).includes(d) ? 'checked' : ''}><span>${NOME_DIA[d]}</span></label>`)}</div>
          <div><button class="btn btn-primary" type="submit">Salvar dias</button></div>
        </form>

        <form class="card stack" id="form-menus">
          <h2>Nomes do menu</h2>
          <p class="muted" style="margin:0;font-size:.85rem">Troque o nome que aparece no menu lateral. Deixe em branco para usar o nome padrão.</p>
          <div class="menus-grid">${MENUS.map(([chave, padrao]) => html`
            <label class="field">${padrao}<input name="${chave}" maxlength="24" placeholder="${padrao}" value="${loja.menus?.[chave] ?? ''}"></label>`)}</div>
          <div><button class="btn btn-primary" type="submit">Salvar nomes</button></div>
        </form>

        <div class="card stack">
          <div class="row"><h2>Usuários</h2><span class="spacer"></span>
            <button class="btn btn-sm btn-primary" data-acao="novo-usuario">+ Usuário</button></div>
          <table><tbody>${usuarios.map((u) => html`
            <tr class="${u.ativo ? '' : 'inativo'}">
              <td><strong>${u.nome}</strong><div class="muted" style="font-size:.8rem">${u.username}</div></td>
              <td>${rotulo(u.perfil)}<div class="muted" style="font-size:.78rem">${u.telas.length === TELAS_DO_PERFIL[u.perfil].length
                ? 'todas as telas do perfil' : u.telas.map((t) => nomeMenu(t.toLowerCase())).join(', ')}</div></td>
              <td class="right">${u.username === sessao.get().username ? html`<span class="muted">você</span>`
                : html`<button class="btn btn-sm" data-acao="acesso-usuario" data-id="${u.id}">🔐 Telas</button>
                  <button class="btn btn-sm" data-acao="ativo-usuario" data-id="${u.id}" data-v="${!u.ativo}">${u.ativo ? 'Desativar' : 'Reativar'}</button>`}</td>
            </tr>`)}</tbody></table>
          <div><button class="btn btn-sm" data-acao="senha">Trocar minha senha</button></div>
        </div>
      </div>`);

    const formLoja = container.querySelector('#form-loja');
    formLoja.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const d = Object.fromEntries(new FormData(formLoja));
      try {
        loja = await api.put('/loja', { ...d, percentualServico: decimal(d.percentualServico), servicoMarcado: !!formLoja.servicoMarcado.checked });
        await dadosLoja(true);
        toast('Dados da loja atualizados');
      } catch (e) { toastErro(e); }
    });

    const formGratis = container.querySelector('#form-entrega-gratis');
    formGratis.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      try {
        loja = await api.put('/loja/entrega-gratis', { dias: [...formGratis.querySelectorAll('[name=dia]:checked')].map((c) => c.value) });
        await dadosLoja(true);
        toast('Dias de entrega grátis atualizados');
      } catch (e) { toastErro(e); }
    });

    const formMenus = container.querySelector('#form-menus');
    formMenus.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      try {
        loja = await api.put('/loja/menus', Object.fromEntries(new FormData(formMenus)));
        await dadosLoja(true);
        window.dispatchEvent(new CustomEvent('menus-alterados', { detail: loja.menus }));
        toast('Nomes do menu atualizados');
      } catch (e) { toastErro(e); }
    });

    const form = container.querySelector('#form-rateio');
    const ler = () => ({
      ...Object.fromEntries([...new FormData(form)].filter(([k]) => !['contasPrimeiro', 'diaTrabalho'].includes(k))
        .map(([k, v]) => [k, decimal(v)])),
      contasPrimeiro: form.contasPrimeiro.checked,
      diasTrabalho: new FormData(form).getAll('diaTrabalho'),
    });
    form.addEventListener('input', () => {
      container.querySelector('[data-percentual-contas]').hidden = form.contasPrimeiro.checked;
      container.querySelector('[data-dias-trabalho]').hidden = !form.contasPrimeiro.checked;
      container.querySelector('[data-exemplo]').innerHTML = String(exemplo(ler(), totalDasContas()));
    });
    form.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      if (form.contasPrimeiro.checked && !ler().diasTrabalho.length) {
        toast('Marque ao menos um dia em que a loja abre');
        return;
      }
      try {
        cfg = await api.put('/financeiro/configuracao', ler());
        toast('Rateio atualizado');
        desenhar();
      } catch (e) { toastErro(e); }
    });
  }

  function formDespesa(d) {
    return html`
      <div class="form-grid">
        <label class="field" style="grid-column:1/-1">Descrição<input name="descricao" required maxlength="100" value="${d?.descricao ?? ''}"></label>
        <label class="field">Valor mensal (R$)<input name="valorMensal" required inputmode="decimal" value="${d ? numero(d.valorMensal, 2) : ''}"></label>
        <label class="field">Dia do vencimento<input name="diaVencimento" type="number" min="1" max="31" value="${d?.diaVencimento ?? ''}"></label>
      </div>
      <label class="row" style="gap:6px"><input type="checkbox" name="valorVariavel" ${d?.valorVariavel ? 'checked' : ''} style="width:auto">
        O valor muda todo mês (água, luz, gás): atualizar sozinho com o valor de cada pagamento</label>
      ${d ? html`<label class="row" style="gap:6px"><input type="checkbox" name="ativa" ${d.ativa ? 'checked' : ''} style="width:auto"> Ativa</label>` : ''}`;
  }

  function formColaborador(c) {
    return html`
      <div class="form-grid">
        <label class="field" style="grid-column:1/-1">Nome<input name="nome" required maxlength="100" value="${c?.nome ?? ''}"></label>
        <label class="field">Função<select name="funcao">${['MOTOBOY', 'FUNCIONARIO'].map((f) => html`
          <option value="${f}" ${c?.funcao === f ? 'selected' : ''}>${rotulo(f)}</option>`)}</select></label>
        <label class="field">Diária (R$)<input name="valorDiaria" required inputmode="decimal" value="${c ? numero(c.valorDiaria, 2) : ''}"></label>
      </div>
      ${c ? html`<label class="row" style="gap:6px"><input type="checkbox" name="ativo" ${c.ativo ? 'checked' : ''} style="width:auto"> Ativo</label>` : ''}`;
  }

  const corpoColaborador = (x) => ({ nome: x.nome, funcao: x.funcao, valorDiaria: decimal(x.valorDiaria) });

  const corpoDespesa = (x) => ({
    descricao: x.descricao, valorMensal: decimal(x.valorMensal),
    diaVencimento: x.diaVencimento ? Number(x.diaVencimento) : null, ativa: x.ativa === undefined ? true : x.ativa === 'on',
    valorVariavel: x.valorVariavel === 'on',
  });

  const soltar = aoClicar(container, {
    largura: (el) => { definirLarguraImpressora(el.dataset.v); desenhar(); toast(`Impressora deste aparelho: ${el.dataset.v} mm`); },
    'teste-impressao': () => imprimirVenda({
      id: 0, canal: 'BALCAO', criadoEm: new Date().toISOString(), formaPagamento: 'PIX', valorTotal: 54,
      itens: [{ quantidade: 1, produto: 'Isca de Frango (6 un)', subtotal: 38 }, { quantidade: 2, produto: 'Refrigerante Lata', subtotal: 12 }],
    }).catch(toastErro),
    'nova-despesa': () => modal({
      titulo: 'Nova conta fixa', corpo: formDespesa(null),
      aoSalvar: async (x) => { await api.post('/financeiro/despesas', corpoDespesa(x)); await carregar(); },
    }),
    'editar-despesa': (el) => {
      const d = despesas.find((y) => y.id === Number(el.dataset.id));
      modal({
        titulo: `Editar ${d.descricao}`,
        corpo: html`${formDespesa(d)}<button type="button" class="btn btn-sm btn-danger" data-excluir>Excluir conta</button>`,
        aoAbrir: (form) => form.querySelector('[data-excluir]').addEventListener('click', async () => {
          try { await api.del(`/financeiro/despesas/${d.id}`); document.getElementById('modal').close(); await carregar(); } catch (e) { toastErro(e); }
        }),
        aoSalvar: async (x) => {
          await api.put(`/financeiro/despesas/${d.id}`, { ...corpoDespesa(x), ativa: x.ativa === 'on' });
          await carregar();
        },
      });
    },
    'novo-colaborador': () => modal({
      titulo: 'Nova pessoa na equipe', corpo: formColaborador(null),
      aoSalvar: async (x) => { await api.post('/colaboradores', corpoColaborador(x)); await carregar(); },
    }),
    'editar-colaborador': (el) => {
      const c = equipe.find((y) => y.id === Number(el.dataset.id));
      modal({
        titulo: `Editar ${c.nome}`, corpo: formColaborador(c),
        aoSalvar: async (x) => { await api.put(`/colaboradores/${c.id}`, { ...corpoColaborador(x), ativo: x.ativo === 'on' }); await carregar(); },
      });
    },
    'novo-usuario': () => modal({
      titulo: 'Novo usuário',
      corpo: html`
        <div class="form-grid">
          <label class="field" style="grid-column:1/-1">Nome<input name="nome" required maxlength="100"></label>
          <label class="field">Login<input name="username" required minlength="3" maxlength="50" pattern="[a-zA-Z0-9._\\-]+" autocomplete="off"></label>
          <label class="field">Senha (mín. 8)<input name="senha" type="password" required minlength="8" autocomplete="new-password"></label>
          <label class="field">Perfil<select name="perfil"><option value="ATENDENTE">Atendente</option><option value="COZINHA">Cozinha</option><option value="ADMIN">Administrador</option></select></label>
        </div>
        <p class="muted" style="margin:0;font-size:.85rem"><strong>Atendente:</strong> lança pedidos, abre e fecha comandas; não vê custos, lucro nem cancela.
          <strong>Cozinha:</strong> só vê a fila da cozinha (para o PC/celular que fica lá).</p>`,
      aoSalvar: async (x) => { await api.post('/usuarios', x); toast('Usuário criado'); await carregar(); },
    }),
    'copiar-link': () => copiar(`${location.origin}/pedir`),
    'copiar-msg': () => copiar(mensagemWhatsapp()),
    'acesso-usuario': (el) => {
      const u = usuarios.find((x) => x.id === Number(el.dataset.id));
      const marcadas = new Set(u.telas.map((t) => t.toLowerCase()));
      modal({
        titulo: `Acesso de ${u.nome}`,
        corpo: html`
          <label class="field">Perfil<select name="perfil">${['ATENDENTE', 'COZINHA', 'ADMIN'].map((p) => html`
            <option value="${p}" ${p === u.perfil ? 'selected' : ''}>${rotulo(p)}</option>`)}</select></label>
          <div>
            <div class="muted" style="font-size:.8rem;margin-bottom:6px">Telas que aparecem no menu deste login</div>
            <div class="menus-grid" data-telas>${MENUS.map(([t]) => html`
              <label class="row" style="gap:8px"><input type="checkbox" name="tela-${t}" data-tela="${t}" style="width:auto"
                ${marcadas.has(t) ? 'checked' : ''}> ${nomeMenu(t)}</label>`)}</div>
          </div>
          <p class="muted" style="margin:0;font-size:.82rem" data-dica></p>`,
        aoAbrir: (form) => {
          const ajustar = () => {
            const pode = TELAS_DO_PERFIL[form.perfil.value];
            form.querySelectorAll('[data-tela]').forEach((c) => {
              c.disabled = !pode.includes(c.dataset.tela);
              if (c.disabled) c.checked = false;
            });
            form.querySelector('[data-dica]').textContent = form.perfil.value === 'ADMIN'
              ? 'Administrador vê custos, lucro e o financeiro e pode cancelar pedidos.'
              : 'Clientes, Cardápio, Financeiro e Config. são só do administrador, porque mostram custos e dados dos clientes.';
          };
          form.perfil.addEventListener('change', () => {
            ajustar();
            // ao trocar de perfil, já vem com tudo que o novo perfil pode
            form.querySelectorAll('[data-tela]:not(:disabled)').forEach((c) => { c.checked = true; });
          });
          ajustar();
        },
        aoSalvar: async (x, form) => {
          const telas = [...form.querySelectorAll('[data-tela]:checked')].map((c) => c.dataset.tela.toUpperCase());
          if (!telas.length) throw new Error('Marque pelo menos uma tela.');
          await api.put(`/usuarios/${u.id}/acesso`, { perfil: x.perfil, telas });
          toast(`Acesso de ${u.nome} salvo. Vale na próxima vez que a tela dele carregar.`);
          await carregar();
        },
      });
    },
    'ativo-usuario': async (el) => {
      try { await api.patch(`/usuarios/${el.dataset.id}/ativo?valor=${el.dataset.v}`); await carregar(); } catch (e) { toastErro(e); }
    },
    senha: () => modal({
      titulo: 'Trocar minha senha',
      corpo: html`
        <label class="field">Senha atual<input name="senhaAtual" type="password" required autocomplete="current-password"></label>
        <label class="field">Nova senha (mín. 8)<input name="novaSenha" type="password" required minlength="8" autocomplete="new-password"></label>`,
      aoSalvar: async (x) => {
        await api.put('/usuarios/me/senha', x);
        toast('Senha alterada. Por segurança, os outros aparelhos saem e você entra de novo com a senha nova.');
        setTimeout(() => location.reload(), 2500);
      },
    }),
  });

  await carregar();
  return soltar;
}
