package com.example.springsupervisorai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supervisor.prompts")
public class SupervisorPromptProperties {

    private String planningSystem = "";
    private String planningTemplate = "";
    private String planningRepairTemplate = "";
    private String hitlPolicySystem = "";
    private String hitlPolicyTemplate = "";
    private String hitlPolicyRepairTemplate = "";
    private String composeSystem = "";
    private String composeTemplate = "";

    public String getPlanningSystem() {
        return planningSystem;
    }

    public void setPlanningSystem(String planningSystem) {
        this.planningSystem = planningSystem;
    }

    public String getPlanningTemplate() {
        return planningTemplate;
    }

    public void setPlanningTemplate(String planningTemplate) {
        this.planningTemplate = planningTemplate;
    }

    public String getPlanningRepairTemplate() {
        return planningRepairTemplate;
    }

    public void setPlanningRepairTemplate(String planningRepairTemplate) {
        this.planningRepairTemplate = planningRepairTemplate;
    }

    public String getHitlPolicySystem() {
        return hitlPolicySystem;
    }

    public void setHitlPolicySystem(String hitlPolicySystem) {
        this.hitlPolicySystem = hitlPolicySystem;
    }

    public String getHitlPolicyTemplate() {
        return hitlPolicyTemplate;
    }

    public void setHitlPolicyTemplate(String hitlPolicyTemplate) {
        this.hitlPolicyTemplate = hitlPolicyTemplate;
    }

    public String getHitlPolicyRepairTemplate() {
        return hitlPolicyRepairTemplate;
    }

    public void setHitlPolicyRepairTemplate(String hitlPolicyRepairTemplate) {
        this.hitlPolicyRepairTemplate = hitlPolicyRepairTemplate;
    }

    public String getComposeSystem() {
        return composeSystem;
    }

    public void setComposeSystem(String composeSystem) {
        this.composeSystem = composeSystem;
    }

    public String getComposeTemplate() {
        return composeTemplate;
    }

    public void setComposeTemplate(String composeTemplate) {
        this.composeTemplate = composeTemplate;
    }
}
