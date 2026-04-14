package com.example.springsupervisorai.a2a.idempotency;

import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorRequestIdempotencyServiceTest {

    @Test
    void shouldReuseCompletedResponseForSameRequestId() {
        SupervisorRequestIdempotencyService service = new SupervisorRequestIdempotencyService();
        AtomicInteger calls = new AtomicInteger();

        JsonRpcResponse first = service.executeOnce(
                "session-1",
                "message/send",
                "req-1",
                () -> {
                    calls.incrementAndGet();
                    return JsonRpcResponse.success("req-1", "ok");
                }
        );
        JsonRpcResponse second = service.executeOnce(
                "session-1",
                "message/send",
                "req-1",
                () -> {
                    calls.incrementAndGet();
                    return JsonRpcResponse.success("req-1", "should-not-run");
                }
        );

        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.result()).isEqualTo("ok");
        assertThat(second.result()).isEqualTo("ok");
    }

    @Test
    void shouldExecuteOnlyOnceForConcurrentDuplicates() throws Exception {
        SupervisorRequestIdempotencyService service = new SupervisorRequestIdempotencyService();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<JsonRpcResponse> owner = executor.submit(() -> service.executeOnce(
                    "session-2",
                    "message/send",
                    "req-2",
                    () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return JsonRpcResponse.success("req-2", "owner-result");
                    }
            ));

            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<JsonRpcResponse> duplicate = executor.submit(() -> service.executeOnce(
                    "session-2",
                    "message/send",
                    "req-2",
                    () -> {
                        calls.incrementAndGet();
                        return JsonRpcResponse.success("req-2", "duplicate-result");
                    }
            ));

            release.countDown();

            JsonRpcResponse first = owner.get(2, TimeUnit.SECONDS);
            JsonRpcResponse second = duplicate.get(2, TimeUnit.SECONDS);

            assertThat(calls.get()).isEqualTo(1);
            assertThat(first.result()).isEqualTo("owner-result");
            assertThat(second.result()).isEqualTo("owner-result");
        } finally {
            executor.shutdownNow();
        }
    }
}
