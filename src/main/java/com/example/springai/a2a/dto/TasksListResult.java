package com.example.springai.a2a.dto;

import java.util.List;

public record TasksListResult(List<TaskView> tasks) {

    public static TasksListResult empty() {
        return new TasksListResult(List.of());
    }
}

