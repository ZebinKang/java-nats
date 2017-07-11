package io.nats.examples;

import java.nio.ByteBuffer;

public class Payload {
    private byte[] payload;
    private ByteBuffer buffer;

    public Payload(int size){
        payload=new byte[size];
        buffer=ByteBuffer.allocate(Long.SIZE/Byte.SIZE);
    }

    public byte[] preparePayload(long msgIndex,long currTime) {
        buffer.putLong(0, currTime);
        for(int i=0;i<buffer.capacity();i++){
            payload[i]=buffer.get(i);
        }
        buffer.putLong(0, msgIndex);
        for(int i=0;i<buffer.capacity();i++){
            payload[i+Long.SIZE/Byte.SIZE]=buffer.get(i);
        }
        return payload;
    }

    public long extractTime(byte[] bytes) {
        buffer.clear();
        buffer.put(bytes, 0, buffer.capacity());
        buffer.flip();//need flip
        return buffer.getLong();
    }

    public long extractMsgIndex(byte[] bytes) {
        buffer.clear();
        buffer.put(bytes, Long.SIZE/Byte.SIZE, buffer.capacity());
        buffer.flip();//need flip
        return buffer.getLong();
    }
}
