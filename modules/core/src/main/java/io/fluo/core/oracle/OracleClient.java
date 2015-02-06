/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.core.oracle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import io.fluo.accumulo.util.ZookeeperPath;
import io.fluo.api.exceptions.FluoException;
import io.fluo.core.impl.CuratorCnxnListener;
import io.fluo.core.impl.Environment;
import io.fluo.core.thrift.OracleService;
import io.fluo.core.util.CuratorUtil;
import io.fluo.core.util.UtilWaitThread;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.framework.recipes.leader.Participant;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFastFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to an oracle to retrieve timestamps. If mutliple oracle servers are run, it will automatically
 * fail over to different leaders.
 */
public class OracleClient implements AutoCloseable {

  public static final Logger log = LoggerFactory.getLogger(OracleClient.class);
  private static final int MAX_ORACLE_WAIT_PERIOD = 60;

  private final Timer responseTimer;
  private final Histogram stampsHistogram;
  
  private Participant currentLeader;
  
  private static final class TimeRequest {
    CountDownLatch cdl = new CountDownLatch(1);
    AtomicLong timestamp = new AtomicLong();
  }

  private class TimestampRetriever extends LeaderSelectorListenerAdapter implements Runnable, PathChildrenCacheListener {

    private LeaderSelector leaderSelector;
    private CuratorFramework curatorFramework;
    private OracleService.Client client;
    private PathChildrenCache pathChildrenCache;

    private TTransport transport;

