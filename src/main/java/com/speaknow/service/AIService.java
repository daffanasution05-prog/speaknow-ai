package com.speaknow.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speaknow.model.ChatRequest;
import com.speaknow.model.ChatResponse;
import com.speaknow.model.ConversationHistory;
import com.speaknow.model.SessionRequest;
import com.speaknow.model.SessionResponse;
import com.speaknow.repository.ConversationHistoryRepository;

@Service
public class AIService {

    @Autowired
    private DictionaryService dictionaryService;
    
    @Autowired
    private ConversationHistoryRepository historyRepo;

    @Value("${groq.api.key}")
    private String GROQ_API_KEY;
    private final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private RestTemplate restTemplate = new RestTemplate();
    private ObjectMapper objectMapper = new ObjectMapper();

    // ========== CHAT MODE ==========
public ChatResponse chat(ChatRequest request) {
    Long userId = request.getUserId();
    String sessionId = request.getSessionId();
    String userMessage = request.getMessage();
    String mode = request.getMode();

    saveMessage(userId, sessionId, "user", userMessage);

    List<ConversationHistory> history = historyRepo.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    String userLevel = getUserLevel(userId);
    
    String aiResponse;
    if ("voice".equals(mode) || "practice".equals(mode)) {
        aiResponse = callGroq(userMessage, mode, userLevel, history, "chat", userId);
        String[] words = aiResponse.split(" ");
        if (words.length > 20) {
            aiResponse = String.join(" ", java.util.Arrays.copyOf(words, 20)) + "...";
        }
    } else {
        aiResponse = callGroq(userMessage, mode, userLevel, history, "chat", userId);
    }
    
    saveMessage(userId, sessionId, "assistant", aiResponse);

    // 🔥 PINDAHKAN TRACKING KE SINI (SETELAH AI RESPON, JADI ASYNC)
    // User sudah dapet response, tracking jalan di background
    if (dictionaryService != null) {
        // HANYA track user message, BUKAN AI response
        new Thread(() -> {
            try {
                dictionaryService.trackUsedWords(userId, userMessage);
            } catch (Exception e) {
                System.out.println("⚠️ Dictionary tracking error: " + e.getMessage());
            }
        }).start();
    }

    ChatResponse response = new ChatResponse();
    response.setMessage(aiResponse);
    response.setGrammarFeedback(null);
    response.setHint(null);
    response.setNaturalAnswer(null);
    response.setAcademicAnswer(null);
    response.setCasualAnswer(null);
    
    if (!"voice".equals(mode)) {
        response.setTopicSuggestions(generateTopicSuggestions(mode, aiResponse, history));
    }

    return response;
}

    // ========== HINT ONLY ==========
    public String generateHintOnly(String userMessage, String lastAIMessage, String mode) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are a helpful English tutor. Based on the conversation, give 1 short hint (1 sentence) to help the user respond to the AI's last question. Be specific and useful. Start with 💡 Hint:");
            messages.add(systemMsg);
            
