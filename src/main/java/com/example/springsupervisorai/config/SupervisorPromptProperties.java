package com.example.springsupervisorai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supervisor 프롬프트 템플릿 설정.
 * <p>
 * 애플리케이션 설정의 {@code supervisor.prompts.*} 프리픽스를 planning, HITL 정책 평가,
 * compose, A2UI compose 렌더러에서 사용하는 타입 필드에 매핑한다.
 */
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
    private String composeA2uiSystem = "";
    private String composeA2uiTemplate = "";
    private String composeA2uiRepairTemplate = "";

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

    public String getComposeA2uiSystem() {
        return composeA2uiSystem;
    }

    public void setComposeA2uiSystem(String composeA2uiSystem) {
        this.composeA2uiSystem = composeA2uiSystem;
    }

    public String getComposeA2uiTemplate() {
        return composeA2uiTemplate;
    }

    public void setComposeA2uiTemplate(String composeA2uiTemplate) {
        this.composeA2uiTemplate = composeA2uiTemplate;
    }

    public String getComposeA2uiRepairTemplate() {
        return composeA2uiRepairTemplate;
    }

    public void setComposeA2uiRepairTemplate(String composeA2uiRepairTemplate) {
        this.composeA2uiRepairTemplate = composeA2uiRepairTemplate;
    }
}
