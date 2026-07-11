package com.example.essentialrag.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/health")
public class HealthController {

  @GetMapping
  public HealthResponse health() {
    return new HealthResponse("UP", "tool-rag", Instant.now().toString());
  }

  public record HealthResponse(String status, String application, String timestamp) {
  }
}
