package com.example.springsupervisorai.a2a.dto;

import java.util.List;

/**
 * tasks/list 응답 결과.
 */
public record TasksListResult(List<TaskView> tasks, String nextPageToken) {
    public TasksListResult(List<TaskView> tasks) {
        this(tasks, null);
    }
}

