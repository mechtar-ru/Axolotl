package com.agent.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.Map;

@Component
public class DebugShutdownListener implements ApplicationListener<ApplicationFailedEvent> {
    private static final Logger log = LoggerFactory.getLogger(DebugShutdownListener.class);

    @Override
    public void onApplicationEvent(ApplicationFailedEvent event) {
        log.error("[DebugShutdown] application failed: {}", event.getException()==null?"null":event.getException().toString());
        dumpAllThreads("ApplicationFailedEvent");
    }

    // Also register a separate listener via bean method to catch ContextClosedEvent
    @org.springframework.context.event.EventListener
    public void onContextClosed(ContextClosedEvent e) {
        log.warn("[DebugShutdown] ContextClosedEvent: closing context");
        dumpAllThreads("ContextClosedEvent");
    }

    private void dumpAllThreads(String reason) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("--- Thread dump for reason: " + reason + " ---");
            Map<Thread, StackTraceElement[]> map = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> en : map.entrySet()) {
                Thread t = en.getKey();
                pw.println(String.format("Thread %s (id=%d) state=%s", t.getName(), t.getId(), t.getState()));
                for (StackTraceElement st : en.getValue()) {
                    pw.println("    at " + st.toString());
                }
                pw.println();
            }
            pw.flush();
            log.warn(sw.toString());
        } catch (Throwable ex) {
            log.error("Failed to dump threads", ex);
        }
    }
}
