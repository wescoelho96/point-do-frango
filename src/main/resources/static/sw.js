// Network first para o app shell, com cache só como fallback offline: depois de um deploy
// ninguém fica preso numa versão antiga.
// /api/* nunca passa pelo cache: pedido e financeiro têm que vir sempre atualizados e não
// devem ficar salvos no aparelho.
const VERSAO = 'pdf-v16';
const ESSENCIAIS = [
  '/', '/index.html', '/offline.html', '/manifest.webmanifest', '/icons/favicon-64.png', '/img/logo.jpg',
  '/css/app.css', '/icons/icon-192.png', '/icons/icon-512.png',
  '/js/main.js', '/js/api.js', '/js/ui.js', '/js/tempo-real.js', '/js/imprimir.js',
  '/js/views/pdv.js', '/js/views/comandas.js', '/js/views/cozinha.js', '/js/views/pedidos.js',
  '/js/views/estoque.js', '/js/views/cardapio.js', '/js/views/financeiro.js', '/js/views/config.js',
  '/js/views/potes.js', '/js/views/entregas.js', '/js/views/caixa.js', '/js/views/clientes.js', '/js/views/saidas.js', '/js/views/contador.js', '/js/views/nota-fiscal.js',
];

self.addEventListener('install', (ev) => {
  ev.waitUntil(caches.open(VERSAO).then((c) => c.addAll(ESSENCIAIS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (ev) => {
  ev.waitUntil(
    caches.keys()
      .then((chaves) => Promise.all(chaves.filter((k) => k !== VERSAO).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (ev) => {
  const req = ev.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/')) {
    return; // sem respondWith, o navegador trata a requisição
  }
  ev.respondWith(
    fetch(req)
      .then((resp) => {
        if (resp.ok && resp.type === 'basic') {
          const copia = resp.clone();
          caches.open(VERSAO).then((c) => c.put(req, copia));
        }
        return resp;
      })
      .catch(async () => (await caches.match(req))
        || (req.mode === 'navigate' ? caches.match('/offline.html') : Response.error())),
  );
});
