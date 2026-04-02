package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "http-llm")
public class HttpLlmProperties {

    private Provider openai = new Provider();
    private Provider gemini = new Provider();
    private Provider geminiLite = new Provider();
    private Provider mistral = new Provider();

    public Provider getOpenai() {
        return openai;
    }

    public void setOpenai(Provider openai) {
        this.openai = openai;
    }

    public Provider getGemini() {
        return gemini;
    }

    public void setGemini(Provider gemini) {
        this.gemini = gemini;
    }

    public Provider getGeminiLite() {
        return geminiLite;
    }

    public void setGeminiLite(Provider geminiLite) {
        this.geminiLite = geminiLite;
    }

    public Provider getMistral() {
        return mistral;
    }

    public void setMistral(Provider mistral) {
        this.mistral = mistral;
    }

    public static class Provider {
        private String apiKey = "";
        private String model = "";
        private String baseUrl = "";
        private int maxTokens = 16384;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}
