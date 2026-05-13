package com.example.springsupervisorai.a2a.dto;

import java.util.List;

public record TasksListResult(List<TaskView> tasks, String nextPageToken) {
    public TasksListResult(List<TaskView> tasks) {
        this(tasks, null);
    }
}

