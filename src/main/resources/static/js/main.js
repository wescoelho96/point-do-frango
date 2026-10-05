import { api, sessao, quandoSessaoExpirar } from './api.js';
import { rotulo, toast, toastErro } from './ui.js';
import { conectar, desconectar, liberarAudio } from './tempo-real.js';
import { dadosLoja } from './imprimir.js';
import * as pdv from './views/pdv.js';
import * as comandas from './views/comandas.js';
import * as cozinha from './views/cozinha.js';
import * as pedidos from './views/pedidos.js';
import * as entregas from './views/entregas.js';
import * as caixa from './views/caixa.js';
import * as clientes from './views/clientes.js';
import * as estoque from './views/estoque.js';
import * as cardapio from './views/cardapio.js';
import * as financeiro from './views/financeiro.js';
import * as config from './views/config.js';

// Cada tela exporta montar(container, params) e pode devolver uma função de limpeza.
const TELAS = { pdv, comandas, cozinha, pedidos, entregas, caixa, clientes, estoque, cardapio, financeiro, config };

// Só controla o menu; quem bloqueia o acesso de fato é o backend.
const PERMITIDAS = {
  ADMIN: Object.keys(TELAS),
  ATENDENTE: ['pdv', 'comandas', 'cozinha', 'pedidos', 'entregas', 'caixa', 'estoque'],
  COZINHA: ['cozinha'],
};
const INICIAL = { ADMIN: 'pdv', ATENDENTE: 'pdv', COZINHA: 'cozinha' };

const $ = (id) => document.getElementById(id);
let limparTelaAtual = null;
// Contador de navegação: se a pessoa troca de tela antes da anterior carregar, a tela atrasada
// é desmontada ao terminar. Sem isso os handlers dela continuariam ativos.
let navegacao = 0;

function mostrarLogin() {
  $('app').classList.add('hidden');
  $('tela-login').classList.remove('hidden');
  $('form-login').username.focus();
}

// Nome original de cada menu, para voltar ao padrão quando o dono apagar o nome personalizado
const NOMES_PADRAO = Object.fromEntries([...document.querySelectorAll('.nav-btn')]
  .map((b) => [b.dataset.view, b.querySelector('span').textContent]));

function aplicarNomesMenu(menus = {}) {
  document.querySelectorAll('.nav-btn').forEach((b) => {
    b.querySelector('span').textContent = menus[b.dataset.view] || NOMES_PADRAO[b.dataset.view];
  });
}

function mostrarApp() {
  const s = sessao.get();
  $('tela-login').classList.add('hidden');
  $('app').classList.remove('hidden');
  $('usuario-nome').textContent = s.nome;
  $('usuario-perfil').textContent = rotulo(s.perfil);
  const permitidas = PERMITIDAS[s.perfil] ?? [];
  document.querySelectorAll('.nav-btn').forEach((b) => b.classList.toggle('hidden', !permitidas.includes(b.dataset.view)));
  document.body.dataset.perfil = s.perfil;
  if (s.perfil !== 'COZINHA') dadosLoja(true).then((l) => aplicarNomesMenu(l.menus)).catch(() => {});
  conectar();
  abrirTela(location.hash.slice(1));
}

// "#pdv?comanda=3" vira { nome: 'pdv', params: { comanda: '3' } }
function lerRota(hash) {
  const [nome, query = ''] = hash.split('?');
  return { nome, params: Object.fromEntries(new URLSearchParams(query)) };
}

