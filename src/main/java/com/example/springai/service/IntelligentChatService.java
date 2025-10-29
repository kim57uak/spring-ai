package com.example.springai.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import com.example.springai.config.PromptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;

@Service
public class IntelligentChatService {
    
    private static final Logger logger = LoggerFactory.getLogger(IntelligentChatService.class);
    private final ChatService chatService;
    private final McpService mcpService;
    private final PromptProperties promptProperties;
    private final Map<String, MessageWindowChatMemory> sessionMemories = new ConcurrentHashMap<>();
    
    public IntelligentChatService(ChatService chatService, McpService mcpService, PromptProperties promptProperties) {
        this.chatService = chatService;
        this.mcpService = mcpService;
        this.promptProperties = promptProperties;
    }
    
    public String chat(String sessionId, String message) {
        logger.info("Processing chat request - Session: {}, Message: {}", sessionId, message);
        
        MessageWindowChatMemory memory = getOrCreateMemory(sessionId);
        memory.add(UserMessage.from(message));
        
        String toolDecision = getToolDecision(memory, message);
        logger.info("Tool decision for session {}: {}", sessionId, toolDecision);
        
        String finalResponse;
        if (toolDecision.toLowerCase().contains("yes")) {
            logger.info("Using tools for session: {}", sessionId);
            finalResponse = handleWithTools(memory, message);
        } else {
            logger.info("Using internal knowledge for session: {}", sessionId);
            finalResponse = handleWithoutTools(memory, message);
        }
        
        memory.add(AiMessage.from(finalResponse));
        logger.info("Chat response generated for session: {}, length: {}", sessionId, finalResponse.length());
        return finalResponse;
    }
    
    public Flux<String> streamChat(String sessionId, String message) {
        String fullResponse = chat(sessionId, message);
        String[] words = fullResponse.split(" ");
        return Flux.fromArray(words)
            .delayElements(Duration.ofMillis(50))
            .map(word -> word + " ");
    }
    
