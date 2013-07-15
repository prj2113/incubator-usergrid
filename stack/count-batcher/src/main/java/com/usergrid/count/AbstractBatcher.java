/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.usergrid.count;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import com.usergrid.count.common.Count;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

/**
 * Base batcher implementation, handles concurrency and locking throughput
 * throttling.
 *
 * @author zznate
 */
public abstract class AbstractBatcher implements Batcher {
    protected BatchSubmitter batchSubmitter;

    private Batch batch;
    private final AtomicLong opCount = new AtomicLong();
    private final Timer addTimer =
            Metrics.newTimer(AbstractBatcher.class, "add_invocation", TimeUnit.MICROSECONDS, TimeUnit.SECONDS);
    protected final Counter invocationCounter =
            Metrics.newCounter(AbstractBatcher.class, "batch_add_invocations");
    private final Counter existingCounterHit =
            Metrics.newCounter(AbstractBatcher.class,"counter_existed");
  // TODO add batchCount, remove shouldSubmit, impl submit, change simpleBatcher to just be an extension
  protected int batchSize = 500;
  private final AtomicLong batchSubmissionCount = new AtomicLong();

  public AbstractBatcher() {
    batch = new Batch();
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
    batch = new Batch();
  }



    public void setBatchSubmitter(BatchSubmitter batchSubmitter) {
        this.batchSubmitter = batchSubmitter;
    }

    /**
     * Individual {@link Count} for the same counter get rolled up, so
     * we track the individual number of operations.
     * @return the number of operation against this SimpleBatcher
     */
    public long getOpCount() {
        return opCount.get();
    }




  /**
     * Add a count object to this batcher
     * @param count
     * @throws CounterProcessingUnavailableException
     */
    public void add(Count count) throws CounterProcessingUnavailableException {
      invocationCounter.inc();
      final TimerContext context = addTimer.time();

      batch.add(count);

      context.stop();

    }

  public long getBatchSubmissionCount() {
    return batchSubmissionCount.get();
  }

    class Batch {
        private BlockingQueue<Count> counts;
        private final AtomicInteger localCallCounter = new AtomicInteger();
        private final ReentrantLock lock = new ReentrantLock();

        Batch() {
            counts = new ArrayBlockingQueue<Count>(batchSize);
        }



      void add(Count count) {
          if (!counts.offer(count) ) {
            ArrayList<Count> flushed = new ArrayList<Count>(batchSize);
            counts.drainTo(flushed);
            batchSubmitter.submit(flushed);
            batchSubmissionCount.incrementAndGet();
            counts.offer(count);
          }


            opCount.incrementAndGet();
            localCallCounter.incrementAndGet();

        }



        /**
         * The number of times the {@link #add(com.usergrid.count.common.Count)} method has been
         * invoked on this batch instance
         * @return
         */
        public int getLocalCallCount() {
            return localCallCounter.get();
        }





    }

}
