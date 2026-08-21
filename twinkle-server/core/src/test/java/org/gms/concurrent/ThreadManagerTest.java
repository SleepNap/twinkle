package org.gms.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ThreadManagerTest {

    @Test
    public void executesEveryTaskOnNamedVirtualThread() throws Exception {
        try (ThreadManager manager = new ThreadManager()) {
            Thread thread = manager.submit(Thread::currentThread).get();

            assertThat(thread.isVirtual()).isTrue();
            assertThat(thread.getName()).startsWith("twinkle-worker-");
            assertThat(manager.snapshot().virtualThreads()).isTrue();
            assertThat(manager.snapshot().submittedTasks()).isEqualTo(1);
            assertThat(manager.snapshot().succeededTasks()).isEqualTo(1);
        }
    }

    @Test
    public void closeIsIdempotentAndRejectsNewTasks() {
        ThreadManager manager = new ThreadManager();

        manager.close();
        manager.close();

        assertThat(manager.isClosed()).isTrue();
        assertThatThrownBy(() -> manager.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
    }
}
