package dev.jaimerey.mvcapp.controllers;

import java.util.Map;

import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
class DbController {

    private final JdbcTemplate jdbc;

    @GetMapping("/db")
    @Transactional
    Map<String, String> db(@RequestParam(defaultValue = "20") long ms)
            throws InterruptedException {
        jdbc.queryForObject("SELECT 1", Integer.class);  // reserva una conexión del pool
        Thread.sleep(ms);                                 // la conexión queda ocupada 20 ms
        return Map.of("thread", Thread.currentThread().toString());
    }
}