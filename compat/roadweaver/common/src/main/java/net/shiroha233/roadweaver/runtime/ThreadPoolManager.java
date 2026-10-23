package net.shiroha233.roadweaver.runtime;

import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** One bounded worker budget shared by all worlds and RoadWeaver task roles. */
public final class ThreadPoolManager {
    private ThreadPoolManager() {}
    public enum TaskRole {
        INITIAL(0), PLANNING(10), COARSE(10), GENERATION(20), MAP(30), POSTPROCESS(30), IDLE(40);
        private final int priority;
        TaskRole(int priority) { this.priority = priority; }
    }
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final AtomicLong TASK_SEQ = new AtomicLong();
    private static final ThreadLocal<Boolean> WORKER = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Long> WORKER_EPOCH = new ThreadLocal<>();
    private static final ThreadLocal<Long> WORK_START = ThreadLocal.withInitial(System::currentTimeMillis);
    private static volatile ThreadPoolExecutor workers;
    private static volatile boolean stopped;
    private static final ExecutorService[] ROLES = java.util.Arrays.stream(TaskRole.values())
            .map(RoleExecutorService::new).toArray(ExecutorService[]::new);

    public static synchronized void onServerStarted(MinecraftServer server) {
        shutdownPool(); EPOCH.incrementAndGet(); stopped = false;
        workers = newPool(resolveWorkerThreads());
    }
    public static void onServerStopping() {
        ThreadPoolExecutor draining;
        synchronized (ThreadPoolManager.class) {
            stopped = true;
            EPOCH.incrementAndGet();
            draining = workers;
            shutdownPool();
        }
        // Future cancellation is not evidence that an interrupt-ignoring generator
        // has left its write path. This bounded wait runs only during server stop.
        if (draining != null && !WORKER.get()) {
            try {
                if (!draining.awaitTermination(30, TimeUnit.SECONDS)) {
                    System.getLogger(ThreadPoolManager.class.getName()).log(System.Logger.Level.ERROR,
                            "RoadWeaver workers did not stop within 30 seconds; stale-write fences remain active");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                System.getLogger(ThreadPoolManager.class.getName()).log(System.Logger.Level.WARNING,
                        "Interrupted while draining RoadWeaver workers; stale-write fences remain active");
            }
        }
    }
    /** Resize in place without interrupting accepted jobs. All legacy resize APIs share this budget. */
    public static synchronized void resizeSharedPool(int requested) {
        ThreadPoolExecutor pool = executor();
        int count = clampWorkerThreads(requested <= 0 ? resolveWorkerThreads() : requested);
        if (count > pool.getMaximumPoolSize()) pool.setMaximumPoolSize(count);
        pool.setCorePoolSize(count);
        if (count < pool.getMaximumPoolSize()) pool.setMaximumPoolSize(count);
    }
    public static void resizeInitialPool(int n) { resizeSharedPool(n); }
    public static void resizeGenerationPool(int n) { resizeSharedPool(n); }
    public static void resizeComputePool(int n) { resizeSharedPool(n); }
    public static ExecutorService computeExecutor() { return roleExecutor(TaskRole.PLANNING); }
    public static ExecutorService generationExecutor() { return roleExecutor(TaskRole.GENERATION); }
    public static ExecutorService roleExecutor(TaskRole role) { return ROLES[(role == null ? TaskRole.PLANNING : role).ordinal()]; }
    public static void execute(TaskRole role, Runnable task) { roleExecutor(role).execute(task); }
    public static Future<?> submit(TaskRole role, Runnable task) { return roleExecutor(role).submit(task); }
    public static <T> Future<T> submit(TaskRole role, Callable<T> task) { return roleExecutor(role).submit(task); }
    public static CompletableFuture<Void> runAsync(TaskRole role, Runnable task) {
        return supplyAsync(role, () -> { task.run(); return null; });
    }
    public static <T> CompletableFuture<T> supplyAsync(TaskRole role, Supplier<T> supplier) {
        CompletableFuture<T> completion = new CompletableFuture<>();
        FutureTask<T> task = new FutureTask<>(supplier::get) {
            @Override protected void done() {
                if (isCancelled()) { completion.cancel(false); return; }
                try { completion.complete(get()); }
                catch (ExecutionException e) { completion.completeExceptionally(e.getCause()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); completion.completeExceptionally(e); }
            }
        };
        completion.whenComplete((value, failure) -> { if (completion.isCancelled()) task.cancel(true); });
        try { execute(role, task); }
        catch (RejectedExecutionException e) { completion.completeExceptionally(e); }
        return completion;
    }
    public static long currentEpoch() { return EPOCH.get(); }
    public static boolean isEpoch(long epoch) { return !stopped && EPOCH.get() == epoch; }
    public static boolean isStopping() { return stopped; }
    public static boolean isCurrentWorkerSession() {
        if (stopped) return false;
        Long generation = WORKER_EPOCH.get();
        return !WORKER.get() || generation != null && generation == EPOCH.get()
                && !Thread.currentThread().isInterrupted();
    }
    public static int sharedBacklog() { ThreadPoolExecutor p = workers; return p == null ? 0 : p.getQueue().size(); }
    public static int workerLimit() { ThreadPoolExecutor p = workers; return p == null ? resolveWorkerThreads() : p.getMaximumPoolSize(); }
    public static void throttle(int duty) {
        if (!WORKER.get() || duty >= RoadConstants.DUTY_CYCLE_MAX) return;
        if (duty <= 0) duty = RoadConstants.DEFAULT_DUTY_CYCLE;
        if (System.currentTimeMillis() - WORK_START.get() >= RoadConstants.WORK_PERIOD_MS) {
            long pause = (long) (RoadConstants.WORK_PERIOD_MS * (100.0 - duty) / duty);
            if (pause > 0) {
                try { Thread.sleep(Math.min(pause, RoadConstants.MAX_SLEEP_MS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            resetThrottle();
        }
    }
    public static void throttle() {
        try { throttle(ConfigService.get().performance().threadDutyCycle()); } catch (RuntimeException ignored) {}
    }
    public static void resetThrottle() { WORK_START.set(System.currentTimeMillis()); }
    public static void clearThrottle() { WORK_START.remove(); }
    public static int defaultWorkerThreads(int processors) { return Math.max(1, Math.min(2, (processors - 2) / 2)); }
    private static int clampWorkerThreads(int n) {
        return Math.max(1, Math.min(Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors() - 2)), n));
    }
    private static int resolveWorkerThreads() {
        int n = Integer.getInteger("roadweaver.workerThreads", 0);
        return n > 0 ? clampWorkerThreads(n) : defaultWorkerThreads(Runtime.getRuntime().availableProcessors());
    }
    private static synchronized ThreadPoolExecutor executor() {
        if (stopped) throw new RejectedExecutionException("RoadWeaver is stopping");
        if (workers == null) workers = newPool(resolveWorkerThreads());
        return workers;
    }
    private static ThreadPoolExecutor newPool(int count) {
        AtomicLong next = new AtomicLong();
        return new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS,
                new BoundedPriorityQueue(256), task -> {
                    Thread worker = new Thread(task, "RW-Shared-" + next.incrementAndGet());
                    worker.setDaemon(true); return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    private static void shutdownPool() {
        ThreadPoolExecutor previous = workers; workers = null;
        if (previous != null) for (Runnable queued : previous.shutdownNow()) {
            if (queued instanceof PrioritizedRunnable task) task.cancel();
        }
    }
    /** ThreadPoolExecutor uses offer; a full queue rejects, never runs on the submitting server thread. */
    static final class BoundedPriorityQueue extends PriorityBlockingQueue<Runnable> {
        private final int capacity;
        BoundedPriorityQueue(int capacity) { this.capacity = capacity; }
        @Override public synchronized boolean offer(Runnable task) { return size() < capacity && super.offer(task); }
        @Override public void put(Runnable task) { if (!offer(task)) throw new RejectedExecutionException("RoadWeaver queue full"); }
        @Override public boolean offer(Runnable task, long timeout, TimeUnit unit) { return offer(task); }
        @Override public int remainingCapacity() { return Math.max(0, capacity - size()); }
    }
    private static final class RoleExecutorService extends AbstractExecutorService {
        private final TaskRole role;
        RoleExecutorService(TaskRole role) { this.role = role; }
        @Override public void shutdown() { onServerStopping(); }
        @Override public List<Runnable> shutdownNow() { onServerStopping(); return List.of(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { ThreadPoolExecutor p = workers; return stopped && (p == null || p.isTerminated()); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            ThreadPoolExecutor p = workers; return p == null || p.awaitTermination(timeout, unit);
        }
        @Override public void execute(Runnable command) {
            java.util.Objects.requireNonNull(command);
            if (stopped) throw new RejectedExecutionException("RoadWeaver is stopping");
            // Existing sampling helpers wait on nested tasks. Inline ONLY on our own worker
            // avoids starvation with one worker and never moves expensive work to the server.
            if (WORKER.get()) {
                Long generation = WORKER_EPOCH.get();
                if (generation == null || !isEpoch(generation)) throw new RejectedExecutionException("Stale RoadWeaver worker session");
                command.run();
            } else executor().execute(new PrioritizedRunnable(role, command));
        }
    }
    private static final class PrioritizedRunnable implements Runnable, Comparable<PrioritizedRunnable> {
        private final TaskRole role;
        private final Runnable delegate;
        private final long sequence = TASK_SEQ.getAndIncrement();
        private final long epoch = currentEpoch();
        PrioritizedRunnable(TaskRole role, Runnable delegate) { this.role = role; this.delegate = delegate; }
        void cancel() { if (delegate instanceof Future<?> future) future.cancel(false); }
        @Override public void run() {
            if (!isEpoch(epoch)) { cancel(); return; }
            WORKER.set(true);
            WORKER_EPOCH.set(epoch);
            try { delegate.run(); } finally { WORKER.remove(); WORKER_EPOCH.remove(); clearThrottle(); }
        }
        @Override public int compareTo(PrioritizedRunnable other) {
            int priority = Integer.compare(role.priority, other.role.priority);
            return priority != 0 ? priority : Long.compare(sequence, other.sequence);
        }
    }
}
