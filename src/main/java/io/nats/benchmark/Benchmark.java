/*
 *  Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 *  materials are made available under the terms of the MIT License (MIT) which accompanies this
 *  distribution, and is available at http://opensource.org/licenses/MIT
 */

package io.nats.benchmark;

import io.nats.client.NUID;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A utility class for collecting and calculating benchmark metrics.
 */
public class Benchmark extends Sample {
    private String name = null;
    private String runId = null;
    private final SampleGroup pubs = new SampleGroup();
    private final SampleGroup subs = new SampleGroup();
    private BlockingQueue<Sample> pubChannel;
    private BlockingQueue<Sample> subChannel;
    private int numMsgs;
    private long[] pubTimeSlots;
    private long[] subTimeSlots;
    private int[] pubSlotIndex;
    private int[] subSlotIndex;



    /**
     * Initializes a Benchmark. After creating a bench call addSubSample/addPubSample. When done
     * collecting samples, call endBenchmark.
     *
     * @param name   a descriptive name for this test run
     * @param subCnt the number of subscribers
     * @param pubCnt the number of publishers
     */
    public Benchmark(String name, int subCnt, int pubCnt,int num) {
        this.name = name;
        this.subChannel = new LinkedBlockingQueue<Sample>();
        this.pubChannel = new LinkedBlockingQueue<Sample>();
        numMsgs=num;
        subTimeSlots=new long[subCnt*num];
        pubTimeSlots=new long[pubCnt*num];
        subSlotIndex=new int[subCnt];
        pubSlotIndex=new int[pubCnt];
    }

    public final void addPubSample(Sample sample) {
        pubChannel.add(sample);
    }

    public final void addSubSample(Sample sample) {
        subChannel.add(sample);
    }

    public final void addPubLatency(int pubIndex, long latency) {
        pubTimeSlots[pubSlotIndex[pubIndex]+numMsgs*pubIndex]=latency;
        pubSlotIndex[pubIndex]+=1;
    }

    public final void addSubLatency(int subIndex, long latency) {
        subTimeSlots[subSlotIndex[subIndex]+numMsgs*subIndex]=latency;
        subSlotIndex[subIndex]+=1;
    }

    /**
     * Closes this benchmark and calculates totals and times.
     */
    public final void close() {
        while (subChannel.size() > 0) {
            subs.addSample(subChannel.poll());
        }
        while (pubChannel.size() > 0) {
            pubs.addSample(pubChannel.poll());
        }

        if (subs.hasSamples()) {
            start = subs.getStart();
            end = subs.getEnd();
        } else {
            start = pubs.getStart();
            end = pubs.getEnd();
        }

        end = Math.min(end, subs.getEnd());
        end = Math.min(end, pubs.getEnd());

        msgBytes = pubs.msgBytes + subs.msgBytes;
        ioBytes = pubs.ioBytes + subs.ioBytes;
        msgCnt = pubs.msgCnt + subs.msgCnt;
        jobMsgCnt = pubs.jobMsgCnt + subs.jobMsgCnt;
    }

    /**
     * Creates the output report.
     *
     * @return the report as a String.
     */
    public final String report() {
        reportLatency();
        StringBuilder sb = new StringBuilder();
        String indent = "";
        if (pubs.hasSamples() && subs.hasSamples()) {
            sb.append(String.format("%s Pub/Sub stats: %s\n", name, this));
            indent += " ";
        }
        if (pubs.hasSamples()) {
            String maybeTitle = "";
            if (!subs.hasSamples()) {
                maybeTitle = name + " ";
            }
            sb.append(String.format("%s%sPub stats: %s\n", indent, maybeTitle, pubs));
            if (pubs.getSamples().size() > 1) {
                for (Sample stat : pubs.getSamples()) {
                    sb.append(String.format("%s [%d] %s (%d msgs)\n", indent,
                            pubs.getSamples().indexOf(stat) + 1, stat, stat.jobMsgCnt));
                }
                sb.append(String.format("%s %s\n", indent, pubs.statistics()));
            }
        }
        if (subs.hasSamples()) {
            String maybeTitle = "";
            sb.append(String.format("%s%sSub stats: %s\n", indent, maybeTitle, subs));
            if (subs.getSamples().size() > 1) {
                for (Sample stat : subs.getSamples()) {
                    sb.append(String.format("%s [%d] %s (%d msgs)\n", indent,
                            subs.getSamples().indexOf(stat) + 1, stat, stat.jobMsgCnt));
                }
                sb.append(String.format("%s %s\n", indent, subs.statistics()));
            }
        }
        return sb.toString();
    }

    /**
     * Returns a list of text lines for output to a CSV file.
     *
     * @return a list of text lines for output to a CSV file
     */
    public final List<String> csv() {
        List<String> lines = new ArrayList<String>();
        String header =
                "#RunID, ClientID, MsgCount, MsgBytes, MsgsPerSec, BytesPerSec, DurationSecs";
        lines.add(header);
        SampleGroup[] groups = new SampleGroup[] {subs, pubs};
        String pre = "S";
        int i = 0;
        for (SampleGroup grp : groups) {
            if (i++ == 1) {
                pre = "P";
            }
            int j = 0;
            for (Sample stat : grp.getSamples()) {
                String line = String.format("%s,%s%d,%d,%d,%d,%f,%f", runId, pre, j, stat.msgCnt,
                        stat.msgBytes, stat.rate(), stat.throughput(),
                        (double) stat.duration() / 1000000000.0);
                lines.add(line);
            }
        }
        return lines;
    }

    public final String getName() {
        return name;
    }

    public final void setName(String name) {
        this.name = name;
    }

    public final String getRunId() {
        return runId;
    }

    public final void setRunId(String runId) {
        this.runId = runId;
    }

    public final SampleGroup getPubs() {
        return pubs;
    }

    public final SampleGroup getSubs() {
        return subs;
    }

    private void reportLatency(){
        if(pubSlotIndex.length>0)
            exportLatencyData(pubTimeSlots,"latency/pubTime.csv");
        if(subSlotIndex.length>0)
            exportLatencyData(subTimeSlots,"latency/subTime.csv");
    }

    private void exportLatencyData(long[] timeSlots, String fileName) {
        Arrays.sort(timeSlots);
        int TIME_SLICE = 1000;
        int numMsgsForEachSlice = timeSlots.length / TIME_SLICE;
        int indexWithinSlice = 0;
        int indexOfSlice = 0;
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileName))) {
            bw.write("Index,Latency\n");
            for (int i = 0; i < timeSlots.length; i += 1) {
                indexWithinSlice += 1;
                if (indexWithinSlice == numMsgsForEachSlice) {
                    indexOfSlice += 1;
                    bw.write(indexOfSlice + "," + timeSlots[i - numMsgsForEachSlice + 1] + "\n");
                    indexWithinSlice = 0;
                }
            }
        } catch (IOException e) {

            e.printStackTrace();

        }
    }
}
