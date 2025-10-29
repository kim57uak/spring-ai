package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {
    
    private String system;
    private String toolDecision;
    private String toolChoice;
    private String finalAnswer;
    private String contextAware;
    
    public String getSystem() {
        return system;
    }
    
    public void setSystem(String system) {
        this.system = system;
    }
    
    public String getToolDecision() {
        return toolDecision;
    }
    
    public void setToolDecision(String toolDecision) {
        this.toolDecision = toolDecision;
    }
    
    public String getToolChoice() {
        return toolChoice;
    }
    
    public void setToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
    }
    
    public String getFinalAnswer() {
        return finalAnswer;
    }
    
    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }
    
    public String getContextAware() {
        return contextAware;
    }
    
    public void setContextAware(String contextAware) {
        this.contextAware = contextAware;
    }
}