package com.example.springai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MistralResponse {
    private List<Choice> choices;
    
    public List<Choice> getChoices() {
        return choices;
    }
    
    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Message message;
        
        public Message getMessage() {
            return message;
        }
        
        public void setMessage(Message message) {
            this.message = message;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String content;
        
        public String getContent() {
            return content;
        }
        
        public void setContent(String content) {
            this.content = content;
        }
    }
}
