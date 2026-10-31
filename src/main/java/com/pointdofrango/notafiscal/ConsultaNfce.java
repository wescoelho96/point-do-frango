package com.pointdofrango.notafiscal;

import com.pointdofrango.notafiscal.LeitorNfe.Nota;
import com.pointdofrango.shared.RegraDeNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Busca a página pública de consulta da NFC-e indicada no QR Code do cupom. */
@Component
public class ConsultaNfce {

    private static final Logger log = LoggerFactory.getLogger(ConsultaNfce.class);
    private static final int MAX_BYTES = 2_000_000;
    private static final int TENTATIVAS = 2;
    private static final Duration VALIDADE = Duration.ofHours(6);

    private final Map<String, Lida> lidas = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ZoneId zona;

    public ConsultaNfce(ZoneId zona) {
        this.zona = zona;
    }

    /**
     * O que a SEFAZ devolveu na prévia é reaproveitado ao dar entrada: a segunda consulta logo em seguida
     * costuma ser recusada pelo portal. O conteúdo continua vindo da SEFAZ, nunca do navegador.
     */
    public Nota ler(String endereco) {
        URI uri = LeitorCupom.validarEndereco(endereco);
        String chave = LeitorCupom.chaveDaUrl(uri);
        Instant agora = Instant.now();
        lidas.values().removeIf(l -> l.em().plus(VALIDADE).isBefore(agora));
        Lida guardada = lidas.get(chave);
        if (guardada != null) {
            return guardada.nota();
        }
        Nota nota = consultar(uri);
        lidas.put(chave, new Lida(nota, agora));
        return nota;
    }

    /** Ao importar, esquece a leitura: uma importação desfeita volta a consultar a SEFAZ. */
    public void esquecer(String chave) {
        lidas.remove(chave);
    }

    private Nota consultar(URI uri) {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Mozilla/5.0 (compatible; PointDoFrango/2.0)")
                .header("Accept", "text/html")
                .GET().build();
        for (int tentativa = 1; ; tentativa++) {
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                String host = resp.uri().getHost() == null ? "" : resp.uri().getHost().toLowerCase();
                if (resp.statusCode() != 200 || !host.endsWith(".gov.br")) {
                    throw new RegraDeNegocioException("A SEFAZ não respondeu a consulta do cupom (código " + resp.statusCode()
                            + "). Tente de novo em alguns minutos ou lance pela Compra sem nota.");
                }
                try (InputStream corpo = resp.body()) {
                    byte[] bytes = corpo.readNBytes(MAX_BYTES);
                    return LeitorCupom.ler(uri, LeitorCupom.decodificar(bytes), zona);
                }
            } catch (IOException e) {
                log.warn("Consulta de NFC-e falhou em {} (tentativa {}): {}", uri.getHost(), tentativa, e.toString());
                if (tentativa >= TENTATIVAS) {
                    throw new RegraDeNegocioException("Não foi possível falar com o site da SEFAZ agora. "
                            + "Tente de novo em alguns minutos ou lance pela Compra sem nota.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RegraDeNegocioException("A consulta do cupom foi interrompida.");
            }
        }
    }

    private record Lida(Nota nota, Instant em) {
    }
}
