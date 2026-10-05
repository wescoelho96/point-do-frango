package com.pointdofrango.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Clock e fuso como beans para os testes poderem fixar a data (ex.: fechamento de ontem).
 */
@Configuration
@EnableScheduling // heartbeat das conexões em tempo real (TransmissorEventos)
public class TempoConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZoneId zonaDaLoja(AppProperties props) {
        return ZoneId.of(props.zona());
    }
}
