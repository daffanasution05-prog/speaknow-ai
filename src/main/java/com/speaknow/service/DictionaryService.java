package com.speaknow.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speaknow.model.WordEntry;
import com.speaknow.repository.WordRepository;

@Service
public class DictionaryService {
    
    @Autowired
    private WordRepository wordRepository;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String DICTIONARY_API = "https://api.dictionaryapi.dev/api/v2/entries/en/";
    
    @Value("${groq.api.key}")
    private String GROQ_API_KEY;
    
    private final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    
    // Fetch definisi dari API eksternal
    public Map<String, String> fetchWordDefinition(String word) {
        if (cache.containsKey(word.toLowerCase())) {
            return cache.get(word.toLowerCase());
        }
        
        try {
            String url = DICTIONARY_API + word.toLowerCase();
            System.out.println("📖 Fetching: " + url);
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                if (root.isArray() && root.size() > 0) {
                    JsonNode firstEntry = root.get(0);
                    JsonNode meanings = firstEntry.get("meanings");
                    
                    if (meanings != null && meanings.size() > 0) {
                        JsonNode firstMeaning = meanings.get(0);
                        String category = firstMeaning.get("partOfSpeech").asText();
                        JsonNode definitions = firstMeaning.get("definitions");
                        if (definitions != null && definitions.size() > 0) {
                            String meaning = definitions.get(0).get("definition").asText();
                            String example = definitions.get(0).has("example") ? 
                                definitions.get(0).get("example").asText() : "";
                            
                            if (example == null || example.trim().isEmpty()) {
                                throw new RuntimeException("API missing example, triggering AI fallback.");
                            }
                            
                            if (meaning.length() > 300) meaning = meaning.substring(0, 297) + "...";
                            if (example.length() > 200) example = example.substring(0, 197) + "...";
                            
                            Map<String, String> result = new HashMap<>();
                            result.put("meaning", meaning);
                            result.put("category", mapCategory(category));
                            result.put("example", example);
                            
                            cache.put(word.toLowerCase(), result);
                            return result;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ API could not find definition for '" + word + "'");
        }
        
        // Fallback to Groq AI
        Map<String, String> fallback = generateFallbackDefinition(word);
        cache.put(word.toLowerCase(), fallback);
        return fallback;
    }
    
    private Map<String, String> generateFallbackDefinition(String word) {
        Map<String, String> fallback = new HashMap<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(GROQ_API_KEY);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama3-8b-8192");
            
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are an English dictionary. Provide the meaning in Indonesian, its category (noun/verb/adjective/adverb), and a short English example sentence for the word. Format your response strictly as JSON: {\"meaning\": \"...\", \"category\": \"...\", \"example\": \"...\"}");
            messages.add(sysMsg);
            
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", "Word: " + word);
            messages.add(userMsg);
            
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.3);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(GROQ_URL, entity, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String content = root.path("choices").path(0).path("message").path("content").asText();
                
                // Coba bersihkan markdown kalau model mengembalikan code block
                if (content.startsWith("```json")) {
                    content = content.replace("```json", "").replace("```", "").trim();
                } else if (content.startsWith("```")) {
                    content = content.replace("```", "").trim();
                }
                
                JsonNode resultJson = objectMapper.readTree(content);
                fallback.put("meaning", resultJson.path("meaning").asText());
                fallback.put("category", mapCategory(resultJson.path("category").asText()));
                fallback.put("example", resultJson.path("example").asText());
                return fallback;
            }
        } catch (Exception ex) {
            System.out.println("⚠️ Groq fallback failed for word: " + word + ". " + ex.getMessage());
        }

        fallback.put("meaning", "Arti tidak ditemukan");
        fallback.put("category", "unknown");
        fallback.put("example", "Tidak ada contoh");
        return fallback;
    }
    
    private String mapCategory(String pos) {
        pos = pos.toLowerCase();
        if (pos.contains("noun")) return "noun";
        if (pos.contains("verb")) return "verb";
        if (pos.contains("adj")) return "adjective";
        if (pos.contains("adv")) return "adverb";
        return "unknown";
    }
    
    // Track kata yang digunakan user
    public void trackUsedWords(Long userId, String text) {
        if (text == null || text.trim().isEmpty()) return;
        
        String[] words = text.toLowerCase().split("\\s+");
        Set<String> uniqueWords = new HashSet<>();
        
        for (String w : words) {
            w = w.replaceAll("[^a-zA-Z']", "");
            if (w.length() > 2 && !isStopWord(w)) {
                uniqueWords.add(w);
            }
        }
        
        for (String word : uniqueWords) {
            Optional<WordEntry> existing = wordRepository.findByUserIdAndWord(userId, word);
            
            if (existing.isPresent()) {
                // UPDATE existing word (update usage count saja, tidak panggil API)
                WordEntry entry = existing.get();
                entry.setUsageCount(entry.getUsageCount() + 1);
                entry.setLastUsed(LocalDateTime.now());
                wordRepository.save(entry);
            } else {
                // CEK CACHE DULU sebelum panggil API
                Map<String, String> def;
                if (cache.containsKey(word)) {
                    def = cache.get(word);
                    System.out.println("📖 Using cache for: " + word);
                } else {
                    def = fetchWordDefinition(word);
                    cache.put(word, def);
                }
                
                WordEntry newEntry = new WordEntry();
                newEntry.setUserId(userId);
                newEntry.setWord(word);
                newEntry.setMeaning(def.get("meaning"));
                newEntry.setCategory(def.get("category"));
                newEntry.setExample(def.get("example"));
                newEntry.setUsageCount(1);
                newEntry.setMastered(false);
                wordRepository.save(newEntry);
                System.out.println("✅ Added new word: " + word);
            }
        }
    }
    
    public boolean isStopWord(String word) {
        Set<String> stop = Set.of("the", "and", "for", "you", "are", "was", "were", 
            "this", "that", "have", "has", "had", "with", "from", "your", "they", "will",
            "can", "all", "one", "two", "but", "not", "get", "got", "what", "when", "where",
            "how", "why", "who", "which", "there", "their", "then", "than", "some", "any",
            "out", "about", "been", "would", "could", "should", "just", "very", "much", "many", "also", "it", "is", "in", "on", "at", "to", "do", "does", "did", "of", "a", "an", "i", "we", "he", "she", "me", "him", "her", "us", "them", "my", "our", "its", "so", "if", "or", "as", "be", "by");
        return stop.contains(word);
    }
    
    public List<WordEntry> getUserWords(Long userId) {
        List<WordEntry> words = wordRepository.findByUserIdOrderByLastUsedDesc(userId);
        
        for (WordEntry word : words) {
            boolean needsUpdate = false;
            String meaning = word.getMeaning();
            String example = word.getExample();
            
            if (meaning == null || meaning.contains("belum terdefinisi") || meaning.contains("Arti tidak ditemukan") || meaning.trim().isEmpty()) {
                needsUpdate = true;
            }
            if (example == null || example.contains("Contoh tidak tersedia") || example.contains("Coba gunakan") || example.contains("Tidak ada contoh") || example.trim().isEmpty()) {
                needsUpdate = true;
            }
            
            if (needsUpdate) {
                new Thread(() -> {
                    try {
                        System.out.println("🔄 Auto-fixing incomplete word async: " + word.getWord());
                        Map<String, String> def = generateFallbackDefinition(word.getWord());
                        word.setMeaning(def.get("meaning"));
                        word.setCategory(def.get("category"));
                        word.setExample(def.get("example"));
                        wordRepository.save(word);
                    } catch (Exception e) {
                        System.out.println("⚠️ Failed to auto-fix word: " + word.getWord());
                    }
                }).start();
            }
        }
        
        return words;
    }
    
    public int countMastered(Long userId) {
        return wordRepository.countMasteredByUser(userId);
    }
    
    public WordEntry markAsMastered(Long userId, String word) {
        Optional<WordEntry> entry = wordRepository.findByUserIdAndWord(userId, word);
        if (entry.isPresent()) {
            WordEntry w = entry.get();
            w.setMastered(true);
            return wordRepository.save(w);
        }
        return null;
    }
    
    public List<Map<String, String>> getRecommendedWords(Long userId, int limit) {
        List<WordEntry> existing = wordRepository.findByUserIdOrderByLastUsedDesc(userId);
        Set<String> used = new HashSet<>();
        for (WordEntry w : existing) used.add(w.getWord().toLowerCase());
        
        String[] popular = {"opportunity", "challenge", "experience", "knowledge", "skill", 
            "achievement", "responsibility", "environment", "solution", "improve", "develop",
            "understand", "confident", "essential", "determination", "accomplish"};
        
        List<Map<String, String>> recs = new ArrayList<>();
        for (String word : popular) {
            if (!used.contains(word) && recs.size() < limit) {
                Map<String, String> def = fetchWordDefinition(word);
                def.put("word", word);
                recs.add(def);
            }
        }
        return recs;
    }

    public List<WordEntry> generateTargetWords(Long userId) {
        List<WordEntry> newTargets = new ArrayList<>();
        try {
            // Fetch words from Datamuse API (professional/academic related)
            String url = "https://api.datamuse.com/words?ml=professional&max=15";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                for (JsonNode node : root) {
                    if (newTargets.size() >= 5) break; // Cukup tambahkan 5 kata baru per request
                    
                    String word = node.get("word").asText().toLowerCase();
                    
                    // Skip if word is a phrase (contains space)
                    if (word.contains(" ")) continue;
                    
                    Optional<WordEntry> existing = wordRepository.findByUserIdAndWord(userId, word);
                    if (existing.isEmpty()) {
                        Map<String, String> def = fetchWordDefinition(word);
                        
                        WordEntry newEntry = new WordEntry();
                        newEntry.setUserId(userId);
                        newEntry.setWord(word);
                        newEntry.setMeaning(def.get("meaning"));
                        newEntry.setCategory(def.get("category"));
                        newEntry.setExample(def.get("example"));
                        newEntry.setUsageCount(0); // 0 means it's a TARGET word (not used yet)
                        newEntry.setMastered(false);
                        
                        wordRepository.save(newEntry);
                        newTargets.add(newEntry);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Error generating target words: " + e.getMessage());
        }
        return newTargets;
    }

    public List<String> getUnusedTargetWords(Long userId) {
        List<WordEntry> words = wordRepository.findByUserIdOrderByLastUsedDesc(userId);
        List<String> targets = new ArrayList<>();
        for (WordEntry w : words) {
            if (w.getUsageCount() == 0 && !w.isMastered()) {
                targets.add(w.getWord());
            }
        }
        return targets;
    }
}