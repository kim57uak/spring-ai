package com.example.springai.a2a.mapper;

import com.example.springai.a2a.dto.TaskView;
import com.example.springai.a2a.task.A2aTaskSnapshot;
import org.springframework.stereotype.Component;

/**
 * 내부 작업 스냅샷을 A2A 응답 DTO로 변환한다.
 */
@Component
public class A2AResponseMapper {

    public TaskView toTaskView(A2aTaskSnapshot task) {
        return toTaskView(task, null);
    }

    public TaskView toTaskView(A2aTaskSnapshot task, Object structuredData) {
        if (task == null) {
            return null;
        }
        return new TaskView(
                task.taskId(),
                task.status().name(),
                task.scopeName().name().toLowerCase(),
                task.createdAt().toString(),
                task.updatedAt().toString(),
                task.responsePayload(),
                structuredData,
                task.errorCode(),
                task.errorMessage()
        );
    }
}
