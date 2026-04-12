package com.example.springsupervisorai.a2a;

import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.a2a.dto.TaskReviewView;
import com.example.springsupervisorai.model.HitlReviewTicket;
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

    /**
     * HITL review 티켓을 API 응답용 뷰로 변환한다.
     *
     * @param ticket review 티켓
     * @return 직렬화 가능한 review view
     */
    public TaskReviewView toTaskReviewView(HitlReviewTicket ticket) {
        if (ticket == null) {
            return null;
        }
        return new TaskReviewView(
                ticket.taskId(),
                ticket.status().name(),
                ticket.policyId(),
                ticket.policyReason(),
                ticket.decisionReason(),
                ticket.requestedAt() == null ? "" : ticket.requestedAt().toString(),
                ticket.decidedAt() == null ? "" : ticket.decidedAt().toString(),
                ticket.expiresAt() == null ? "" : ticket.expiresAt().toString()
        );
    }
}
