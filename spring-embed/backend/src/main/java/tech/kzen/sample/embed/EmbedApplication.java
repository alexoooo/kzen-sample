package tech.kzen.sample.embed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


/**
 * A plain Spring Boot host for kzen-auto: the sample plugin sits on the application class path (plugin zero),
 * the configured workspaces are one context and one loopback server each under one process-global runtime,
 * and the host's own MVC proxies each workspace's UI under {@code /kzen/{workspace}/}. Nothing here edits
 * kzen; nothing here installs kzen's process-exit or headless behaviour.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EmbedApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmbedApplication.class, args);
    }
}
