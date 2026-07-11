package com.example.essentialrag.api;

import com.example.essentialrag.service.evaluation.AnswerEvaluationService;
import com.example.essentialrag.service.evaluation.AnswerEvaluationSummary;
import com.example.essentialrag.service.evaluation.AnswerTestCase;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/evaluation")
@CrossOrigin
public class AnswerEvaluationController {

  private final AnswerEvaluationService evaluationService;

  public AnswerEvaluationController(AnswerEvaluationService evaluationService) {
    this.evaluationService = evaluationService;
  }

  @GetMapping("/answer-tests")
  public List<AnswerTestCase> answerTestCases() {
    return evaluationService.loadDefaultTestCases();
  }

  @PostMapping("/answer-tests/run")
  public AnswerEvaluationSummary runDefaultAnswerTests() {
    return evaluationService.runDefaultTestCases();
  }

  @PostMapping("/answer-tests/run-custom")
  public AnswerEvaluationSummary runCustomAnswerTests(@RequestBody List<AnswerTestCase> testCases) {
    return evaluationService.run(testCases);
  }
}
