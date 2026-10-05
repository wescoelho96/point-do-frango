package com.pointdofrango;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PointDoFrangoApplication {

    public static void main(String[] args) {
        SpringApplication.run(PointDoFrangoApplication.class, args);
    }
}
