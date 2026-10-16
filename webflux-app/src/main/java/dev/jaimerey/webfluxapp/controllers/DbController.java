package dev.jaimerey.webfluxapp.controllers;

import java.time.Duration;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;

@RestController
class DbController {

    private final ConnectionFactory factory;

    DbController(ConnectionFactory factory) {
        this.factory = factory;
    }

    @GetMapping("/db")
    Mono<Map<String, String>> db(@RequestParam(defaultValue = "20") long ms) {
        return Mono.usingWhen(
                factory.create(),                                          // reserva conexión del pool
                conn -> Mono.from(conn.createStatement("SELECT 1").execute())
                        .then(Mono.delay(Duration.ofMillis(ms)))           // mantiene reservada 20 ms
                        .thenReturn(Map.of("thread", Thread.currentThread().toString())),
                conn -> Mono.from(conn.close())                            // devuelve al pool
        );
    }
}