    @Override
    public void run() {
      
      try {
        synchronized (this) {
          //want this code to be mutually exclusive with close() .. so if in middle of setup, close method will wait till finished 
          if(closed){
            return;
          }
          
          curatorFramework = CuratorUtil.newFluoCurator(env.getConfiguration());
          CuratorCnxnListener cnxnListener = new CuratorCnxnListener();
          curatorFramework.getConnectionStateListenable().addListener(cnxnListener);
          curatorFramework.start();

          while (!cnxnListener.isConnected())
            Thread.sleep(200);

          pathChildrenCache = new PathChildrenCache(curatorFramework, ZookeeperPath.ORACLE_SERVER, true);
          pathChildrenCache.getListenable().addListener(this);
          pathChildrenCache.start();

          leaderSelector = new LeaderSelector(curatorFramework, ZookeeperPath.ORACLE_SERVER, this);

          connect();  
        }
        doWork();

      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    /**
     * It's possible an Oracle has gone into a bad state. Upon the leader being changed, we want to update our state
     */
    @Override
    public void childEvent(CuratorFramework curatorFramework, PathChildrenCacheEvent event) throws Exception {

      if (event.getType().equals(PathChildrenCacheEvent.Type.CHILD_REMOVED) ||
          event.getType().equals(PathChildrenCacheEvent.Type.CHILD_ADDED) ||
          event.getType().equals(PathChildrenCacheEvent.Type.CHILD_UPDATED)) {

        Participant participant = leaderSelector.getLeader();
        synchronized (this) {
          if(isLeader(participant))
            currentLeader = leaderSelector.getLeader();
          else
            currentLeader = null;
        }
      }
    }

    private void doWork() {

      ArrayList<TimeRequest> request = new ArrayList<>();

      while (true) {

        try {
          request.clear();
          TimeRequest trh = null;
          while(trh == null){
            if(closed){
              return;
            }
            trh = queue.poll(1, TimeUnit.SECONDS);
          }
          request.add(trh);
          queue.drainTo(request);

          long start;

          while (true) {

            try {
              String currentLeaderId;
              OracleService.Client localClient;
              synchronized (this) {
                currentLeaderId = getOracle();
                localClient = client;
              }

              Context timerContext = responseTimer.time();

              start = localClient.getTimestamps(env.getFluoInstanceID(), request.size());

              String leaderId = getOracle();
              if(leaderId != null && !leaderId.equals(currentLeaderId)) {
                reconnect();
                continue;
              }

              stampsHistogram.update(request.size());
              timerContext.close();

              break;

            } catch (TTransportException tte) {
              log.info("Oracle connection lost. Retrying...");
              reconnect();
            } catch (TException e) {
              e.printStackTrace();
            }
          }

          for (int i = 0; i < request.size(); i++) {
            TimeRequest tr = request.get(i);
            tr.timestamp.set(start + i);
            tr.cdl.countDown();
          }
        } catch (InterruptedException e) {
          if(closed){
            return;
          }
          e.printStackTrace();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    private synchronized void connect() throws IOException, KeeperException, InterruptedException, TTransportException {

      getLeader();
      while (true) {
        log.debug("Connecting to oracle at " + currentLeader.getId());
        String[] hostAndPort = currentLeader.getId().split(":");

        String host = hostAndPort[0];
        int port = Integer.parseInt(hostAndPort[1]);

        try {
          transport = new TFastFramedTransport(new TSocket(host, port));
          transport.open();
          TProtocol protocol = new TCompactProtocol(transport);
          client = new OracleService.Client(protocol);
          log.info("Connected to oracle at " + getOracle());
          break;
        } catch (TTransportException e) {
          sleepRandom();
          getLeader();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    /**
     * Atomically closes current connection and connects to the current leader
     */
    private synchronized void reconnect() throws InterruptedException, TTransportException, KeeperException, IOException {
      if(transport.isOpen())
        transport.close();
      connect();
    }

    private synchronized void close() {
      if(transport != null && transport.isOpen())
        transport.close();
      try {
        if(pathChildrenCache != null)
          pathChildrenCache.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      
      if(curatorFramework != null)
        curatorFramework.close();
      
      transport = null;
      pathChildrenCache = null;
      leaderSelector = null;
      curatorFramework = null;
    }

    private boolean getLeaderAttempt() {
      Participant possibleLeader = null;
      try {
        possibleLeader = leaderSelector.getLeader();
      } catch (KeeperException e) {
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      if (isLeader(possibleLeader)) {
        currentLeader = possibleLeader;
        return true;
      }
      return false;
    }

    /**
     * Attempt to retrieve a leader until one is found
     */
    private void getLeader() {
      boolean found = getLeaderAttempt();
      while (!found) {
        sleepRandom();
        found = getLeaderAttempt();
      }
    }

    /**
     * Sleep a random amount of time from 100ms to 1sec
     */
    private void sleepRandom() {
      UtilWaitThread.sleep(100 + (long) (1000 * Math.random()));
    }

    private boolean isLeader(Participant participant) {
      return participant != null && participant.isLeader();
    }


    /**
     * NOTE: This isn't competing for leadership, so it doesn't need to be started.
     */
    @Override
    public void takeLeadership(CuratorFramework curatorFramework) throws Exception {
    }
  }

  private final Environment env;
  private final ArrayBlockingQueue<TimeRequest> queue = new ArrayBlockingQueue<>(1000);
  private final Thread thread;
  private volatile boolean closed = false;
  private final TimestampRetriever timestampRetriever;

  public OracleClient(Environment env) {
    this.env = env;

    responseTimer = env.getSharedResources().getMetricRegistry().timer(env.getMeticNames().getOracleClientGetStamps());
    stampsHistogram = env.getSharedResources().getMetricRegistry().histogram(env.getMeticNames().getOrcaleClientStamps());

    timestampRetriever = new TimestampRetriever();
    thread = new Thread(timestampRetriever);
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Retrieves time stamp from Oracle.  Throws {@link FluoException} if timed out or interrupted. 
   */
  public long getTimestamp() {
    checkClosed();
    
    TimeRequest tr = new TimeRequest();
    queue.add(tr);
    try {
      int timeout = env.getConfiguration().getClientRetryTimeout();
      if (timeout < 0) {
        long waitPeriod = 1;
        long waitTotal = 0;
        while (!tr.cdl.await(waitPeriod, TimeUnit.SECONDS)) {
          checkClosed();
          waitTotal += waitPeriod;
          if (waitPeriod < MAX_ORACLE_WAIT_PERIOD) {
            waitPeriod *= 2;
          }
          log.warn("Waiting for timestamp from Oracle. Is it running? waitTotal={}s waitPeriod={}s", waitTotal, waitPeriod);
        }
      } else if (!tr.cdl.await(timeout, TimeUnit.MILLISECONDS)) {
        throw new FluoException("Timed out (after "+timeout+"ms) trying to retrieve timestamp from Oracle.  Is the Oracle running?");
      }
    } catch (InterruptedException e) {
      throw new FluoException("Interrupted while retrieving timestamp from Oracle", e);
    }
    return tr.timestamp.get();
  }

  /**
   * Return the oracle that the current client is connected to.
   */
  public synchronized String getOracle() {
    checkClosed();
    return currentLeader != null ? currentLeader.getId() : null;
  }

  private void checkClosed(){
    if(closed){
      throw new IllegalStateException(OracleClient.class.getSimpleName()+" is closed");
    }
  }
  
  @Override
  public void close() {
    if(!closed){
      closed = true;
      try {
        thread.interrupt();
        thread.join();
        timestampRetriever.close();
      } catch (InterruptedException e) {
        throw new FluoException("Interrupted during close", e);
      }
    }
  }  
}
