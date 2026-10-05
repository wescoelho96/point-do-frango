import { api } from '../api.js';
import { aoClicar, decimal, hojeISO, html, modal, moeda, numero, pct, renderizar, rotulo, telefone, toast, toastErro } from '../ui.js';

const CATEGORIAS = ['INSUMOS', 'FUNCIONARIO', 'SALARIO', 'MOTOBOY', 'PRO_LABORE', 'IMPOSTO', 'CONTA_FIXA', 'EQUIPAMENTO', 'OUTRO'];
const COM_PESSOA = ['FUNCIONARIO', 'SALARIO', 'MOTOBOY'];
const COM_FORNECEDOR = ['INSUMOS', 'EQUIPAMENTO', 'OUTRO'];
const FORMAS = ['PIX', 'DEBITO', 'CREDITO', 'DINHEIRO'];
const SUGESTAO = { PRO_LABORE: 'Meu salário', IMPOSTO: 'DAS MEI', SALARIO: 'Salário', INSUMOS: 'Compra no mercado', FUNCIONARIO: 'Diária' };
const CLASSE_SITUACAO = { PAGA: 'badge-good', PARCIAL: 'badge-warn', PENDENTE: '', ATRASADA: 'badge-bad' };

/** Abas no topo do Financeiro. */
export const abas = (atual) => html`
  <div class="segmented" style="margin-bottom:16px">
    <a class="${atual === 'vendas' ? 'on' : ''}" href="#financeiro">💰 Vendas e potes</a>
    <a class="${atual === 'saidas' ? 'on' : ''}" href="#financeiro?aba=saidas">📤 Saídas do mês</a>
    <a class="${atual === 'fornecedores' ? 'on' : ''}" href="#financeiro?aba=fornecedores">🏪 Fornecedores</a>
    <a class="${atual === 'contador' ? 'on' : ''}" href="#financeiro?aba=contador">📑 Contador</a>
  </div>`;

/**
 * Lê o código de barras pela câmera (Chrome/Android têm o BarcodeDetector; boleto usa o formato ITF).
 * Devolve uma função que desliga a câmera.
 */
async function lerPelaCamera(video, aoLer) {
  const detector = new window.BarcodeDetector({ formats: ['itf'] });
  const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
  video.srcObject = stream;
  await video.play();
  let ativo = true;
  const parar = () => { ativo = false; stream.getTracks().forEach((t) => t.stop()); video.srcObject = null; };
  const procurar = async () => {
    if (!ativo) return;
    try {
      const achados = await detector.detect(video);
      const codigo = achados.map((a) => a.rawValue).find((v) => /^\d{44}$/.test(v));
      if (codigo) { parar(); aoLer(codigo); return; }
    } catch { /* quadro sem código */ }
    setTimeout(procurar, 300);
  };
  procurar();
  return parar;
}

const variacao = (agora, antes) => {
  if (antes == null || !Number(antes)) return '';
  const dif = Number(agora) - Number(antes);
  const p = (dif / Number(antes)) * 100;
  return `${dif >= 0 ? '+' : '−'}${moeda(Math.abs(dif))} (${dif >= 0 ? '+' : '−'}${Math.abs(p).toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%)`;
};

const nomeMes = (ym) => new Date(`${ym}-15T12:00:00`).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
const mesCurto = (ym) => new Date(`${ym}-15T12:00:00`).toLocaleDateString('pt-BR', { month: 'short' }).replace('.', '');
const dataBr = (iso) => iso.split('-').reverse().slice(0, 2).join('/');

