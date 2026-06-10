package net.fourletters.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = {"net.fourletters"})
@EntityScan(basePackages = {"net.fourletters.server.model"})
public class FourLettersServer {

    public static void main(String[] args) {
        SpringApplication.run(FourLettersServer.class, args);
    }

}
