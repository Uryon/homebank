package com.homebank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // Les deux origines sont necessaires : "localhost" pour un acces
                // manuel classique au serveur de dev Vite, et "127.0.0.1" pour
                // l'appli lancee via HomeBank.vbs, qui ouvre volontairement
                // Edge sur cette adresse (vite.config.js n'ecoute d'ailleurs
                // que sur 127.0.0.1, voir son commentaire) -- sans cette
                // deuxieme origine, Spring rejette tous les appels /api/**
                // en 403 ("Invalid CORS request") pour l'appli lancee par le
                // raccourci.
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
