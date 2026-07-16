package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.GuestCreateResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Validates, creates, and atomically persists the desktop guest account. */
public final class GuestAccountService implements AutoCloseable {
    private final MultiplayerApi api;
    private final GuestCredentialsStore store;
    private final MultiplayerSession session;
    private final ThreadPoolExecutor fileExecutor;
    private final Object ensureLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    private CompletableFuture<GuestCredentials> ensureInFlight;

    public GuestAccountService(
        MultiplayerApi api,
        GuestCredentialsStore store,
        MultiplayerSession session
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.store = Objects.requireNonNull(store, "store");
        this.session = Objects.requireNonNull(session, "session");
        this.fileExecutor = NetworkExecutors.newBoundedDaemonPool(
            "jjktbf-guest-store", 1, 16);
    }

    public GuestAccountService(MultiplayerApi api, GuestCredentialsStore store) {
        this(api, store, new MultiplayerSession());
    }

    /** Reuses one in-flight operation so concurrent callers cannot create duplicate guests. */
    public CompletableFuture<GuestCredentials> ensureGuest() {
        synchronized (ensureLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("GuestAccountService is closed"));
            }
            if (ensureInFlight != null) {
                return ensureInFlight;
            }

            CompletableFuture<GuestCredentials> result = new CompletableFuture<>();
            ensureInFlight = result;
            startEnsure().whenComplete((credentials, failure) -> {
                synchronized (ensureLock) {
                    if (ensureInFlight == result) {
                        ensureInFlight = null;
                    }
                }
                if (failure == null) {
                    result.complete(credentials);
                } else {
                    result.completeExceptionally(unwrap(failure));
                }
            });
            return result;
        }
    }

    /** Explicitly creates and persists a guest with a caller-selected display name. */
    public CompletableFuture<GuestCredentials> createGuest(String displayName) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("GuestAccountService is closed"));
        }
        return createAndPersist(new GuestCreateRequest(displayName))
            .thenApply(credentials -> {
                session.setGuestCredentials(credentials);
                return credentials;
            });
    }

    private CompletableFuture<GuestCredentials> startEnsure() {
        return fileTask(store::load).thenCompose(stored -> stored
            .map(this::validateStored)
            .orElseGet(() -> createAndPersist(new GuestCreateRequest(null))))
            .thenApply(credentials -> {
                session.setGuestCredentials(credentials);
                return credentials;
            });
    }

    private CompletableFuture<GuestCredentials> validateStored(GuestCredentials stored) {
        return api.getSession(stored.token()).handle((identity, failure) -> {
            if (failure == null) {
                GuestCredentials refreshed = new GuestCredentials(identity, stored.token());
                if (refreshed.equals(stored)) {
                    return CompletableFuture.completedFuture(stored);
                }
                return persist(refreshed);
            }

            Throwable cause = unwrap(failure);
            if (cause instanceof ApiClientException apiFailure
                && apiFailure.isInvalidToken()) {
                session.clearGuestCredentials();
                return fileTask(() -> {
                    store.clear();
                    return null;
                }).thenCompose(ignored ->
                    createAndPersist(new GuestCreateRequest(null)));
            }
            return CompletableFuture.<GuestCredentials>failedFuture(cause);
        }).thenCompose(future -> future);
    }

    private CompletableFuture<GuestCredentials> createAndPersist(GuestCreateRequest request) {
        return api.createGuest(request).thenCompose(this::persistCreated);
    }

    private CompletableFuture<GuestCredentials> persistCreated(GuestCreateResponse response) {
        if (response == null || response.identity() == null) {
            return CompletableFuture.failedFuture(new ApiClientException(
                ApiClientException.Kind.MALFORMED_RESPONSE,
                201,
                "MALFORMED_RESPONSE",
                "The server returned an unreadable guest account.",
                null
            ));
        }
        return persist(new GuestCredentials(response.identity(), response.token()));
    }

    private CompletableFuture<GuestCredentials> persist(GuestCredentials credentials) {
        return fileTask(() -> {
            store.save(credentials);
            return credentials;
        });
    }

    private <T> CompletableFuture<T> fileTask(IoSupplier<T> supplier) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("GuestAccountService is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }, fileExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("GuestAccountService is closed", exception));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        fileExecutor.shutdownNow();
        try {
            fileExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
