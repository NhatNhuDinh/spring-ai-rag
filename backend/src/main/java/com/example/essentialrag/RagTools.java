package com.example.essentialrag;

import com.example.essentialrag.service.TextbookRetrievalService;
import com.example.essentialrag.service.retrieval.RetrievalContextPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagTools {

  private static final Logger logger = LoggerFactory.getLogger(RagTools.class);

  private final TextbookRetrievalService retrievalService;
  private final RetrievalContextPackager contextPackager;

  public RagTools(TextbookRetrievalService retrievalService, RetrievalContextPackager contextPackager) {
    this.retrievalService = retrievalService;
    this.contextPackager = contextPackager;
  }

  @Tool(
      name = "triet-hoc-helper",
      description = "Tra cứu giáo trình và trả về RAG_CONTEXT đã đóng gói kèm nhãn trích dẫn [S1], [S2]. "
          + "Dùng khi cần trả lời câu hỏi về Triết học Mác - Lênin hoặc Kinh tế chính trị Mác - Lênin.")
  public String findPhilosophyContent(
      @ToolParam(description = "Câu hỏi hoặc từ khóa cần tra trong giáo trình") String query) {

    logger.info("LLM called textbook retrieval tool with query: {}", query);
    List<Document> documents = retrievalService.retrieveWithParentContext(query, null, null);
    return contextPackager.packageContext(query, documents);
  }
}