export async function montar(container) {
  let mes = hojeISO().slice(0, 7);
  let filtro = '';
  let r; let fornecedores = []; let equipe = []; let cfg;

  async function carregar() {
    [r, fornecedores, equipe, cfg] = await Promise.all([
      api.get(`/saidas/mes?mes=${mes}`), api.get('/fornecedores'), api.get('/colaboradores'), api.get('/financeiro/configuracao'),
    ]);
    desenhar();
  }

  function cartaoRegime() {
    const g = r.regime;
    const usado = Math.min(Number(g.percentualUsado), 100);
    return html`
      <div class="card ${g.alerta ? 'alerta' : ''}">
        <div class="row"><h2>${rotulo(g.regime)}: faturamento de ${r.mes.slice(0, 4)}</h2><span class="spacer"></span>
          <button class="btn btn-sm" data-acao="regime">Alterar</button></div>
        <p class="text-2" style="margin:6px 0 0"><strong>${moeda(g.faturamentoAno)}</strong> de ${moeda(g.limiteAnual)} (limite do ano)</p>
        <div class="progress" role="progressbar" aria-valuenow="${usado}" aria-valuemin="0" aria-valuemax="100"><div style="width:${usado}%"></div></div>
        <p class="muted" style="margin:0;font-size:.85rem">${pct(g.percentualUsado)} usado · média de ${moeda(g.mediaMensal)}/mês ·
          no ritmo atual o ano fecha em <strong>${moeda(g.projecaoAno)}</strong></p>
        ${g.alerta ? html`<p class="bad" style="margin:8px 0 0">⚠️ ${g.alerta}</p>` : ''}
        <p class="muted" style="margin:8px 0 0;font-size:.78rem">Conta vendas + taxas de entrega + serviço. Confira o limite atual com o seu contador.</p>
      </div>`;
  }

  function historico() {
    const max = Math.max(...r.historico.flatMap((m) => [Number(m.entradas), Number(m.saidas)]), 1);
    return html`
      <div class="card">
        <div class="row"><h2>Últimos 6 meses</h2><span class="spacer"></span>
          <div class="legenda"><span><span class="chip" style="--c:var(--pote-prolabore)"></span>Entrou</span>
            <span><span class="chip" style="--c:var(--pote-contas)"></span>Saiu</span></div></div>
        <div class="hist" role="img" aria-label="Entradas e saídas dos últimos 6 meses">${r.historico.map((m) => html`
          <div class="par" title="${nomeMes(m.mes)}: entrou ${moeda(m.entradas)}, saiu ${moeda(m.saidas)}, sobrou ${moeda(m.resultado)}">
            <div class="ent" style="height:${(Number(m.entradas) / max) * 100}%"></div>
            <div class="sai" style="height:${(Number(m.saidas) / max) * 100}%"></div>
          </div>`)}</div>
        <div class="hist-eixo">${r.historico.map((m) => html`<span>${mesCurto(m.mes)}<br><strong class="${Number(m.resultado) < 0 ? 'bad' : ''}">${moeda(m.resultado)}</strong></span>`)}</div>
      </div>`;
  }

  function cartaoEntregas() {
    const e = r.entregas;
    if (!e.total) return '';
    return html`
      <div class="card">
        <h2>Entregas do mês: quem ficou com a taxa</h2>
        <div class="stat-row" style="margin-top:8px">
          <div><div class="muted" style="font-size:.8rem">🙋 Você entregou</div><div class="num" style="font-size:1.2rem"><strong>${e.peloDono}</strong> entrega(s)</div>
            <div class="good" style="font-size:.85rem">${moeda(e.taxasFicaramComALoja)} de taxa ficaram com a loja</div></div>
          <div><div class="muted" style="font-size:.8rem">🛵 Motoboy entregou</div><div class="num" style="font-size:1.2rem"><strong>${e.porMotoboy}</strong> entrega(s)</div>
            <div class="muted" style="font-size:.85rem">${moeda(e.taxasDosMotoboys)} de taxas são do motoboy</div></div>
          <div><div class="muted" style="font-size:.8rem">🎁 Entrega grátis com motoboy</div><div class="num" style="font-size:1.2rem"><strong>${e.gratis}</strong> entrega(s)</div>
            <div class="${Number(e.custoEntregaGratis) ? 'bad' : 'muted'}" style="font-size:.85rem">${moeda(e.custoEntregaGratis)} pagos pela loja</div></div>
        </div>
      </div>`;
  }

  function tabela(titulo, linhas, vazio) {
    return html`<div class="card"><h2>${titulo}</h2>${linhas.length ? html`<div class="table-wrap"><table><tbody>${linhas}</tbody></table></div>` : html`<p class="muted">${vazio}</p>`}</div>`;
  }

  function lancamentos() {
    const lista = r.lancamentos.filter((l) => !filtro || l.categoria === filtro);
    return html`
      <div class="card">
        <div class="row"><h2>Lançamentos</h2><span class="spacer"></span>
          <select data-campo="filtro" style="width:auto" aria-label="Filtrar categoria">
            <option value="">Todas as categorias</option>
            ${CATEGORIAS.map((c) => html`<option value="${c}" ${filtro === c ? 'selected' : ''}>${rotulo(c)}</option>`)}
          </select></div>
        ${lista.length ? html`<div class="table-wrap"><table>
          <thead><tr><th>Dia</th><th>Categoria</th><th>Descrição</th><th>Pago como</th><th class="right">Valor</th><th></th></tr></thead>
          <tbody>${lista.map((l) => html`
            <tr>
              <td class="num">${dataBr(l.data)}</td>
              <td><span class="badge">${rotulo(l.categoria)}</span></td>
              <td>${l.descricao}${l.pessoa || l.fornecedor ? html`<div class="muted" style="font-size:.78rem">${[l.pessoa, l.fornecedor].filter(Boolean).join(' · ')}</div>` : ''}</td>
              <td>${rotulo(l.forma)} <span class="muted" style="font-size:.78rem">${l.origem === 'CAIXA' ? '· gaveta' : '· por fora'}</span></td>
              <td class="num right bad">${moeda(l.valor)}</td>
              <td class="right">${l.origem === 'CONTA'
                ? html`<button class="btn btn-sm" data-acao="remover" data-id="${l.id}" aria-label="Remover lançamento">✕</button>`
                : html`<span class="muted" title="Lançado no caixa: remova pela tela Caixa" style="font-size:.78rem">caixa</span>`}</td>
            </tr>`)}</tbody></table></div>` : html`<p class="muted">Nada lançado${filtro ? ' nesta categoria' : ''} em ${nomeMes(r.mes)}.</p>`}
      </div>`;
  }

  function desenhar() {
    const total = Number(r.totalSaidas) || 1;
    renderizar(container, html`
      ${abas('saidas')}
      <div class="page-head">
        <div><h1>Saídas de ${nomeMes(r.mes)}</h1><p>Tudo o que saiu: da gaveta (caixa) e por fora (PIX, boleto, cartão).</p></div>
        <div class="row">
          <input type="month" data-campo="mes" value="${mes}" max="${hojeISO().slice(0, 7)}" style="width:auto" aria-label="Mês">
          <button class="btn btn-primary" data-acao="lancar">+ Lançar pagamento</button>
        </div>
      </div>
      <div class="stack">
        <div class="stat-row">
          <div class="card stat"><div class="rotulo">Entrou</div><div class="valor num">${moeda(r.totalEntradas)}</div>
            <div class="sub">vendas ${moeda(r.vendas)} · entregas ${moeda(r.taxasEntrega)}${Number(r.taxaServico) ? ` · serviço ${moeda(r.taxaServico)}` : ''}</div></div>
          <div class="card stat"><div class="rotulo">Saiu</div><div class="valor num bad">${moeda(r.totalSaidas)}</div>
            <div class="sub">${moeda(r.saidasDoCaixa)} da gaveta · ${moeda(r.saidasPorFora)} por fora</div></div>
          <div class="card stat"><div class="rotulo">Sobrou no mês</div>
            <div class="valor num ${Number(r.resultado) < 0 ? 'bad' : 'good'}">${moeda(r.resultado)}</div>
            <div class="sub">entrou − saiu (regime de caixa)</div></div>
          <div class="card stat"><div class="rotulo">Consumo da equipe</div><div class="valor num">${moeda(r.consumoInterno)}</div>
            <div class="sub">custo dos insumos da janta/bebidas · não é venda</div></div>
        </div>

        <div class="grid grid-2">
          ${tabela('Por categoria', r.porCategoria.map((c) => html`
            <tr><td>${c.rotulo}<div class="barra-pct"><div style="width:${(Number(c.valor) / total) * 100}%"></div></div></td>
              <td class="num right muted">${c.quantidade}×</td><td class="num right">${moeda(c.valor)}</td>
              <td class="num right muted">${pct(c.percentual)}</td></tr>`), 'Nenhuma saída no mês.')}
          <div class="card">
            <div class="row"><h2>Equipe: quanto cada um recebeu</h2><span class="spacer"></span>
              <button class="btn btn-sm" data-acao="diaria" title="Lançar a diária de um dia (também de dias anteriores)">+ Diária</button></div>
            ${r.porPessoa.length ? html`<div class="table-wrap"><table><tbody>${r.porPessoa.map((p) => html`
              <tr><td>${p.nome}<div class="muted" style="font-size:.78rem">${rotulo(p.funcao)} · dias: ${p.dias.map(dataBr).join(', ')}</div></td>
                <td class="num right muted">${p.pagamentos} pagamento(s)</td><td class="num right"><strong>${moeda(p.valor)}</strong></td></tr>`)}
              </tbody></table></div>` : html`<p class="muted">Nenhum pagamento à equipe no mês.</p>`}
          </div>
        </div>

        <div class="grid grid-2">
          ${tabela('Fornecedores e mercado', r.porFornecedor.map((f) => html`
            <tr><td>${f.nome}</td><td class="num right muted">${f.compras} compra(s)</td><td class="num right">${moeda(f.valor)}</td></tr>`),
            'Nenhuma compra com fornecedor informado.')}
          <div class="card">
            <h2>Contas fixas</h2>
            ${r.contasFixas.length ? html`<div class="table-wrap"><table><tbody>${r.contasFixas.map((c) => html`
              <tr><td>${c.descricao}${c.valorVariavel ? html` <span class="badge">varia</span>` : ''}
                <div class="muted" style="font-size:.78rem">${c.diaVencimento ? `vence dia ${c.diaVencimento}` : 'sem vencimento'}${c.pagoMesAnterior != null
                  ? ` · mês passado ${moeda(c.pagoMesAnterior)}` : ''}${c.situacao === 'PAGA' && c.pagoMesAnterior != null
                  ? ` · ${variacao(c.pago, c.pagoMesAnterior)}` : ''}</div></td>
                <td class="num right">${moeda(c.valorMensal)}${Number(c.pago) && c.situacao !== 'PAGA' ? html`<div class="muted" style="font-size:.78rem">pago ${moeda(c.pago)}</div>` : ''}</td>
                <td><span class="badge ${CLASSE_SITUACAO[c.situacao]}">${rotulo(c.situacao)}</span></td>
                <td class="right">${c.situacao !== 'PAGA' ? html`<button class="btn btn-sm" data-acao="pagar-conta" data-id="${c.despesaFixaId}">Pagar</button>` : ''}</td></tr>`)}
              </tbody></table></div>` : html`<p class="muted">Cadastre aluguel, luz, DAS... em Config. → Contas fixas.</p>`}
          </div>
        </div>

        <div class="grid grid-2">${cartaoRegime()}${historico()}</div>
        ${cartaoEntregas()}
        ${lancamentos()}
      </div>`);
  }

  function formSaida(preset = {}) {
    return html`
      <div class="form-grid">
        <label class="field">Categoria<select name="categoria">${CATEGORIAS.filter((c) => c !== 'CONTA_FIXA').map((c) => html`
          <option value="${c}" ${preset.categoria === c ? 'selected' : ''}>${rotulo(c)}</option>`)}</select></label>
        <label class="field">Dia<input type="date" name="data" value="${mes === hojeISO().slice(0, 7) ? hojeISO() : `${mes}-01`}"></label>
        <label class="field" style="grid-column:1/-1">Descrição<input name="descricao" required maxlength="150" value="${SUGESTAO.INSUMOS}"></label>
        <label class="field">Valor (R$)<input name="valor" required inputmode="decimal" autocomplete="off"></label>
        <label class="field">Pago com<select name="forma">${FORMAS.map((f) => html`<option value="${f}">${rotulo(f)}</option>`)}</select></label>
        <label class="field" data-so="pessoa" style="grid-column:1/-1">Pessoa<select name="colaboradorId">
          <option value="">—</option>${equipe.filter((c) => c.ativo).map((c) => html`<option value="${c.id}">${c.nome} (${rotulo(c.funcao)})</option>`)}</select></label>
        <label class="field" data-so="fornecedor" style="grid-column:1/-1">Fornecedor / mercado<select name="fornecedorId">
          <option value="">—</option>${fornecedores.filter((f) => f.ativo).map((f) => html`<option value="${f.id}">${f.nome}</option>`)}</select></label>
      </div>
      <p class="muted" style="margin:0;font-size:.8rem">Use para o que foi pago <strong>por fora da gaveta de hoje</strong>: PIX, cartão,
        ou dinheiro de um dia que já passou (ex.: a diária de ontem). O que sai hoje da gaveta se lança na tela Caixa.</p>`;
  }

  /** preset.categoria = 'FUNCIONARIO' abre já como diária (de hoje ou de um dia anterior). */
  function abrirLancamento(preset = {}) {
    modal({
      titulo: preset.categoria === 'FUNCIONARIO' ? 'Lançar diária' : 'Lançar pagamento',
      corpo: formSaida(preset),
      aoAbrir: (form) => {
        let sugerida = SUGESTAO.INSUMOS;
        // escolheu a pessoa: valor = diária do cadastro e descrição com o dia trabalhado
        const aoEscolherPessoa = () => {
          const c = equipe.find((x) => x.id === Number(form.colaboradorId.value));
          if (!c || !['FUNCIONARIO', 'MOTOBOY'].includes(form.categoria.value)) return;
          form.valor.value = String(Number(c.valorDiaria).toFixed(2)).replace('.', ',');
          form.descricao.value = `Diária de ${c.nome} (${dataBr(form.data.value || hojeISO())})`;
          sugerida = form.descricao.value;
        };
        form.colaboradorId.addEventListener('change', aoEscolherPessoa);
        form.data.addEventListener('change', aoEscolherPessoa);
        const ajustar = () => {
          const cat = form.categoria.value;
          form.querySelector('[data-so=pessoa]').classList.toggle('hidden', !COM_PESSOA.includes(cat));
          form.querySelector('[data-so=fornecedor]').classList.toggle('hidden', !COM_FORNECEDOR.includes(cat));
          if (!form.descricao.value || form.descricao.value === sugerida) form.descricao.value = SUGESTAO[cat] ?? '';
          sugerida = SUGESTAO[cat] ?? '';
        };
        form.categoria.addEventListener('change', ajustar);
        ajustar();
      },
      aoSalvar: async (d) => {
        if (COM_PESSOA.includes(d.categoria) && !d.colaboradorId) throw new Error('Escolha a pessoa que recebeu.');
        await api.post('/saidas', {
          data: d.data || null, categoria: d.categoria, descricao: d.descricao, valor: decimal(d.valor), forma: d.forma,
          colaboradorId: COM_PESSOA.includes(d.categoria) && d.colaboradorId ? Number(d.colaboradorId) : null,
          fornecedorId: COM_FORNECEDOR.includes(d.categoria) && d.fornecedorId ? Number(d.fornecedorId) : null,
        });
        toast('Pagamento lançado');
        await carregar();
      },
    });
  }

  const soltar = aoClicar(container, {
    lancar: () => abrirLancamento(),
    diaria: () => abrirLancamento({ categoria: 'FUNCIONARIO' }),
    'pagar-conta': (el) => {
      const c = r.contasFixas.find((x) => x.despesaFixaId === Number(el.dataset.id));
      modal({
        titulo: `Pagar ${c.descricao}`,
        corpo: html`
          <label class="field">Código de barras ou linha digitável (opcional)
            <div class="row" style="flex-wrap:nowrap">
              <input name="codigoBarras" inputmode="numeric" autocomplete="off" placeholder="Cole os números da conta ou do app do banco">
              ${'BarcodeDetector' in window ? html`<button type="button" class="btn" data-camera title="Ler com a câmera">📷</button>` : ''}
            </div></label>
          <video data-video playsinline muted class="hidden" style="width:100%;border-radius:8px;background:#000"></video>
          <p class="muted" style="margin:0;font-size:.85rem" data-leitura>${c.pagoMesAnterior != null ? `Mês passado: ${moeda(c.pagoMesAnterior)}.` : ''}
            O valor é lido do código; não precisa digitar.</p>
          <div class="form-grid">
            <label class="field">Valor (R$)<input name="valor" inputmode="decimal" value="${String((Number(c.valorMensal) - Number(c.pago)).toFixed(2)).replace('.', ',')}"></label>
            <label class="field">Pago com<select name="forma">${FORMAS.map((f) => html`<option value="${f}">${rotulo(f)}</option>`)}</select></label>
            <label class="field">Dia<input type="date" name="data" value="${mes === hojeISO().slice(0, 7) ? hojeISO() : `${mes}-${String(c.diaVencimento ?? 1).padStart(2, '0')}`}"></label>
          </div>`,
        textoSalvar: 'Marcar como paga',
        aoAbrir: (form) => {
          const info = form.querySelector('[data-leitura]');
          let pararCamera = null;
          const ler = async () => {
            const codigo = form.codigoBarras.value.replace(/\D/g, '');
            if (![44, 47, 48].includes(codigo.length)) return;
            try {
              const l = await api.get(`/saidas/boleto?codigo=${codigo}`);
              if (l.valor != null) form.valor.value = String(Number(l.valor).toFixed(2)).replace('.', ',');
              if (l.vencimento) form.data.value = l.vencimento;
              info.innerHTML = String(html`✅ Lido: <strong>${l.valor != null ? moeda(l.valor) : 'sem valor no código (digite)'}</strong>${l.vencimento
                ? ` · vence ${l.vencimento.split('-').reverse().join('/')}` : ''}${c.pagoMesAnterior != null && l.valor != null
                ? ` · mês passado ${moeda(c.pagoMesAnterior)} (${variacao(l.valor, c.pagoMesAnterior)})` : ''}`);
            } catch (e) {
              info.innerHTML = String(html`<span class="bad">${e.message}</span>`);
            }
          };
          form.codigoBarras.addEventListener('input', ler);
          form.querySelector('[data-camera]')?.addEventListener('click', async () => {
            const video = form.querySelector('[data-video]');
            video.classList.remove('hidden');
            try {
              pararCamera = await lerPelaCamera(video, (codigo) => { video.classList.add('hidden'); form.codigoBarras.value = codigo; ler(); });
            } catch { video.classList.add('hidden'); info.textContent = 'Não foi possível abrir a câmera. Cole os números.'; }
          });
          document.getElementById('modal').addEventListener('close', () => pararCamera?.(), { once: true });
        },
        aoSalvar: async (d) => {
          await api.post(`/saidas/contas-fixas/${c.despesaFixaId}/pagar`, {
            valor: decimal(d.valor), forma: d.forma, data: d.data || null, codigoBarras: d.codigoBarras || null,
          });
          toast(`${c.descricao} paga`);
          await carregar();
        },
      });
    },
    remover: (el) => modal({
      titulo: 'Remover lançamento',
      corpo: html`<p class="text-2">O pagamento sai das saídas do mês.</p>`,
      textoSalvar: 'Remover',
      perigo: true,
      aoSalvar: async () => { await api.del(`/saidas/${el.dataset.id}`); await carregar(); },
    }),
    regime: () => modal({
      titulo: 'Regime tributário',
      corpo: html`
        <div class="form-grid">
          <label class="field">Regime<select name="regime">${['MEI', 'ME'].map((x) => html`<option value="${x}" ${cfg.regimeTributario === x ? 'selected' : ''}>${rotulo(x)}</option>`)}</select></label>
          <label class="field">Limite de faturamento no ano (R$)<input name="limite" required inputmode="decimal" value="${numero(cfg.limiteFaturamentoAnual, 2)}"></label>
        </div>
        <p class="muted" style="margin:0;font-size:.8rem">MEI hoje: R$ 81 mil por ano. Ao virar ME pelo Simples Nacional, o teto é outro
          (ex.: R$ 360 mil). O DAS de cada mês você lança em "Lançar pagamento" → Imposto, ou como conta fixa.</p>`,
      aoSalvar: async (d) => {
        await api.put('/financeiro/regime', { regime: d.regime, limiteFaturamentoAnual: decimal(d.limite) });
        await carregar();
      },
    }),
  });

  const aoMudar = (ev) => {
    if (ev.target.dataset.campo === 'mes' && ev.target.value) { mes = ev.target.value; carregar().catch(toastErro); }
    if (ev.target.dataset.campo === 'filtro') { filtro = ev.target.value; desenhar(); }
  };
  container.addEventListener('change', aoMudar);

  await carregar();
  return () => { soltar(); container.removeEventListener('change', aoMudar); };
}