    private String getToolDecision(MessageWindowChatMemory memory, String message) {
        StringBuilder prompt = new StringBuilder();
        
        // 시스템 프롬프트 추가
        String systemPrompt = promptProperties.getToolDecision();
        prompt.append(systemPrompt);
        
        // 대화 히스토리 추가
        List<ChatMessage> messages = memory.messages();
        if (messages.size() > 1) {
            prompt.append(" ");
            for (int i = Math.max(0, messages.size() - 4); i < messages.size() - 1; i++) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    prompt.append("User: ").append(msg.text()).append(" ");
                } else if (msg instanceof AiMessage) {
                    prompt.append("Assistant: ").append(msg.text()).append(" ");
                }
            }
        }
        
        // 현재 사용자 메시지 추가
        prompt.append(" User: ").append(message);
        
        String fullPrompt = prompt.toString();
        
        // 프롬프트 로깅
        logger.info("=== 도구 결정 프롬프트 ===");
        logger.info("시스템 프롬프트: {}", systemPrompt);
        logger.info("사용자 메시지: {}", message);
        logger.info("전체 프롬프트:\n{}", fullPrompt);
        logger.info("프롬프트 길이: {} 문자", fullPrompt.length());
        logger.info("=== 도구 결정 프롬프트 끝 ===");
        
        return chatService.chat(fullPrompt);
    }
    
    private String handleWithTools(MessageWindowChatMemory memory, String message) {
        // 단순화: 바로 도구 선택하고 실행
        String toolChoice = getToolChoice(memory, message);
        logger.info("Tool choice: {}", toolChoice);
        
        try {
            String toolResult = executeSelectedTool(toolChoice, message);
            logger.info("Tool result length: {}", toolResult.length());
            
            return generateFinalAnswer(memory, message, toolResult);
        } catch (Exception e) {
            logger.error("Tool execution error: {}", e.getMessage(), e);
            return "도구 실행 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
    
    private String handleWithoutTools(MessageWindowChatMemory memory, String message) {
        return chatService.chat(buildContextPrompt(memory, message));
    }
    
    private String getToolChoice(MessageWindowChatMemory memory, String message) {
        return getToolChoice(memory, message, "");
    }
    
    private String getToolChoice(MessageWindowChatMemory memory, String message, String previousResults) {
        String availableTools = getAvailableToolsInfo();
        StringBuilder prompt = new StringBuilder();
        
        String systemInstruction = promptProperties.getToolChoice();
        
        prompt.append("AVAILABLE TOOLS: ");
        prompt.append(availableTools).append(" ");
        prompt.append("USER QUERY: ");
        prompt.append(message).append(" ");
        
        if (!previousResults.isEmpty()) {
            prompt.append("PREVIOUS RESULTS: ");
            prompt.append(previousResults).append(" ");
            prompt.append("NEXT ACTION: Based on previous results, choose next tool or return 'COMPLETE' if sufficient data exists. ");
        }
        
        prompt.append("INSTRUCTIONS: ");
        prompt.append(systemInstruction);
        
        String fullPrompt = prompt.toString();
        
        logger.info("=== 도구 선택 프롬프트 ===");
        logger.info("사용 가능한 도구: {}", availableTools);
        logger.info("사용자 쿼리: {}", message);
        logger.info("이전 결과 길이: {} 문자", previousResults.length());
        logger.info("전체 프롬프트:\n{}", fullPrompt);
        logger.info("프롬프트 길이: {} 문자", fullPrompt.length());
        logger.info("=== 도구 선택 프롬프트 끝 ===");
        
        String response = chatService.chat(fullPrompt);
        logger.info("도구 선택 AI 응답: {}", response);
        
        return response;
    }
    
    private String createExecutionPlan(String userQuery) {
        String availableTools = getAvailableToolsInfo();
        String prompt = String.format(
            "Create a step-by-step execution plan for this query using available tools. Query: %s Available Tools: %s Create a numbered plan (1-3 steps max). Think about what information is needed and which tools can provide it. Return ONLY the numbered plan, no explanations.",
            userQuery, availableTools
        );
        
        return chatService.chat(prompt);
    }
    
    private String getNextAction(String userQuery, String plan, String previousResults, int currentStep) {
        String availableTools = getAvailableToolsInfo();
        String reactPrompt = promptProperties.getToolChoice();
        
        String prompt = String.format(
            "%s ReAct Step %d: THOUGHT: What do I need to accomplish: %s PLAN: %s OBSERVATION: %s AVAILABLE TOOLS: %s THOUGHT: What information am I still missing? What's the next logical step? ACTION: Choose next tool or return 'COMPLETE' if I have sufficient information. Return JSON format: {\"server\":\"exact_name\",\"tool\":\"exact_name\"} or 'COMPLETE'",
            reactPrompt, currentStep, userQuery, plan, previousResults, availableTools
        );
        
        logger.info("=== ReAct Step {} 프롬프트 ===", currentStep);
        logger.info("전체 프롬프트:\n{}", prompt);
        logger.info("=== ReAct Step {} 프롬프트 끝 ===", currentStep);
        
        return chatService.chat(prompt);
    }
    
    private String getAvailableToolsInfo() {
        try {
            String toolsInfo = mcpService.getDetailedToolsInfo();
            logger.info("Retrieved tools info: {}", toolsInfo);
            return toolsInfo;
        } catch (Exception e) {
            logger.error("Failed to get tools info: {}", e.getMessage());
            return "No MCP tools available";
        }
    }
    
    private String executeSelectedTool(String toolChoice, String message) {
        try {
            ToolSelection selection = parseToolSelection(toolChoice);
            if (selection != null) {
                String correctedServer = mapServerName(selection.server, selection.tool);
                String processedQuery = processQueryForTool(selection.tool, message);
                logger.info("Executing tool: {} on server: {} with query: {}", selection.tool, correctedServer, processedQuery);
                return mcpService.executeToolOnServer(correctedServer, selection.tool, processedQuery);
            }
        } catch (Exception e) {
            logger.error("Tool execution failed: {}", e.getMessage(), e);
        }
        
        logger.info("Fallback to perplexity search");
        return mcpService.executeToolOnServer("search-mcp-server", "perplexitySearch", message);
    }
    
    private String processQueryForTool(String toolName, String message) {
        if (toolName.contains("ticker") || toolName.contains("stock")) {
            // 주식 관련 도구는 회사명만 추출
            return extractCompanyName(message);
        }
        return message;
    }
    
    private String extractCompanyName(String message) {
        // 간단한 회사명 추출 로직
        String[] keywords = {"주가", "검색", "알려", "찾아", "보여"};
        String result = message;
        for (String keyword : keywords) {
            result = result.replace(keyword, "").trim();
        }
        return result.isEmpty() ? message : result;
    }
    
    private String mapServerName(String aiSelectedServer, String toolName) {
        // AI가 선택한 서버명을 실제 서버명으로 매핑
        switch (toolName.toLowerCase()) {
            case "search":
            case "fetchurl":
            case "perplexitysearch":
                return "search-mcp-server";
            case "get_stock_snapshot":
            case "get_multiple_stock_quotes":
            case "get_multiple_domestic_stock_quotes":
            case "get_multiple_crypto_quotes":
                return "search-economy-index";
            default:
                // 기본적으로 AI가 선택한 서버명 사용, 없으면 search-mcp-server
                return mcpService.hasServer(aiSelectedServer) ? aiSelectedServer : "search-mcp-server";
        }
    }
    
    private ToolSelection parseToolSelection(String toolChoice) {
        try {
            // 다양한 JSON 형식 처리
            String cleanedChoice = toolChoice.trim();
            
            // markdown 코드 블록 제거
            if (cleanedChoice.startsWith("```")) {
                int start = cleanedChoice.indexOf("{");
                int end = cleanedChoice.lastIndexOf("}");
                if (start != -1 && end != -1) {
                    cleanedChoice = cleanedChoice.substring(start, end + 1);
                }
            }
            
            // JSON 파싱
            if (cleanedChoice.contains("{") && cleanedChoice.contains("}")) {
                String jsonPart = cleanedChoice.substring(cleanedChoice.indexOf("{"), cleanedChoice.lastIndexOf("}") + 1);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(jsonPart);
                
                if (node.has("server") && node.has("tool")) {
                    String server = node.get("server").asText();
                    String tool = node.get("tool").asText();
                    
                    logger.info("Parsed tool selection - Server: '{}', Tool: '{}'", server, tool);
                    return new ToolSelection(server, tool);
                } else {
                    logger.warn("JSON missing required fields. Available fields: {}", node.fieldNames());
                }
            }
        } catch (Exception e) {
            logger.warn("JSON parsing failed for input '{}': {}", toolChoice, e.getMessage());
        }
        return null;
    }
    
    private static class ToolSelection {
        final String server;
        final String tool;
        
        ToolSelection(String server, String tool) {
            this.server = server;
            this.tool = tool;
        }
    }
    

    
    private String generateFinalAnswer(MessageWindowChatMemory memory, String message, String toolResult) {
        StringBuilder prompt = new StringBuilder();
        
        // 시스템 프롬프트 추가
        String systemPrompt = promptProperties.getFinalAnswer();
        prompt.append(systemPrompt);
        
        // 대화 히스토리 추가
        List<ChatMessage> messages = memory.messages();
        if (messages.size() > 1) {
            prompt.append(" ");
            for (int i = Math.max(0, messages.size() - 3); i < messages.size() - 1; i++) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    prompt.append("User: ").append(msg.text()).append(" ");
                } else if (msg instanceof AiMessage) {
                    prompt.append("Assistant: ").append(msg.text()).append(" ");
                }
            }
        }
        
        prompt.append(" Q: ").append(message).append(" Data: ").append(toolResult);
        
        String fullPrompt = prompt.toString();
        
        // 프롬프트 로깅
        logger.info("=== 최종 답변 생성 프롬프트 ===");
        logger.info("시스템 프롬프트: {}", systemPrompt);
        logger.info("사용자 질문: {}", message);
        logger.info("도구 결과 길이: {} 문자", toolResult.length());
        logger.info("도구 결과 내용: {}", toolResult.length() > 500 ? toolResult.substring(0, 500) + "..." : toolResult);
        logger.info("전체 프롬프트:\n{}", fullPrompt);
        logger.info("프롬프트 길이: {} 문자", fullPrompt.length());
        logger.info("=== 최종 답변 생성 프롬프트 끝 ===");
        
        return chatService.chat(fullPrompt);
    }
    
    private String buildContextPrompt(MessageWindowChatMemory memory, String message) {
        List<ChatMessage> messages = memory.messages();
        
        String systemPrompt;
        StringBuilder prompt = new StringBuilder();
        
        if (messages.size() <= 1) {
            systemPrompt = promptProperties.getSystem();
            prompt.append(systemPrompt).append(" ").append(message);
        } else {
            systemPrompt = promptProperties.getContextAware();
            prompt.append(systemPrompt).append(" ");
            
            for (int i = Math.max(0, messages.size() - 6); i < messages.size() - 1; i++) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    prompt.append("User: ").append(msg.text()).append(" ");
                } else if (msg instanceof AiMessage) {
                    prompt.append("Assistant: ").append(msg.text()).append(" ");
                }
            }
            
            prompt.append(" Q: ").append(message);
        }
        
        String fullPrompt = prompt.toString();
        
        // 프롬프트 로깅
        logger.info("=== 컨텍스트 기반 응답 프롬프트 ===");
        logger.info("시스템 프롬프트: {}", systemPrompt);
        logger.info("사용자 메시지: {}", message);
        logger.info("대화 히스토리 메시지 수: {}", messages.size() - 1);
        logger.info("전체 프롬프트:\n{}", fullPrompt);
        logger.info("프롬프트 길이: {} 문자", fullPrompt.length());
        logger.info("=== 컨텍스트 기반 응답 프롬프트 끝 ===");
        
        return fullPrompt;
    }
    
    private MessageWindowChatMemory getOrCreateMemory(String sessionId) {
        return sessionMemories.computeIfAbsent(sessionId, 
            id -> MessageWindowChatMemory.withMaxMessages(15));
    }
    
    public void clearSession(String sessionId) {
        MessageWindowChatMemory memory = sessionMemories.remove(sessionId);
        if (memory != null) {
            memory.clear();
        }
    }
    
    public int getMessageCount(String sessionId) {
        MessageWindowChatMemory memory = sessionMemories.get(sessionId);
        return memory != null ? memory.messages().size() : 0;
    }
}