package io.nats.examples;

import java.nio.ByteBuffer;

public class Payload {
    private byte[] payload;
    private ByteBuffer buffer;

    public Payload(int size){
        payload=new byte[size];
        buffer=ByteBuffer.allocate(Long.SIZE/Byte.SIZE);
    }

    public byte[] preparePayload() {
        buffer.putLong(0, System.nanoTime());
        for(int i=0;i<buffer.capacity();i++){
            payload[i]=buffer.get(i);
        }
        return payload;
    }

    public long extractTime(byte[] bytes) {
        buffer.clear();
        buffer.put(bytes, 0, buffer.capacity());
        buffer.flip();//need flip
        return buffer.getLong();
    }
}
