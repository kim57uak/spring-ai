package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {
    
    private String system = "";
    private String agentSystem = "";
    private String toolChoice = "";
    private String finalAnswer = "";
    private String composeRules = "";
    private String composePromptTemplate = "";
    private String toolPlanningPromptTemplate = "";
    private String plannerRepairPromptTemplate = "";
    private String dateHintsTemplate = "";
    
    public String getSystem() {
        return system;
    }
    
    public void setSystem(String system) {
        this.system = system;
    }

    public String getAgentSystem() {
        return agentSystem;
    }

    public void setAgentSystem(String agentSystem) {
        this.agentSystem = agentSystem;
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

    public String getComposeRules() {
        return composeRules;
    }

    public void setComposeRules(String composeRules) {
        this.composeRules = composeRules;
    }

    public String getComposePromptTemplate() {
        return composePromptTemplate;
    }

    public void setComposePromptTemplate(String composePromptTemplate) {
        this.composePromptTemplate = composePromptTemplate;
    }

    public String getToolPlanningPromptTemplate() {
        return toolPlanningPromptTemplate;
    }

    public void setToolPlanningPromptTemplate(String toolPlanningPromptTemplate) {
        this.toolPlanningPromptTemplate = toolPlanningPromptTemplate;
    }

    public String getPlannerRepairPromptTemplate() {
        return plannerRepairPromptTemplate;
    }

    public void setPlannerRepairPromptTemplate(String plannerRepairPromptTemplate) {
        this.plannerRepairPromptTemplate = plannerRepairPromptTemplate;
    }

    public String getDateHintsTemplate() {
        return dateHintsTemplate;
    }

    public void setDateHintsTemplate(String dateHintsTemplate) {
        this.dateHintsTemplate = dateHintsTemplate;
    }
}
