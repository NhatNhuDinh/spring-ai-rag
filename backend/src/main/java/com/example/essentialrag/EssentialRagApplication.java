package com.example.essentialrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class EssentialRagApplication {

  public static void main(String[] args) {
    // In tiếng Việt có dấu ra console mà không bị vỡ font.
    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
    SpringApplication.run(EssentialRagApplication.class, args);
  }

}
