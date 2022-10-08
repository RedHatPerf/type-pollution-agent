package io.type.pollution.agent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

/**
 * The algorithm of this list is a mix of
 * https://github.com/JCTools/JCTools/blob/master/jctools-core/src/main/java/org/jctools/queues/MpscUnboundedXaddArrayQueue.java
 * and the enhanced Michael-Scott queue on {@link ConcurrentLinkedQueue}.
 */
class AppendOnlyList<E> {
    private static final class Chunk extends AtomicReferenceArray {
        private static final AtomicReferenceFieldUpdater<Chunk, Chunk> NEXT_UPDATER = AtomicReferenceFieldUpdater.newUpdater(Chunk.class, Chunk.class, "next");
        private Chunk prev;
        private long id;
        private volatile Chunk next;

        public Chunk(long id) {
            this(id, null);
        }

        public Chunk(long id, Chunk prev) {
            super(CHUNK_SIZE);
            this.id = id;
            this.prev = prev;
        }

        public boolean trySetNext(Chunk next) {
            return NEXT_UPDATER.compareAndSet(this, null, next);
        }

    }

    // This MUST be a power of 2
    private static final int CHUNK_SIZE = 128;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;
    private static final int CHUNK_SHIFT = Integer.numberOfTrailingZeros(CHUNK_SIZE);

    private final AtomicLong appenderSequence = new AtomicLong();
    private final Chunk firstChunk;
    private final AtomicReference<Chunk> lastChunk = new AtomicReference<>();

    public AppendOnlyList() {
        firstChunk = new Chunk(0);
        lastChunk.lazySet(firstChunk);
    }

    private Chunk producerChunkForIndex(final Chunk initialChunk, final long requiredChunkId) {
        final AtomicReference<Chunk> lastChunk = this.lastChunk;
        Chunk currentChunk = initialChunk;
        long jumpBackward;
        Chunk tmpChunk = null;
        while (true) {
            if (currentChunk == null) {
                currentChunk = lastChunk.get();
            }
            final long currentChunkId = currentChunk.id;
            // if the required chunk id is less than the current chunk id then we need to walk the linked list of
            // chunks back to the required id
            jumpBackward = currentChunkId - requiredChunkId;
            if (jumpBackward >= 0) {
                break;
            }
            final long nextChunkId = currentChunkId + 1;
            Chunk nextChunk;
            // fast-path to save allocating a new chunk
            if ((nextChunk = currentChunk.next) != null) {
                // try help
                lastChunk.compareAndSet(currentChunk, nextChunk);
                if (requiredChunkId == nextChunkId) {
                    return nextChunk;
                }
                currentChunk = null;
            } else {
                // slow (maybe) allocating path
                if (tmpChunk == null) {
                    tmpChunk = new Chunk(nextChunkId, currentChunk);
                } else {
                    tmpChunk.id = nextChunkId;
                    tmpChunk.prev = currentChunk;
                }
                if (currentChunk.trySetNext(tmpChunk)) {
                    lastChunk.compareAndSet(currentChunk, tmpChunk);
                    if (requiredChunkId == nextChunkId) {
                        return tmpChunk;
                    }
                    currentChunk = null;
                    tmpChunk = null;
                } else {
                    // reset it
                    tmpChunk.prev = null;
                    // failed to append to next, try help moving lastChunk forward
                    nextChunk = currentChunk.next;
                    lastChunk.compareAndSet(currentChunk, nextChunk);
                    if (requiredChunkId == nextChunkId) {
                        return nextChunk;
                    }
                    currentChunk = null;
                }
            }
        }
        for (long i = 0; i < jumpBackward; i++) {
            currentChunk = currentChunk.prev;
            assert currentChunk != null;
        }
        return currentChunk;
    }

    public void add(E e) {
        if (null == e) {
            throw new NullPointerException();
        }
        final long pIndex = appenderSequence.getAndIncrement();

        final int piChunkOffset = (int) (pIndex & CHUNK_MASK);
        final long piChunkId = pIndex >> CHUNK_SHIFT;

        Chunk pChunk = lastChunk.get();
        if (pChunk.id != piChunkId) {
            pChunk = producerChunkForIndex(pChunk, piChunkId);
        }
        pChunk.lazySet(piChunkOffset, e);
    }

    public void forEach(Consumer<? super E> accept) {
        long remaining = appenderSequence.get();
        if (remaining == 0) {
            return;
        }
        Chunk currentChunk = firstChunk;
        while (true) {
            final int batch = (int) Math.min(CHUNK_SIZE, remaining);
            for (int i = 0; i < batch; i++) {
                Object e;
                while ((e = currentChunk.get(i)) == null) {
                    Thread.onSpinWait();
                }
                accept.accept((E) e);
            }
            remaining -= batch;
            if (remaining == 0) {
                return;
            }
            while ((currentChunk = currentChunk.next) == null) {
                Thread.onSpinWait();
            }
        }
    }

    public long size() {
        return appenderSequence.get();
    }

}
