package com.safecode.controller;

import com.safecode.entities.Review;
import com.safecode.enums.Provider;
import com.safecode.mocks.MockResponseGpt;
import com.safecode.services.CommentService;
import com.safecode.services.UtilsService;
import com.safecode.services.gitlab.CommentGitlabService;
import com.safecode.services.gitlab.GitLabLanguageDetectorService;
import com.safecode.services.llm.OpenAIService;
import com.safecode.services.llm.RaidenAIService;
import com.safecode.services.review.ReviewDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@Slf4j
@RequiredArgsConstructor
public class InspectorController {

    @Value("${llm.raiden-gpt.enable}")
    private boolean raidenGptEnabled;

    @Value("${raiden-ai.review.language}")
    private String raidenDefaultReviewLAnguage;


    @Value("${raiden-ai.debug}")
    private boolean isRaidenGptEnabled;


    private final ReviewDetailService reviewDetailService;
    private final UtilsService utilsService;

    private final RaidenAIService raidenAIService;

    private final OpenAIService openAIService;

    private final GitLabLanguageDetectorService gitLabLanguageDetectorService;

    private final CommentService commentService;

    private final CommentGitlabService commentGitlabService;

    @PostMapping("/strategy/1/review-pr-diff")
    public Map<String, Object> reviewPullRequestStrategie1(@RequestBody Map<String, Object> requestBody, boolean enableReview, String accesToken, Provider provider, Review review) {

        log.debug("Request body received: {}", requestBody);

        // Extract metadata
        String diff = (String) requestBody.get("diff");
        String filePath = (String) requestBody.get("file");
        log.info("Processing file: {}", filePath);

        String owner = (String) requestBody.get("owner");
        String repo = (String) requestBody.get("repo");
        int prNumber;
        try {
            prNumber = Integer.parseInt(requestBody.get("prNumber").toString());
        } catch (NumberFormatException e) {
            log.error("Invalid PR number in request: {}", requestBody.get("prNumber"));
            return Map.of("status", "error", "message", "Invalid PR number format.");
        }
        String commitId = (String) requestBody.get("commitId");
        String base_sha = (String) requestBody.get("base_sha");
        String start_sha = (String) requestBody.get("start_sha");
        String head_sha = (String) requestBody.get("head_sha");



        log.debug("Processing file '{}' for review.", filePath);
        int totalComments = 0;
        // Process only the edited lines in the file's diff
        if (diff != null && filePath != null) {
            try {
                String editedLinesString = utilsService.extractEditedLinesString(diff);
                if (editedLinesString != null && !editedLinesString.isEmpty()) {
                    log.debug("Sending file '{}' for review with edited lines: {}", filePath, editedLinesString);

                    Map<String, Object> gptResponse;
                    if (isRaidenGptEnabled) {
                        gptResponse = MockResponseGpt.createMockGptResponse();
                    } else {
                        gptResponse = reviewCodeV2(requestBody, filePath, editedLinesString);

                    }
                    log.debug("GPT Response for file '{}': {}", filePath, gptResponse);

                    if (review != null) {
                        reviewDetailService.addReviewDetailsFromGptResponse(gptResponse, review);
                    }

                    if (gptResponse == null || gptResponse.containsKey("error")) {
                        log.error("GPT Response contains an error or is null for file '{}': {}", filePath, gptResponse);
                    } else {
                        if (provider.equals(Provider.GITLAB)) {
                            totalComments = commentGitlabService.postCommentsToGitLab(gptResponse, owner, repo, prNumber, commitId, head_sha, base_sha, start_sha, Collections.singletonList(filePath), accesToken);
                        } else if (provider.equals(Provider.GITHUB)) {
                            totalComments = commentService.postCommentsToGithub(gptResponse, owner, repo, prNumber, commitId, Collections.singletonList(filePath));
                        }
                        log.info("Added comment for PR '{}' on provider '{}'.", prNumber, provider);
                    }
                } else {
                    log.info("No edited lines found in the diff for file '{}'.", filePath);
                }
            } catch (Exception e) {
                log.error("Error processing the diff for file '{}': {}", filePath, e.getMessage(), e);
            }
        } else {
            log.error("Diff or filePath is null in the request: {}", requestBody);
        }

        return Map.of("status", "success", "message", "Review completed.", "totalComments", totalComments);
    }


    @PostMapping("/review-pr")
    public Map<String, Object> reviewCodeV2(Map<String, Object> requestBody, String filePath, String editedLinesString) {
        String codeLang = gitLabLanguageDetectorService.detectFirstLanguageInMergeRequest(filePath);

        if (raidenGptEnabled) {
            return raidenAIService.reviewCode(requestBody);
        } else {
            return openAIService.reviewCode(filePath, editedLinesString, codeLang, raidenDefaultReviewLAnguage);
        }
    }

}
