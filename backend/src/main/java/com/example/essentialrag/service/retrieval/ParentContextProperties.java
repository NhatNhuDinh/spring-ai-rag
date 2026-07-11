package com.example.essentialrag.service.retrieval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ParentContextProperties {

  private final boolean enabled;
  private final int windowBefore;
  private final int windowAfter;
  private final int maxResults;

  public ParentContextProperties(
      @Value("${rag.retrieval.parent-context.enabled:true}") boolean enabled,
      @Value("${rag.retrieval.parent-context.window-before:1}") int windowBefore,
      @Value("${rag.retrieval.parent-context.window-after:1}") int windowAfter,
      @Value("${rag.retrieval.parent-context.max-results:12}") int maxResults) {

    this.enabled = enabled;
    this.windowBefore = Math.max(0, windowBefore);
    this.windowAfter = Math.max(0, windowAfter);
    this.maxResults = Math.max(1, maxResults);
  }

  public boolean enabled() {
    return enabled;
  }

  public int windowBefore() {
    return windowBefore;
  }

  public int windowAfter() {
    return windowAfter;
  }

  public int maxResults() {
    return maxResults;
  }
}
