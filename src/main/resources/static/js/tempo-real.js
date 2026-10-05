// Eventos do servidor via SSE.
// Usa fetch em vez de EventSource porque o EventSource não envia header Authorization, e
// token na query string acaba em log de acesso. O parse do formato SSE é feito à mão.
import { sessao } from './api.js';

const ouvintes = new Set();
let controle = null;
let tentativas = 0;
let ligado = false;

/** fn({tipo, id}) é chamada a cada mudança. Devolve a função para parar de ouvir. */
export function ouvir(fn) {
  ouvintes.add(fn);
  return () => ouvintes.delete(fn);
}

export function conectar() {
  if (ligado) return;
  ligado = true;
  abrir();
}

export function desconectar() {
  ligado = false;
  controle?.abort();
  controle = null;
}

async function abrir() {
  const token = sessao.get()?.token;
  if (!ligado || !token) return;
  controle = new AbortController();
  try {
    const resp = await fetch('/api/v1/eventos', {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
      signal: controle.signal,
      cache: 'no-store',
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    tentativas = 0;
    const leitor = resp.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    for (;;) {
      const { value, done } = await leitor.read();
      if (done) break;
      buffer += value;
      let fim;
      while ((fim = buffer.indexOf('\n\n')) >= 0) {
        processar(buffer.slice(0, fim));
        buffer = buffer.slice(fim + 2);
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') return;
  }
  // queda de rede, deploy ou timeout de 30 min: reconecta com backoff de até 30 s
  if (ligado) {
    tentativas += 1;
    setTimeout(abrir, Math.min(30000, 1000 * 2 ** Math.min(tentativas, 5)));
  }
}

function processar(bloco) {
  let evento = 'message';
  const dados = [];
  for (const linha of bloco.split('\n')) {
    if (linha.startsWith('event:')) evento = linha.slice(6).trim();
    else if (linha.startsWith('data:')) dados.push(linha.slice(5).trim());
  }
  if (evento !== 'atualizacao' || !dados.length) return;
  try {
    const msg = JSON.parse(dados.join('\n'));
    ouvintes.forEach((fn) => fn(msg));
  } catch { /* ignora mensagem malformada */ }
}

// ---------- Alertas de pedido novo (cozinha) ----------

let audio = null;
/** Ding-dong sintetizado com Web Audio, sem arquivo de som. */
export function tocarAlerta() {
  try {
    audio ??= new (window.AudioContext || window.webkitAudioContext)();
    [[880, 0], [660, 0.18]].forEach(([freq, atraso]) => {
      const osc = audio.createOscillator();
      const vol = audio.createGain();
      osc.frequency.value = freq;
      osc.connect(vol).connect(audio.destination);
      const t = audio.currentTime + atraso;
      vol.gain.setValueAtTime(0.0001, t);
      vol.gain.exponentialRampToValueAtTime(0.4, t + 0.02);
      vol.gain.exponentialRampToValueAtTime(0.0001, t + 0.35);
      osc.start(t);
      osc.stop(t + 0.4);
    });
  } catch { /* navegador sem áudio */ }
  navigator.vibrate?.([200, 100, 200]);
}

/** Política de autoplay: o áudio só destrava depois de uma interação do usuário. */
export function liberarAudio() {
  audio ??= new (window.AudioContext || window.webkitAudioContext)();
  return audio.resume();
}
