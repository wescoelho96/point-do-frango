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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;

/** Busca a página pública de consulta da NFC-e indicada no QR Code do cupom. */
@Component
public class ConsultaNfce {

    private static final Logger log = LoggerFactory.getLogger(ConsultaNfce.class);
    private static final int MAX_BYTES = 2_000_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ZoneId zona;

    public ConsultaNfce(ZoneId zona) {
        this.zona = zona;
    }

    public Nota ler(String endereco) {
        URI uri = LeitorCupom.validarEndereco(endereco);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Mozilla/5.0 (compatible; PointDoFrango/2.0)")
                .header("Accept", "text/html")
                .GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            String host = resp.uri().getHost() == null ? "" : resp.uri().getHost().toLowerCase();
            if (resp.statusCode() != 200 || !host.endsWith(".gov.br")) {
                throw new RegraDeNegocioException("A SEFAZ não respondeu a consulta do cupom (código " + resp.statusCode()
                        + "). Tente de novo em alguns minutos ou lance pela Compra sem nota.");
            }
            try (InputStream corpo = resp.body()) {
                byte[] bytes = corpo.readNBytes(MAX_BYTES);
                return LeitorCupom.ler(uri, new String(bytes, StandardCharsets.UTF_8), zona);
            }
        } catch (IOException e) {
            log.warn("Consulta de NFC-e falhou em {}: {}", uri.getHost(), e.toString());
            throw new RegraDeNegocioException("Não foi possível falar com o site da SEFAZ agora. "
                    + "Tente de novo em alguns minutos ou lance pela Compra sem nota.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegraDeNegocioException("A consulta do cupom foi interrompida.");
        }
    }
}
