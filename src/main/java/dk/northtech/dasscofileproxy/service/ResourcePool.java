package dk.northtech.dasscofileproxy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public abstract class ResourcePool<Resource> {
    private final BlockingQueue<Resource> pool;
    private final ReentrantLock lock = new ReentrantLock();
    private int createdObjects = 0;
    private int size;
    private static final Logger logger = LoggerFactory.getLogger(ResourcePool.class);

    protected ResourcePool(int size) {
        this(size, false);
    }

    protected ResourcePool(int size, Boolean dynamicCreation) {
        // Enable the fairness; otherwise, some threads may wait forever.
        pool = new ArrayBlockingQueue<>(size, true);
        this.size = size;
        if (!dynamicCreation) {
            lock.lock();
        }
    }

    Resource acquire() throws Exception {
        if (!lock.isLocked()) {
            if (lock.tryLock()) {
                try {
                    Resource object = createObject();
                    ++createdObjects;
                    logger.info("Created {} resources", createdObjects);
                    return object;
                } finally {
                    if (createdObjects < size) {
                        lock.unlock();
                    }
                }
            }
        }
        return pool.take();
    }

    public void recycle(Resource resource)  {
        // Will throws Exception when the queue is full,
        // but it should never happen.
        pool.add(resource);
    }
    public void destroy(Resource resource) {
        createdObjects--;
    }

    public void createPool() {
        if (lock.isLocked()) {
            for (int i = 0; i < size; ++i) {
                pool.add(createObject());
                createdObjects++;
            }
        }
    }

    protected abstract Resource createObject();
}