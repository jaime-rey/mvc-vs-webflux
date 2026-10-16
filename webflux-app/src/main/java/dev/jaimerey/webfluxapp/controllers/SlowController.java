package dev.jaimerey.webfluxapp.controllers;

import java.time.Duration;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
class SlowController {

    @GetMapping("/slow")
    Mono<Map<String, String>> slow(@RequestParam(defaultValue = "200") long ms) {
        return Mono.delay(Duration.ofMillis(ms))
                .map(t -> Map.of("thread", Thread.currentThread().toString()));
    }

    // MAL: bloquea el hilo, que en WebFlux hay muy pocos
    @GetMapping("/slow-blocking")
    Mono<Map<String, String>> slowBlocking(@RequestParam(defaultValue = "200") long ms)
            throws InterruptedException {
        Thread.sleep(ms);
        return Mono.just(Map.of("thread", Thread.currentThread().toString()));
    }

    // ARREGLADO: el trabajo que bloquea se manda a otro grupo de hilos pensado para eso
    @GetMapping("/slow-fixed")
    Mono<Map<String, String>> slowFixed(@RequestParam(defaultValue = "200") long ms) {
        return Mono.fromCallable(() -> {
                    Thread.sleep(ms);
                    return Map.of("thread", Thread.currentThread().toString());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}