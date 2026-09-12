package com.rabitah.frontend.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApprovalSocket implements AutoCloseable {
    private final URI endpoint;
    private final HttpClient http;
    private final Executor executor;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean connected;

    public ApprovalSocket(URI apiBase, Executor executor) {
        this.executor = executor;
        this.http = HttpClient.newBuilder().executor(executor).build();
        String scheme = apiBase.getScheme().equalsIgnoreCase("https") ? "wss" : "ws";
        this.endpoint = URI.create(scheme + "://" + apiBase.getAuthority() + "/ws/approvals");
        connect();
    }

    public AutoCloseable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public boolean connected() { return connected; }
    public URI endpoint() { return endpoint; }

    private void connect() {
        if (closed || !connecting.compareAndSet(false, true)) return;
        http.newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).buildAsync(endpoint, new Listener())
                .whenComplete((value, error) -> {
                    connecting.set(false);
                    if (error != null) { connected = false; reconnectLater(); }
                    else socket = value;
                });
    }

    private void reconnectLater() {
        if (!closed) CompletableFuture.runAsync(this::connect,
                CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS, executor));
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() {
        closed = true; connected = false;
        WebSocket current = socket;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "Application closed");
        listeners.clear();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { connected = true; webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) { if (message.toString().endsWith("_CHANGED")) fireChanged(); message.setLength(0); }
            webSocket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connected = false; socket = null; reconnectLater(); return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            connected = false; socket = null; reconnectLater();
        }
    }
}
