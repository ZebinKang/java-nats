/*
 *  Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 *  materials are made available under the terms of the MIT License (MIT) which accompanies this
 *  distribution, and is available at http://opensource.org/licenses/MIT
 */

package io.nats.examples;

import io.nats.benchmark.Benchmark;
import io.nats.benchmark.Sample;
import io.nats.client.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * A utility class for measuring NATS performance.
 */
public class NatsBench {

    final BlockingQueue<Throwable> errorQueue = new LinkedBlockingQueue<Throwable>();

    // Default test values
    private int numMsgs = 100000;
    private int numPubs = 0;
    private int numSubs = 0;
    private int size = 128;

    private String urls = Nats.DEFAULT_URL;
    private String subject;
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger received = new AtomicInteger();
    private String csvFileName;

    Options opts = null;
    private Thread shutdownHook;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private boolean secure;
    private Benchmark bench;

    static final String usageString =
            "\nUsage: java NatsBench [-s server] [-tls] [-np num] [-ns num] [-n num] [-ms size] "
                    + "[-csv file] <subject>\n\nOptions:\n"
                    + "    -s   <urls>                    The nats server URLs (comma-separated)\n"
                    + "    -tls                            Use TLS secure connection\n"
                    + "    -np                             Number of concurrent publishers\n"
                    + "    -ns                             Number of concurrent subscribers\n"
                    + "    -n                              Number of messages to publish\n"
                    + "    -ms                             Size of the message\n"
                    + "    -csv                            Save bench data to csv file\n";

    /**
     * Main constructor for NatsBench.
     *
     * @param args configuration parameters
     */
    public NatsBench(String[] args) {
        if (args == null || args.length < 1) {
            usage();
            return;
        }
        parseArgs(args);

        if (secure) {
            opts = new Options.Builder(Nats.defaultOptions()).secure().noReconnect().build();
        } else {
            opts = new Options.Builder(Nats.defaultOptions()).noReconnect().build();
        }
    }


    /**
     * Properties-based constructor for NatsBench.
     *
     * @param properties configuration properties
     */
    public NatsBench(Properties properties) {
        urls = properties.getProperty("bench.nats.servers", urls);
        secure = Boolean.parseBoolean(
                properties.getProperty("bench.nats.secure", Boolean.toString(secure)));
        numMsgs = Integer.parseInt(
                properties.getProperty("bench.nats.msg.count", Integer.toString(numMsgs)));
        size = Integer
                .parseInt(properties.getProperty("bench.nats.msg.size", Integer.toString(numSubs)));
        numPubs = Integer
                .parseInt(properties.getProperty("bench.nats.pubs", Integer.toString(numPubs)));
        numSubs = Integer
                .parseInt(properties.getProperty("bench.nats.subs", Integer.toString(numSubs)));
        csvFileName = properties.getProperty("bench.nats.csv.filename", null);
        subject = properties.getProperty("bench.nats.subject", NUID.nextGlobal());
    }

    class Worker implements Runnable {
        final Phaser phaser;
        final int num;
        final int size;
        final int workerIndex;

        Worker(Phaser phaser, int numMsgs, int size, int i) {
            this.phaser = phaser;
            this.num = numMsgs;
            this.size = size;
            workerIndex=i;
        }

        @Override
        public void run() {
        }
    }

    class SubWorker extends Worker {
        SubWorker(Phaser phaser, int numMsgs, int size,int subIndex) {
            super(phaser, numMsgs, size,subIndex);
        }

        @Override
        public void run() {
            try {
                runSubscriber();
            } catch (Exception e) {
                errorQueue.add(e);
                phaser.arrive();
            }
        }

        public void runSubscriber() throws Exception {
            final Connection nc = Nats.connect(urls, opts);
            nc.setDisconnectedCallback(new DisconnectedCallback() {
                @Override
                public void onDisconnect(ConnectionEvent ev) {
                    System.err.printf("Subscriber disconnected after %d msgs\n", received.get());
                }
            });
            nc.setClosedCallback(new ClosedCallback() {
                @Override
                public void onClose(ConnectionEvent ev) {
                    System.err.printf("Subscriber connection closed after %d msgs\n", received.get());
                }
            });
            nc.setExceptionHandler(new ExceptionHandler() {
                @Override
                public void onException(NATSException ex) {
                    System.err.println("Subscriber connection exception: " + ex);
                    AsyncSubscription sub = (AsyncSubscription) ex.getSubscription();
                    System.err.printf("Sent=%d, Received=%d\n", sent.get(), received.get());
                    System.err.printf("Messages dropped (total) = %d\n", sub.getDropped());
                    System.exit(-1);
                }
            });

            final long start = System.nanoTime();
            Subscription sub = nc.subscribe(subject+workerIndex, new MessageHandler() {
                private Payload payload =new Payload(size);
                private long currTime=0;
                private long i=0;
                @Override
                public void onMessage(Message msg)  {
                    currTime=System.currentTimeMillis();
                    received.incrementAndGet();
                    bench.addSubLatency(workerIndex,currTime - payload.extractTime(msg.getData()));
                    i=payload.extractMsgIndex(msg.getData());
                    if(i%(numMsgs/100)==0&&workerIndex==numSubs-1) {
                        System.out.println("sub"+workerIndex + ":" + i / (numMsgs / 100));
                        System.out.println(payload.extractTime(msg.getData()));
                        System.out.println(currTime);

                    }
                    if (payload.extractMsgIndex(msg.getData())+1 >= numMsgs) {
                        bench.addSubSample(new Sample(numMsgs, size, start, System.nanoTime(), nc));
                        phaser.arrive();
                        nc.setDisconnectedCallback(null);
                        nc.setClosedCallback(null);
                        nc.close();
                    }
                }
            });
            sub.setPendingLimits(10000000, 1000000000);
            nc.flush();
            phaser.arrive();
            while (received.get() < numMsgs) {
            }
        }
    }

