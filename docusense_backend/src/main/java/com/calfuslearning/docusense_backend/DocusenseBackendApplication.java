package com.calfuslearning.docusense_backend;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DocusenseBackendApplication {

    public static void main(String[] args) {
        loadDotenvIntoSystemProperties();
        SpringApplication.run(DocusenseBackendApplication.class, args);
    }

    /**
     * Loads .env into system properties before the Spring context starts, so
     * ${VAR} placeholders in application.properties resolve normally. Real
     * environment variables (e.g. from CI or a container) always take priority.
     */
    private static void loadDotenvIntoSystemProperties() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> {
            if (System.getenv(entry.getKey()) == null && System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }

}
