package grabl.tracing.client;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import grabl.tracing.protocol.TracingProto.Trace;
import grabl.tracing.protocol.TracingServiceGrpc.TracingServiceStub;
import io.grpc.stub.StreamObserver;

import static grabl.tracing.util.ProtobufUUIDUtil.toBuf;

/**
 * The GRPC client layer for the tracing session.
 *
 * This client is currently set up not to attempt to recover from errors, it will buffer any errors it receives from the
 * async listener Thread and throw them on the TracingSession user Thread when the next method is called. This has the
 * potential to be confusing, since a server-side issue caused by the client might not be related to the point when the
 * RuntimeException appears to be thrown. The hope is that the (suppressed) exceptions passed on will still be useful.
 *
 * For this setup to work, it is vital that all methods either call {@link #ensureConnection()} or call
 * {@link #throwErrors()} directly.
 */
class TraceStream {
    private final StreamObserver<Trace.Req> requestObserver;
    private final CountDownLatch finishLatch = new CountDownLatch(1);

    private final Deque<Throwable> errors = new ArrayDeque<>();

    TraceStream(TracingServiceStub serviceStub) {
        requestObserver = serviceStub.stream(new TracingResponseObserver());
    }

    void traceRootStart(UUID traceId, UUID analysisId, String name, String tracker, int iteration, long startMillis) {
        assert traceId != null;
        assert analysisId != null;
        ensureConnection();
        Trace.Req req = Trace.Req.newBuilder()
                .setId(toBuf(traceId))
                .setRootStart(Trace.Req.StartRoot.newBuilder()
                        .setAnalysisId(toBuf(analysisId))
                        .setTracker(tracker)
                        .setIteration(iteration))
                .setName(name)
                .setStarted(startMillis)
                .build();
        synchronized (this) {
            requestObserver.onNext(req);
        }
    }

    void traceChildStart(UUID rootId, UUID traceId, UUID parentId, String name, long startMillis) {
        assert rootId != null;
        assert traceId != null;
        ensureConnection();
        Trace.Req req = Trace.Req.newBuilder()
                .setId(toBuf(traceId))
                .setRootId(toBuf(rootId))
                .setParentId(toBuf(parentId))
                .setName(name)
                .setStarted(startMillis)
                .build();
        synchronized (this) {
            requestObserver.onNext(req);
        }
    }

    void traceData(UUID rootId, UUID traceId, String data) {
        assert rootId != null;
        assert traceId != null;
        ensureConnection();
        Trace.Req req = Trace.Req.newBuilder()
                .setId(toBuf(traceId))
                .setRootId(toBuf(rootId))
                .setData(data)
                .build();
        synchronized (this) {
            requestObserver.onNext(req);
        }
    }

    void traceLabels(UUID rootId, UUID traceId, String[] labels) {
        assert rootId != null;
        assert traceId != null;
        ensureConnection();
        Trace.Req req = Trace.Req.newBuilder()
                .setId(toBuf(traceId))
                .setRootId(toBuf(rootId))
                .addAllLabels(Arrays.asList(labels))
                .build();
        synchronized (this) {
            requestObserver.onNext(req);
        }
    }

    void traceEnd(UUID rootId, UUID traceId, long endMillis) {
        assert rootId != null;
        assert traceId != null;
        ensureConnection();
        Trace.Req req = Trace.Req.newBuilder()
                .setId(toBuf(traceId))
                .setRootId(toBuf(rootId))
                .setEnded(endMillis)
                .build();
        synchronized (this) {
            requestObserver.onNext(req);
        }
    }

    void close() throws Exception {
        requestObserver.onCompleted();

        try {
            finishLatch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            requestObserver.onError(e);
            throw e;
        }

        throwErrors();
    }

    private void ensureConnection() {
        if (finishLatch.getCount() == 0) {
            errors.add(new RuntimeException("Connection Lost"));
            throwErrors();
        }
    }

    private synchronized void throwErrors() {
        if (errors.peek() != null) {
            RuntimeException ex = new RuntimeException(errors.pop());
            while (errors.peek() != null) {
                ex.addSuppressed(errors.pop());
            }
            throw ex;
        }
    }

    private class TracingResponseObserver implements StreamObserver<Trace.Res> {

        @Override
        public void onNext(Trace.Res res) {
        }

        @Override
        public void onError(Throwable throwable) {
            errors.add(throwable);
            finishLatch.countDown();
        }

        @Override
        public void onCompleted() {
            finishLatch.countDown();
        }
    }
}