/** Aba Fornecedores: cadastro de mercados, distribuidoras e prestadores. */
export async function montarFornecedores(container) {
  let lista = [];

  async function carregar() {
    lista = await api.get('/fornecedores');
    renderizar(container, html`
      ${abas('fornecedores')}
      <div class="page-head">
        <div><h1>Fornecedores</h1><p>Mercados, atacados, distribuidoras e prestadores. Aparecem na entrada de estoque e nos pagamentos.</p></div>
        <button class="btn btn-primary" data-acao="novo">+ Fornecedor</button>
      </div>
      <div class="card table-wrap">${lista.length ? html`<table>
        <thead><tr><th>Fornecedor</th><th>O que fornece</th><th>Contato</th><th></th></tr></thead>
        <tbody>${lista.map((f) => html`
          <tr class="${f.ativo ? '' : 'inativo'}">
            <td><strong>${f.nome}</strong>${f.documento ? html`<div class="muted" style="font-size:.78rem">${f.documento}</div>` : ''}</td>
            <td>${f.fornece || html`<span class="muted">—</span>`}${f.observacao ? html`<div class="muted" style="font-size:.78rem">${f.observacao}</div>` : ''}</td>
            <td>${f.telefone ? telefone(f.telefone) : html`<span class="muted">—</span>`}</td>
            <td class="right"><button class="btn btn-sm" data-acao="editar" data-id="${f.id}">Editar</button></td>
          </tr>`)}</tbody></table>` : html`<div class="empty">Nenhum fornecedor cadastrado.</div>`}
      </div>`);
  }

  const form = (f) => html`
    <div class="form-grid">
      <label class="field" style="grid-column:1/-1">Nome<input name="nome" required maxlength="100" value="${f?.nome ?? ''}" placeholder="Ex.: Atacadão"></label>
      <label class="field" style="grid-column:1/-1">O que fornece<input name="fornece" maxlength="100" value="${f?.fornece ?? ''}" placeholder="Ex.: bebidas, frango, embalagens"></label>
      <label class="field">Telefone<input name="telefone" maxlength="20" inputmode="tel" value="${f?.telefone ?? ''}"></label>
      <label class="field">CNPJ<input name="documento" maxlength="20" value="${f?.documento ?? ''}"></label>
      <label class="field" style="grid-column:1/-1">Observação<input name="observacao" maxlength="255" value="${f?.observacao ?? ''}" placeholder="Ex.: entrega às terças, pedido mínimo"></label>
    </div>
    ${f ? html`<label class="row" style="gap:6px"><input type="checkbox" name="ativo" ${f.ativo ? 'checked' : ''} style="width:auto"> Ativo</label>` : ''}`;
  const corpo = (d) => ({ nome: d.nome, fornece: d.fornece || null, telefone: d.telefone || null, documento: d.documento || null, observacao: d.observacao || null });

  const soltar = aoClicar(container, {
    novo: () => modal({
      titulo: 'Novo fornecedor', corpo: form(null),
      aoSalvar: async (d) => { await api.post('/fornecedores', corpo(d)); toast('Fornecedor cadastrado'); await carregar(); },
    }),
    editar: (el) => {
      const f = lista.find((x) => x.id === Number(el.dataset.id));
      modal({
        titulo: `Editar ${f.nome}`, corpo: form(f),
        aoSalvar: async (d) => { await api.put(`/fornecedores/${f.id}`, { ...corpo(d), ativo: d.ativo === 'on' }); await carregar(); },
      });
    },
  });

  await carregar();
  return soltar;
}
