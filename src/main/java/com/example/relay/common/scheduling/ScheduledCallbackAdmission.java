package com.example.relay.common.scheduling;

import java.util.Objects;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Context-owned linearization boundary for recurring business callbacks. */
@Component
public final class ScheduledCallbackAdmission implements ApplicationListener<ContextClosedEvent>, Ordered {

    private static final BoundaryProbe NO_PROBE = new BoundaryProbe() {
        @Override
        public void afterAdmissionRecorded() {}

        @Override
        public void beforeCloseTransition() {}
    };

    private final ApplicationContext owningApplicationContext;
    private final BoundaryProbe boundaryProbe;
    private final Object boundary = new Object();
    private boolean open = true;
    private int activeAdmissions;

    @Autowired
    public ScheduledCallbackAdmission(ApplicationContext owningApplicationContext) {
        this(owningApplicationContext, NO_PROBE);
    }

    ScheduledCallbackAdmission(ApplicationContext owningApplicationContext, BoundaryProbe boundaryProbe) {
        this.owningApplicationContext = Objects.requireNonNull(owningApplicationContext);
        this.boundaryProbe = Objects.requireNonNull(boundaryProbe);
    }

    public boolean runIfOpen(Runnable admittedBody) {
        Objects.requireNonNull(admittedBody);
        synchronized (boundary) {
            if (!open) {
                return false;
            }
            activeAdmissions++;
            boundaryProbe.afterAdmissionRecorded();
        }

        try {
            admittedBody.run();
            return true;
        } finally {
            synchronized (boundary) {
                activeAdmissions--;
            }
        }
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != owningApplicationContext) {
            return;
        }
        synchronized (boundary) {
            boundaryProbe.beforeCloseTransition();
            open = false;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    interface BoundaryProbe {
        void afterAdmissionRecorded();

        void beforeCloseTransition();
    }
}
