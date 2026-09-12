package com.rabitah.backend.realtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class ApprovalEvents {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    void connected(WebSocketSession session) { sessions.add(session); }
    void disconnected(WebSocketSession session) { sessions.remove(session); }

    public void approvalsChanged() {
        changed("APPROVALS_CHANGED");
    }

    public void chatChanged() {
        changed("CHAT_CHANGED");
    }

    private void changed(String event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { broadcast(event); }
            });
        } else {
            broadcast(event);
        }
    }

    private void broadcast(String event) {
        TextMessage message = new TextMessage(event);
        sessions.removeIf(session -> {
            if (!session.isOpen()) return true;
            try { synchronized (session) { session.sendMessage(message); } return false; }
            catch (Exception ignored) { return true; }
        });
    }
}
