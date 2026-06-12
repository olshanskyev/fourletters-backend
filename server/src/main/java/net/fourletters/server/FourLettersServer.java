package net.fourletters.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(scanBasePackages = {"net.fourletters"}, exclude = {UserDetailsServiceAutoConfiguration.class})
@EntityScan(basePackages = {"net.fourletters.server.model"})
public class FourLettersServer {

    public static void main(String[] args) {
        SpringApplication.run(FourLettersServer.class, args);
    }

}
