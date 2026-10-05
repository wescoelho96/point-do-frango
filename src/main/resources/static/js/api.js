// Cliente da API: injeta o token, trata JSON e normaliza os erros.
const CHAVE_SESSAO = 'pdf.sessao';

// sessionStorage por padrão, porque o PC do caixa é compartilhado. localStorage só com
// "manter conectado", pensado para o tablet fixo da loja. O token expira em 12 h de qualquer forma.
function ler(storage) {
  try { return JSON.parse(storage.getItem(CHAVE_SESSAO)); } catch { return null; }
}

export const sessao = {
  get() {
    return ler(sessionStorage) || ler(localStorage);
  },
  set(dados, lembrar = false) {
    this.limpar();
    try { (lembrar ? localStorage : sessionStorage).setItem(CHAVE_SESSAO, JSON.stringify(dados)); } catch { /* modo privado */ }
  },
  limpar() {
    try { sessionStorage.removeItem(CHAVE_SESSAO); localStorage.removeItem(CHAVE_SESSAO); } catch { /* ignore */ }
  },
  admin() {
    return this.get()?.perfil === 'ADMIN';
  },
  perfil() {
    return this.get()?.perfil;
  },
};

export class ErroApi extends Error {
  constructor(status, corpo) {
    super(corpo?.detail || corpo?.title || `Erro ${status}`);
    this.status = status;
    this.corpo = corpo;
  }
}

let aoExpirar = () => {};
export function quandoSessaoExpirar(fn) { aoExpirar = fn; }

async function requisitar(metodo, caminho, corpo) {
  const headers = { Accept: 'application/json' };
  const token = sessao.get()?.token;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (corpo !== undefined) headers['Content-Type'] = 'application/json';

  const resp = await fetch(`/api/v1${caminho}`, {
    method: metodo,
    headers,
    body: corpo !== undefined ? JSON.stringify(corpo) : undefined,
  });

  if (resp.status === 401 && caminho !== '/auth/login') {
    aoExpirar();
    throw new ErroApi(401, { detail: 'Sessão expirada. Entre novamente.' });
  }
  const texto = await resp.text();
  const dados = texto ? JSON.parse(texto) : null;
  if (!resp.ok) {
    if (resp.status === 403) throw new ErroApi(403, { detail: 'Seu perfil não tem acesso a esta função.' });
    throw new ErroApi(resp.status, dados);
  }
  return dados;
}

export const api = {
  get: (c) => requisitar('GET', c),
  post: (c, b = {}) => requisitar('POST', c, b),
  put: (c, b) => requisitar('PUT', c, b),
  patch: (c, b) => requisitar('PATCH', c, b),
  del: (c) => requisitar('DELETE', c),
};
