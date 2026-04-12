package com.example.springsupervisorai.a2a;

import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import org.springframework.stereotype.Component;

@Component("supervisorA2aResponseMapper")
public class A2AResponseMapper {

    public TaskView toTaskView(A2aTaskSnapshot task) {
        if (task == null) {
            return null;
        }
        return new TaskView(
                task.taskId(),
                task.status().name(),
                "supervisor",
                task.createdAt().toString(),
                task.updatedAt().toString(),
                task.responsePayload(),
                task.errorCode(),
                task.errorMessage()
        );
    }
}