            Map<String, String> contextMsg = new HashMap<>();
            contextMsg.put("role", "user");
            contextMsg.put("content", "AI's last question: \"" + lastAIMessage + "\"\nMy last message: \"" + userMessage + "\"\n\nGive me a hint on how to respond to the AI's question.");
            messages.add(contextMsg);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.3-70b-versatile");
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 100);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(GROQ_API_KEY);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_URL, HttpMethod.POST, entity, String.class);
            
            JsonNode root = objectMapper.readTree(response.getBody());
            String hint = root.path("choices").get(0).path("message").path("content").asText();
            
            hint = hint.replace("💡 Hint:", "").trim();
            if (!hint.startsWith("💡")) hint = "💡 Hint: " + hint;
            return hint;
            
        } catch (Exception e) {
            return "💡 Hint: Try answering the AI's question with a complete sentence!";
        }
    }
    
    // ========== TRANSLATION ==========
    public String translateMessage(String message, String type, Long userId) {
        try {
            if ("smart".equals(type)) {
                List<com.speaknow.model.WordEntry> userWords = dictionaryService.getUserWords(userId);
                java.util.Set<String> knownWords = new java.util.HashSet<>();
                for (com.speaknow.model.WordEntry w : userWords) {
                    knownWords.add(w.getWord().toLowerCase());
                }
                
                String[] words = message.toLowerCase().split("\\s+");
                java.util.Set<String> unknownWords = new java.util.HashSet<>();
                for(String w : words) {
                    w = w.replaceAll("[^a-z]", "");
                    if(w.length() > 2 && !dictionaryService.isStopWord(w) && !knownWords.contains(w)) {
                        unknownWords.add(w);
                    }
                }
                
                if (unknownWords.isEmpty()) {
                    return "✅ Kosakata sudah familier bagi Anda (tidak ada kata sulit/baru).";
                }
                
                String wordsToTranslate = String.join(", ", unknownWords);
                return executeTranslationGroq("Translate ONLY these specific English words to Indonesian: " + wordsToTranslate + ". Format as a bulleted list: '- Word: Translation'");
            } else {
                return executeTranslationGroq("Translate the following English text to Indonesian accurately and naturally:\n\n" + message);
            }
        } catch (Exception e) {
            System.err.println("Translation error: " + e.getMessage());
            return "Maaf, terjadi kesalahan saat menerjemahkan.";
        }
    }
    
    private String executeTranslationGroq(String prompt) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.3-70b-versatile");
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 200);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(GROQ_API_KEY);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_URL, HttpMethod.POST, entity, String.class);
            
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("choices").get(0).path("message").path("content").asText().trim();
        } catch (Exception e) {
            System.err.println("Groq Translation API Error: " + e.getMessage());
            e.printStackTrace();
            return "Translation failed.";
        }
    }

    // ========== GRAMMAR CHECK (3 SCORES: Grammar, Clarity, Confidence) ==========
    public Map<String, Object> checkGrammarWithScore(String text) {
        Map<String, Object> result = new HashMap<>();
        result.put("grammarScore", 5);
        result.put("clarityScore", 5);
        result.put("confidenceScore", 5);
        result.put("feedback", "Analisis tidak tersedia");
        
        if (text == null || text.trim().isEmpty()) return result;
        
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Anda adalah expert English grammar teacher. Analisis kalimat user dengan JUJUR dan KETAT.\n" +
                   "Return HANYA dalam format JSON ini (TIDAK BOLEH ADA TEKS LAIN):\n" +
                   "{\"grammarScore\": <0-10>, \"clarityScore\": <0-10>, \"confidenceScore\": <0-10>, \"feedback\": \"<max 2 kalimat Bahasa Indonesia>\"}\n\n" +
                   "SCORING:\n" +
                   "- grammarScore: Akurasi grammar (tense, subject-verb, articles, word order)\n" +
                   "- clarityScore: Kejelasan & struktur kalimat\n" +
                   "- confidenceScore: Apakah penutur menguasai topik & menggunakan vocab yang tepat\n\n" +
                   "FEEDBACK (max 2 kalimat):\n" +
                   "- Kalimat 1: Apa kesalahannya (jika ada)\n" +
                   "- Kalimat 2: Versi yang benar\n" +
                   "Gunakan Bahasa Indonesia saja. Jika SEMPURNA, berikan compliment singkat.");
            messages.add(systemMsg);
            
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", "Analisis: \"" + text + "\"");
            messages.add(userMsg);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.3-70b-versatile");
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.2);
            requestBody.put("max_tokens", 200);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(GROQ_API_KEY);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_URL, HttpMethod.POST, entity, String.class);
            
            JsonNode root = objectMapper.readTree(response.getBody());
            String aiResult = root.path("choices").get(0).path("message").path("content").asText();
            
            System.out.println("Grammar Check Response: " + aiResult);
            
            // Clean response
            aiResult = aiResult.trim();
            if (aiResult.startsWith("```json")) aiResult = aiResult.substring(7);
            if (aiResult.startsWith("```")) aiResult = aiResult.substring(3);
            if (aiResult.endsWith("```")) aiResult = aiResult.substring(0, aiResult.length() - 3);
            aiResult = aiResult.trim();
            
            // Parse JSON
            try {
                JsonNode jsonNode = objectMapper.readTree(aiResult);
                
                if (jsonNode.has("grammarScore")) {
                    int score = jsonNode.get("grammarScore").asInt();
                    result.put("grammarScore", Math.max(0, Math.min(10, score)));
                }
                if (jsonNode.has("clarityScore")) {
                    int score = jsonNode.get("clarityScore").asInt();
                    result.put("clarityScore", Math.max(0, Math.min(10, score)));
                }
                if (jsonNode.has("confidenceScore")) {
                    int score = jsonNode.get("confidenceScore").asInt();
                    result.put("confidenceScore", Math.max(0, Math.min(10, score)));
                }
                if (jsonNode.has("feedback")) {
                    result.put("feedback", jsonNode.get("feedback").asText());
                }
            } catch (Exception parseError) {
                System.out.println("JSON parse failed, extracting manually");
                result.put("feedback", aiResult);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            result.put("grammarScore", 5);
            result.put("clarityScore", 5);
            result.put("confidenceScore", 5);
            result.put("feedback", "Analisis tidak dapat diproses. Coba lagi.");
        }
        return result;
    }

    // ========== ALTERNATIVE STYLES ==========
    public Map<String, String> generateStyleAnswers(String text) {
        Map<String, String> result = new HashMap<>();
        result.put("natural", text);
        result.put("ielts", text);
        result.put("slang", text);
        
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are a text rewriter. REWRITE the user's sentence, NOT answer it.\n" +
                   "Rewrite this exact sentence in 3 different styles:\n" +
                   "NATURAL: friendly daily conversation, fix grammar\n" +
                   "IELTS: formal, academic, sophisticated\n" +
                   "SLANG: casual, relaxed, everyday speech\n" +
                   "IMPORTANT: Do NOT answer the question. Just rewrite the sentence.\n" +
                   "Return ONLY in JSON format: {\"natural\": \"...\", \"ielts\": \"...\", \"slang\": \"...\"}");
            messages.add(systemMsg);
            
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", "Rewrite this sentence: \"" + text + "\"");
            messages.add(userMsg);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.3-70b-versatile");
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.5);
            requestBody.put("max_tokens", 200);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(GROQ_API_KEY);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_URL, HttpMethod.POST, entity, String.class);
            
            JsonNode root = objectMapper.readTree(response.getBody());
            String aiResult = root.path("choices").get(0).path("message").path("content").asText();
            
            // Parse JSON response
            try {
                JsonNode jsonNode = objectMapper.readTree(aiResult);
                if (jsonNode.has("natural")) result.put("natural", jsonNode.get("natural").asText());
                if (jsonNode.has("ielts")) result.put("ielts", jsonNode.get("ielts").asText());
                if (jsonNode.has("slang")) result.put("slang", jsonNode.get("slang").asText());
            } catch (Exception e) {
                // Fallback ke format lama
                if (aiResult.contains("NATURAL:") && aiResult.contains("IELTS:") && aiResult.contains("SLANG:")) {
                    String natural = extractBetween(aiResult, "NATURAL:", "IELTS:");
                    String ielts = extractBetween(aiResult, "IELTS:", "SLANG:");
                    String slang = aiResult.substring(aiResult.indexOf("SLANG:") + 6).trim();
                    if (!natural.isEmpty()) result.put("natural", natural);
                    if (!ielts.isEmpty()) result.put("ielts", ielts);
                    if (!slang.isEmpty()) result.put("slang", slang);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private String getUserLevel(Long userId) {
        return "Intermediate";
    }

        private String callGroq(String userMessage, String mode, String level, List<ConversationHistory> history, String purpose, Long userId) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            
            List<String> targetWords = new ArrayList<>();
            if (dictionaryService != null && userId != null) {
                targetWords = dictionaryService.getUnusedTargetWords(userId);
            }
            
            String systemPrompt = getSystemPrompt(mode, level, purpose, targetWords);
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
            
            int start = Math.max(0, history.size() - 10);
            for (int i = start; i < history.size(); i++) {
                ConversationHistory h = history.get(i);
                Map<String, String> msg = new HashMap<>();
                msg.put("role", h.getRole());
                msg.put("content", h.getMessage());
                messages.add(msg);
            }
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.3-70b-versatile");
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.8);
            requestBody.put("max_tokens", purpose.equals("chat") ? 150 : 300);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(GROQ_API_KEY);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_URL, HttpMethod.POST, entity, String.class);
            
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("choices").get(0).path("message").path("content").asText();
            
        } catch (Exception e) {
            e.printStackTrace();
            return getFallbackResponse(userMessage);
        }
    }

    private String getSystemPrompt(String mode, String level, String purpose, List<String> targetWords) {
        String targetVocabInstruction = "";
        if (targetWords != null && !targetWords.isEmpty()) {
            targetVocabInstruction = "\n🎯 TARGET VOCABULARY TO TEACH: " + String.join(", ", targetWords) + 
                "\nTry to naturally use 1-2 of these words in your response so the user can learn them. " +
                "If the user uses them correctly, praise them. If they use any words incorrectly, gently correct them.";
        }

        if (purpose.equals("chat")) {
            if (mode.equals("practice") || mode.equals("voice")) {
                return """
                    You are a friendly English conversation partner.
                    You are having a normal, natural conversation with someone who wants to practice English.
                    
                    RULES:
                    1. Keep responses SHORT (1-2 sentences, 10-20 words)
                    2. Be conversational and friendly
                    3. You can talk about ANY topic the user brings up
                    4. Ask follow-up questions to keep conversation flowing
                    5. DO NOT give lengthy explanations
                    %s
                    
                    User level: %s
                    """.formatted(targetVocabInstruction, level);
            } else if (mode.equals("guided")) {
                return """
                    You are an English tutor. Help user improve their English naturally.
                    Keep responses short. Correct grammar errors gently.
                    Ask follow-up questions. %s
                    User level: %s
                    """.formatted(targetVocabInstruction, level);
            } else if (mode.equals("challenge")) {
                return """
                    You are a strict HR interviewer. Ask professional interview questions.
                    Keep responses short. Be strict but fair. %s
                    User level: %s
                    """.formatted(targetVocabInstruction, level);
            }
        }
        return "You are a helpful assistant. Answer briefly and naturally.";
    }

    private String extractBetween(String text, String start, String end) {
        int startIdx = text.indexOf(start) + start.length();
        int endIdx = text.indexOf(end);
        if (startIdx > start.length() - 1 && endIdx > startIdx) {
            return text.substring(startIdx, endIdx).trim();
        }
        return "";
    }

    private String getFallbackResponse(String userMessage) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("school")) return "Oh, school! What subject do you like most?";
        if (lower.contains("hello") || lower.contains("hi")) return "Hi there! How can I help you practice English?";
        if (lower.contains("thank")) return "You're welcome! Keep practicing!";
        return "That's interesting! Tell me more about that.";
    }

    private void saveMessage(Long userId, String sessionId, String role, String message) {
        ConversationHistory history = new ConversationHistory();
        history.setUserId(userId);
        history.setSessionId(sessionId);
        history.setRole(role);
        history.setMessage(message);
        history.setCreatedAt(LocalDateTime.now());
        historyRepo.save(history);
    }

    private String[] generateTopicSuggestions(String mode, String aiResponse, List<ConversationHistory> history) {
        if (!mode.equals("practice")) {
            return new String[0];
        }

        String lastTopics = "";
        int count = 0;
        for (int i = history.size() - 1; i >= 0 && count < 5; i--) {
            ConversationHistory h = history.get(i);
            if (h.getRole().equals("user")) {
                lastTopics += h.getMessage() + " ";
                count++;
            }
        }

        String[] suggestions = new String[5];
        suggestions[0] = "Talk about your hobbies";
        suggestions[1] = "Describe your daily routine";
        suggestions[2] = "Share your favorite food";
        suggestions[3] = "Ask me anything random!";
        suggestions[4] = "Choose your own topic";

        if (lastTopics.toLowerCase().contains("food") || lastTopics.toLowerCase().contains("eat")) {
            suggestions[0] = "Tell me about cooking";
            suggestions[1] = "Your favorite restaurant";
        } else if (lastTopics.toLowerCase().contains("work") || lastTopics.toLowerCase().contains("job")) {
            suggestions[0] = "Describe your job";
            suggestions[1] = "Work-life balance";
        } else if (lastTopics.toLowerCase().contains("travel") || lastTopics.toLowerCase().contains("trip")) {
            suggestions[0] = "Your dream vacation";
            suggestions[1] = "Travel experiences";
        }

        return suggestions;
    }


}