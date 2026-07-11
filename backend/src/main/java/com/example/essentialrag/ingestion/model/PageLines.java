package com.example.essentialrag.ingestion.model;

import java.util.List;

public record PageLines(int pageNumber, List<String> lines) {
}
