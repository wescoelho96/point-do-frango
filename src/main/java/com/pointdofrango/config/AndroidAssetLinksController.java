package com.pointdofrango.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Digital Asset Links do app Android (TWA): sem isso o app abre com a barra de endereço.
 * Usa APP_ANDROID_PACOTE e APP_ANDROID_SHA256; se faltar algum, devolve lista vazia.
 */
@RestController
public class AndroidAssetLinksController {

    private final AppProperties.Android android;

    public AndroidAssetLinksController(AppProperties props) {
        this.android = props.android();
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = "application/json")
    public List<Map<String, Object>> assetLinks() {
        if (android == null || vazio(android.pacote()) || vazio(android.sha256())) {
            return List.of();
        }
        List<String> digitais = Arrays.stream(android.sha256().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return List.of(Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", Map.of(
                        "namespace", "android_app",
                        "package_name", android.pacote(),
                        "sha256_cert_fingerprints", digitais)));
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }
}