async function abrirTela(hash) {
  const minha = ++navegacao;
  const perfil = sessao.perfil();
  let { nome, params } = lerRota(hash || '');
  if (!TELAS[nome] || !(PERMITIDAS[perfil] ?? []).includes(nome)) {
    nome = INICIAL[perfil] ?? 'pdv';
    params = {};
  }
  const destino = `#${nome}${Object.keys(params).length ? `?${new URLSearchParams(params)}` : ''}`;
  if (location.hash !== destino) history.replaceState(null, '', destino);
  document.querySelectorAll('.nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.view === nome));
  limparTelaAtual?.();
  limparTelaAtual = null;
  // Cada tela ganha o próprio elemento: se uma tela atrasada terminar de carregar depois,
  // ela desenha num elemento que já saiu da página.
  const container = document.createElement('div');
  container.innerHTML = '<p class="muted">Carregando…</p>';
  $('conteudo').replaceChildren(container);
  try {
    const limpar = (await TELAS[nome].montar(container, params)) || null;
    if (minha !== navegacao) {
      limpar?.();
      return;
    }
    limparTelaAtual = limpar;
  } catch (e) {
    toastErro(e);
  }
}

function sair() {
  desconectar();
  sessao.limpar();
  limparTelaAtual?.();
  limparTelaAtual = null;
  mostrarLogin();
}

$('form-login').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const form = ev.target;
  const erro = $('login-erro');
  erro.classList.add('hidden');
  form.querySelector('button').disabled = true;
  try {
    const r = await api.post('/auth/login', { username: form.username.value, senha: form.senha.value });
    sessao.set({ token: r.token, nome: r.nome, perfil: r.perfil, username: r.username, expiraEm: r.expiraEm },
      form.lembrar.checked);
    form.reset();
    mostrarSenha(false);
    avisoCaps.classList.add('hidden');
    mostrarApp();
  } catch (e) {
    erro.textContent = e.message;
    erro.classList.remove('hidden');
  } finally {
    form.querySelector('button').disabled = false;
  }
});

// ---------- Mostrar senha só enquanto o botão estiver pressionado ----------
const campoSenha = $('campo-senha');
const btnOlho = $('btn-ver-senha');
const mostrarSenha = (ver) => {
  campoSenha.type = ver ? 'text' : 'password';
  btnOlho.classList.toggle('vendo', ver);
};
btnOlho.addEventListener('pointerdown', (ev) => { ev.preventDefault(); mostrarSenha(true); }); // preventDefault: o cursor fica no campo
['pointerup', 'pointerleave', 'pointercancel', 'blur'].forEach((ev) => btnOlho.addEventListener(ev, () => mostrarSenha(false)));
// Mesmo comportamento pelo teclado
btnOlho.addEventListener('keydown', (ev) => { if (ev.key === ' ' || ev.key === 'Enter') { ev.preventDefault(); mostrarSenha(true); } });
btnOlho.addEventListener('keyup', () => mostrarSenha(false));
btnOlho.addEventListener('contextmenu', (ev) => ev.preventDefault()); // toque longo no celular não abre menu

// ---------- Caps Lock ----------
const avisoCaps = $('aviso-caps');
const conferirCaps = (ev) => {
  if (typeof ev.getModifierState === 'function') avisoCaps.classList.toggle('hidden', !ev.getModifierState('CapsLock'));
};
['keydown', 'keyup'].forEach((tipo) => {
  campoSenha.addEventListener(tipo, conferirCaps);
  $('form-login').username.addEventListener(tipo, conferirCaps);
});
campoSenha.addEventListener('blur', () => avisoCaps.classList.add('hidden'));

document.querySelectorAll('.nav-btn').forEach((b) => b.addEventListener('click', () => {
  location.hash = b.dataset.view;
}));
$('btn-sair').addEventListener('click', sair);
window.addEventListener('hashchange', () => {
  const modal = $('modal');
  if (modal.open) modal.close(); // ex.: "+ Pedido" dentro da conta da comanda
  if (sessao.get()) abrirTela(location.hash.slice(1));
});
quandoSessaoExpirar(sair);
// Config. avisa quando o dono renomeia os menus
window.addEventListener('menus-alterados', (ev) => aplicarNomesMenu(ev.detail));
// Navegadores só liberam som depois do primeiro toque na tela
document.addEventListener('pointerdown', () => liberarAudio().catch(() => {}), { once: true });

// ---------- PWA ----------
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
}
let pedidoDeInstalacao = null;
window.addEventListener('beforeinstallprompt', (ev) => {
  ev.preventDefault(); // adia o prompt para o botão próprio de instalar
  pedidoDeInstalacao = ev;
  $('btn-instalar').classList.remove('hidden');
});
$('btn-instalar').addEventListener('click', async () => {
  if (!pedidoDeInstalacao) return;
  pedidoDeInstalacao.prompt();
  const { outcome } = await pedidoDeInstalacao.userChoice;
  if (outcome === 'accepted') toast('App instalado! Procure o ícone do Point do Frango na tela inicial.');
  pedidoDeInstalacao = null;
  $('btn-instalar').classList.add('hidden');
});
window.addEventListener('appinstalled', () => $('btn-instalar').classList.add('hidden'));

const s = sessao.get();
if (s && new Date(s.expiraEm) > new Date()) mostrarApp(); else { sessao.limpar(); mostrarLogin(); }
