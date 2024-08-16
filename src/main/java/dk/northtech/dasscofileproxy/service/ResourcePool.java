package dk.northtech.dasscofileproxy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created with IntelliJ IDEA.
 * User: dtsai
 * Date: 2/18/13
 * Time: 3:42 PM
 */

public abstract class ResourcePool<Resource> {
    private final BlockingQueue<Resource> pool;
    private final ReentrantLock lock = new ReentrantLock();
    public Object logObject;
    private int createdObjects = 0;
    private int size;
    List<Instant> creationTimes = new ArrayList<>();
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


    Resource acquire(int maxSeconds) throws Exception {
        if (!lock.isLocked()) {
            if (lock.tryLock()) {
                try {
                    if(!checkAndAddCreationTime()) {
                        // ERDA does not allow to create too many connections within a short timeframe.
                        return pool.take();
                    }
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
        return pool.poll(maxSeconds, TimeUnit.SECONDS);
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

    boolean checkAndAddCreationTime() {
        if(creationTimes.size() == 3 && creationTimes.getLast().plusSeconds(300).isBefore(Instant.now())) {
            return false;
        }
        synchronized (lock) {
            creationTimes.add(Instant.now());
            if(creationTimes.size() > 3) {
                creationTimes.removeLast();
            }
        }
        return true;
    }

//    boolean checkAndRemoveCreationTime() {
//        if(creationTimes.size() == 3 && creationTimes.getLast().plusSeconds(300).isAfter(Instant.now())) {
//            return false;
//        }
//        synchronized (lock) {
//            creationTimes.removeLast();
//        }
//        return true;
//    }

    protected abstract Resource createObject();
}