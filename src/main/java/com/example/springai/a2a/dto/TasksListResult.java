package com.example.springai.a2a.dto;

import java.util.List;

public record TasksListResult(List<TaskView> tasks, String nextPageToken) {
    public TasksListResult(List<TaskView> tasks) {
        this(tasks, null);
    }
}

