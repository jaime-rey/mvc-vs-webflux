package dev.jaimerey.mvcapp.controllers;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SlowController {

    @GetMapping("/slow")
    Map<String, String> slow(@RequestParam(defaultValue = "200") long ms)
            throws InterruptedException {
        Thread.sleep(ms);
        return Map.of("thread", Thread.currentThread().toString());
    }
}
