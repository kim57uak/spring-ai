package com.example.springai.model.agent;

public enum AgentScopeName {
    PRODUCT("product"),
    RESERVATION("reservation"),
    SEARCH("search");

    private final String propertyKey;

    AgentScopeName(String propertyKey) {
        this.propertyKey = propertyKey;
    }

    public String propertyKey() {
        return propertyKey;
    }
}
