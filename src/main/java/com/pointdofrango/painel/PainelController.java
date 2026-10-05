package com.pointdofrango.painel;

import com.pointdofrango.painel.PainelDtos.Resumo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/v1/painel")
@Tag(name = "Painel financeiro", description = "Para onde foi cada real vendido (somente ADMIN)")
public class PainelController {

    private final PainelService service;
    private final Clock clock;
    private final ZoneId zona;

    public PainelController(PainelService service, Clock clock, ZoneId zona) {
        this.service = service;
        this.clock = clock;
        this.zona = zona;
    }

    @GetMapping("/resumo")
    @Operation(summary = "Potes do período (padrão: hoje): reposição, contas, taxas, pró-labore e reserva")
    public Resumo resumo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        LocalDate hoje = LocalDate.now(clock.withZone(zona));
        LocalDate ate = fim != null ? fim : hoje;
        LocalDate de = inicio != null ? inicio : ate;
        return service.resumo(de, ate);
    }
}