    class PubWorker extends Worker {
        PubWorker(Phaser phaser, int numMsgs, int size,int pubIndex) {
            super(phaser, numMsgs, size,pubIndex);
        }

        @Override
        public void run() {
            try {
                runPublisher();
                phaser.arrive();
            } catch (Exception e) {
                errorQueue.add(e);
                phaser.arrive();
            }
        }

        public void runPublisher() throws Exception {
            try (Connection nc = Nats.connect(urls, opts)) {
                Payload payload=new Payload(size);
                final long start = System.nanoTime();
                long previousTime=System.nanoTime();
                long currTime;
                long currMillis;

                for (int i = 0; i < numMsgs; i++) {
                    sent.incrementAndGet();
                    previousTime=System.nanoTime();
                    currMillis=System.currentTimeMillis();
                    nc.publish(subject+workerIndex, payload.preparePayload(i,currMillis));
                    currTime=System.nanoTime();
                    bench.addPubLatency(workerIndex,currTime-previousTime);
                    if(i%(numMsgs/100)==0&&workerIndex==numPubs-1) {
                        System.out.println("pub"+workerIndex + ":" + i / (numMsgs / 100));
                        System.out.println(currMillis);
                    }
                }
                nc.flush();
                bench.addPubSample(new Sample(numMsgs, size, start, System.nanoTime(), nc));
                Statistics s = nc.getStats();
                System.out.println("NATS publish connection statistics:");
                System.out.printf("   Bytes out: %d\n", s.getOutBytes());
                System.out.printf("   Msgs  out: %d\n", s.getOutMsgs());
            }
        }
    }


    /**
     * Runs the benchmark.
     *
     * @throws Exception if an exception occurs
     */
    public void run() throws Exception {
        ExecutorService exec = Executors.newCachedThreadPool();
        final Phaser phaser = new Phaser();

        installShutdownHook();

        phaser.register();

        bench = new Benchmark("NATS", numSubs, numPubs,numMsgs);

        // Run Subscribers first
        for (int i = 0; i < numSubs; i++) {
            phaser.register();
            exec.execute(new SubWorker(phaser, numMsgs, size,i));
        }

        // Wait for subscribers threads to initialize
        phaser.arriveAndAwaitAdvance();

        // Now publishers
        for (int i = 0; i < numPubs; i++) {
            System.out.println(i);
            phaser.register();
            exec.execute(new PubWorker(phaser, numMsgs, size,i));
        }

        System.out.printf("Starting benchmark [msgs=%d, msgsize=%d, pubs=%d, subs=%d]\n", numMsgs,
                size, numPubs, numSubs);

        // Wait for subscribers and publishers to finish
        phaser.arriveAndAwaitAdvance();

        // We're done. Clean up and report.
        Runtime.getRuntime().removeShutdownHook(shutdownHook);

        if (!errorQueue.isEmpty()) {
            Throwable error = errorQueue.take();
            System.err.printf(error.getMessage());
            throw new RuntimeException(error);
        }

        bench.close();
        System.out.println(bench.report());

        if (csvFileName != null) {
            List<String> csv = bench.csv();
            Path csvFile = Paths.get(csvFileName);
            Files.write(csvFile, csv, Charset.forName("UTF-8"));
        }
    }

    void installShutdownHook() {
        shutdownHook = new Thread(new Runnable() {
            @Override
            public void run() {
                System.err.println("\nCaught CTRL-C, shutting down gracefully...\n");
                shutdown.set(true);
                System.err.printf("Sent=%d\n", sent.get());
                System.err.printf("Received=%d\n", received.get());

            }
        });

        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    void usage() {
        System.err.println(usageString);
        System.exit(-1);
    }

    private void parseArgs(String[] args) {
        List<String> argList = new ArrayList<String>(Arrays.asList(args));

        subject = argList.get(argList.size() - 1);
        argList.remove(argList.size() - 1);

        // Anything left is flags + args
        Iterator<String> it = argList.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "-s":
                case "--server":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    urls = it.next();
                    it.remove();
                    continue;
                case "-tls":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    secure = true;
                    continue;
                case "-np":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    numPubs = Integer.parseInt(it.next());
                    it.remove();
                    continue;
                case "-ns":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    numSubs = Integer.parseInt(it.next());
                    it.remove();
                    continue;
                case "-n":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    numMsgs = Integer.parseInt(it.next());
                    // hashModulo = numMsgs / 100;
                    it.remove();
                    continue;
                case "-ms":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    size = Integer.parseInt(it.next());
                    // hashModulo = numMsgs / 100;
                    it.remove();
                    continue;
                case "-csv":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    csvFileName = it.next();
                    it.remove();
                    continue;

                default:
                    System.err.printf("Unexpected token: '%s'\n", arg);
                    usage();
                    break;
            }
        }
    }

    private static Properties loadProperties(String configPath) {
        try {
            InputStream is = new FileInputStream(configPath);
            Properties prop = new Properties();
            prop.load(is);
            return prop;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The main program executive.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        Properties properties = null;
        try {
            if (args.length == 1 && args[0].endsWith(".properties")) {
                properties = loadProperties(args[0]);
                new NatsBench(properties).run();
            } else {
                new NatsBench(args).run();
            }
        } catch (Exception e) {
            System.err.println("Exception: " + e);
            System.exit(-1);
        }
        System.exit(0);
    }

}
