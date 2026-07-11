package com.example.essentialrag.ingestion.reader;

import com.example.essentialrag.ingestion.model.PageText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfPageReader {

  public List<PageText> read(Resource resource) throws IOException {
    byte[] pdfBytes = StreamUtils.copyToByteArray(resource.getInputStream());
    try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);

      List<PageText> pages = new ArrayList<>();
      for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        pages.add(new PageText(pageNumber, stripper.getText(pdf)));
      }
      return pages;
    }
  }
}
