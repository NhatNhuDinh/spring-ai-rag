package com.example.essentialrag.service.guardrail;

import org.springframework.stereotype.Component;

@Component
public class AnswerGuardrailService {

  public String refusalMessage(ContextQualityResult quality) {
    return """
        Ngữ cảnh giáo trình hiện có chưa đủ để trả lời câu hỏi này một cách có căn cứ.
        Lý do kiểm tra ngữ cảnh: %s. Bạn có thể hỏi cụ thể hơn theo tên giáo trình, chương hoặc khái niệm cần tra cứu.
        """.formatted(quality.reason()).trim();
  }

  public String citationFailureMessage() {
    return "Không thể xác thực trích dẫn của câu trả lời theo ngữ cảnh giáo trình hiện có. "
        + "Vui lòng hỏi cụ thể hơn hoặc thử lại với phạm vi chương/mục rõ hơn.";
  }

  public String groundedSystemPrompt(String ragContext) {
    return """
        Bạn là trợ lý học thuật về Triết học Mác - Lênin và Kinh tế chính trị Mác - Lênin.
        Trả lời câu hỏi chỉ dựa trên RAG_CONTEXT được cung cấp trong system message này.

        Yêu cầu bắt buộc:
        - Không gọi tool vì RAG_CONTEXT đã được backend chuẩn bị sẵn.
        - Mọi khẳng định học thuật phải có citation dạng [S1], [S2] tương ứng.
        - Không dùng citation không xuất hiện trong RAG_CONTEXT.
        - Nếu RAG_CONTEXT không đủ, nói rõ ngữ cảnh giáo trình hiện có chưa đủ để kết luận.
        - Trả lời bằng tiếng Việt, rõ ràng và ngắn gọn vừa đủ.

        %s
        """.formatted(ragContext).trim();
  }

  public String groundedAnswerPrompt(String question, String ragContext) {
    return """
        %s

        Câu hỏi cần trả lời:
        %s
        """.formatted(groundedSystemPrompt(ragContext), question).trim();
  }

  public String citationCorrectionPrompt(
      String question,
      String ragContext,
      String draftAnswer,
      CitationValidationResult validation) {

    return """
        Câu trả lời nháp dưới đây vi phạm guardrail citation.

        Lỗi:
        - Thiếu citation bắt buộc: %s
        - Citation không tồn tại trong RAG_CONTEXT: %s

        Hãy viết lại câu trả lời cuối cùng.
        Yêu cầu:
        - Chỉ dùng citation có trong RAG_CONTEXT: %s
        - Không dùng citation ngoài danh sách trên.
        - Nếu không thể trả lời có căn cứ, hãy nói ngữ cảnh giáo trình hiện có chưa đủ.
        - Không gọi thêm tool.

        %s

        Câu hỏi:
        %s

        Câu trả lời nháp:
        %s
        """.formatted(
        validation.missingRequiredCitation(),
        validation.invalidCitations(),
        validation.contextCitations(),
        ragContext,
        question,
        draftAnswer).trim();
  }
}
