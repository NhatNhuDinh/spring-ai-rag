package com.example.essentialrag.ingestion.profile;

import com.example.essentialrag.ingestion.model.TextbookProfile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class TextbookProfileResolver {

  public TextbookProfile resolve(String location, Resource resource) {
    String filename = resource.getFilename() == null ? location : resource.getFilename();
    String lower = filename.toLowerCase(Locale.ROOT);

    if (lower.contains("triet_2") || lower.contains("kinh")) {
      return new TextbookProfile(
          location,
          filename,
          "Giáo trình Kinh tế chính trị Mác - Lênin",
          "Kinh tế chính trị Mác - Lênin");
    }

    return new TextbookProfile(
        location,
        filename,
        "Giáo trình Triết học Mác - Lênin",
        "Triết học Mác - Lênin");
  }
}
