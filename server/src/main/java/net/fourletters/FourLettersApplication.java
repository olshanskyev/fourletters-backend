package net.fourletters;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan(basePackages = {"net.fourletters.model"})
public class FourLettersApplication {

    public static void main(String[] args) {
        SpringApplication.run(FourLettersApplication.class, args);
    }

}
