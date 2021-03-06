package oraclefeeder.core.threads;

import oraclefeeder.core.domain.CacheIterateGroup;
import oraclefeeder.core.domain.ThreadCacheResult;
import oraclefeeder.core.logs.L4j;
import oraclefeeder.core.statistics.Capturer;
import oraclefeeder.properties.Settings;
import oraclefeeder.properties.xml.mapping.Metric;
import oraclefeeder.sender.Sender;
import oraclefeeder.sender.graphite.sender.GraphiteSender;
import oraclefeeder.utils.Utils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ThreadManager implements Runnable {

    private List<Metric> metrics;
    private CacheTime cacheTime;
    private Connection connection;
    private int totalStaticQuerys;
    private Map<Integer,List<CacheIterateGroup>> cacheIterateGroups;
    private Boolean fristIteration;
    private Sender sender;

    private ThreadCacheResult threadCacheResult;


    public ThreadManager(List<Metric> metrics, Connection connection) throws SQLException {
        this.metrics = metrics;
        this.connection = connection;
        this.sender = this.getSender();
        this.cacheTime = this.readMetrics();
        OfStats ofStats = new OfStats(this.sender);
        new Thread(this.cacheTime, "CacheTime").start();
        new Thread(ofStats, "OfStats").start();
    }

    private CacheTime readMetrics(){
        this.threadCacheResult = new ThreadCacheResult();
        this.threadCacheResult.isRunning(true);
        this.threadCacheResult.setCacheIterateGroups(new LinkedList<CacheIterateGroup>());
        CacheTime cache = new CacheTime(this.connection, this.threadCacheResult, this.sender);
        for(Metric metric:this.metrics){
            if(metric.getStatement() != null){
                cache.put(metric.getId(), metric);
            } else {
                this.totalStaticQuerys = this.totalStaticQuerys + metric.getQuery().size();
            }
        }
        return cache;
    }

    public void run() {
        this.fristIteration = true;
        boolean start = true;
        while(start){
            long start_time=System.currentTimeMillis();
            this.cacheIterateGroups = this.getCache();
            try {
                this.launchThreads(this.cacheIterateGroups);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            long elapsed = (System.currentTimeMillis() - start_time);
            if(this.fristIteration) {
                this.fristIteration = false;
            }
            try {
                Thread.sleep(Utils.calculateNextIteration(elapsed));
            } catch (InterruptedException e) {
                start = false;
                e.printStackTrace();
            }
        }
    }

    private Map<Integer,List<CacheIterateGroup>> getCache(){
        if(this.fristIteration){
            while(true){
                if(this.threadCacheResult.isRunning() != null && !this.threadCacheResult.isRunning()) {
                    int totalResultCached = (Utils.countSingleQuery(this.metrics) + Utils.countIterateQuerys(this.threadCacheResult));
                    this.sender.send(Settings.propertie().getGraphitePrefix() + ".of_stats.metrics.cache.0.number_metrics " + totalResultCached + " " + System.currentTimeMillis() / 1000L + "\n");
                    if(Settings.propertie().getShowThreadsInfo()){
                        this.showThreadInfo();
                    }
                    return this.cacheTime.allocateQuerys(this.metrics);
                }
            }
        } else {
            if(this.threadCacheResult.isRunning() != null && !this.threadCacheResult.isRunning()) {
                int totalResultCached = (Utils.countSingleQuery(this.metrics) + Utils.countIterateQuerys(this.threadCacheResult));
                this.sender.send(Settings.propertie().getGraphitePrefix() + ".of_stats.metrics.cache.0.number_metrics " + totalResultCached + " " + System.currentTimeMillis() / 1000L + "\n");
                return this.cacheTime.allocateQuerys(this.metrics);
            } else {
                return this.cacheIterateGroups;
            }
        }

    }

    private void showThreadInfo(){
        Integer maxThreads =  Integer.valueOf(Settings.propertie().getMaxThreads());
        int threadNum = 0;
        for(Integer numQuerys:Utils.calculateQuerysForThreadReal(this.threadCacheResult, this.metrics, maxThreads)){
            threadNum++;
            L4j.getL4j().info("############################################");
            L4j.getL4j().info("# Threading info");
            L4j.getL4j().info("# Worker: "+ threadNum);
            L4j.getL4j().info("# Number of queries to this thread: " + numQuerys);
            L4j.getL4j().info("############################################\n");
        }
    }

    private void launchThreads(Map<Integer,List<CacheIterateGroup>> launchThreadsCache) throws SQLException {
        this.checkConnection();
        long start_time=System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(Integer.valueOf(Settings.propertie().getMaxThreads()));
        int threadNum = 0;
        for(Map.Entry<Integer,List<CacheIterateGroup>> mapCache: launchThreadsCache.entrySet()){
            threadNum++;
            Capturer capturer = new Capturer(mapCache.getValue(), this.metrics, this.connection, this.fristIteration);
            Runnable worker = new MetricsCollector(capturer, this.sender, threadNum);
            executor.execute(worker);
        }
        executor.shutdown();
        try {
            executor.awaitTermination(50, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        boolean waitToThreads = true;
        boolean oneTime = true;
        while(waitToThreads){
            long elapsed = (System.currentTimeMillis() - start_time);
            if(executor.isTerminated()){
                waitToThreads = false;
            }
            if(elapsed == Settings.propertie().getThreadsIntervalSec() && oneTime){
                L4j.getL4j().critical("The time elapsed by thread is more than: " + Settings.propertie().getThreadsIntervalSec());
                oneTime = false;
            }
        }
    }
    public Sender getSender(){
        GraphiteSender sender = new GraphiteSender();
        sender.init();
        return sender;
    }


    public void checkConnection(){
        //TODO
    }
}