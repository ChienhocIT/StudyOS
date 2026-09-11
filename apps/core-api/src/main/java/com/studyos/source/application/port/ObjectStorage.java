package com.studyos.source.application.port;
import java.time.Instant;import java.util.Map;
public interface ObjectStorage {
    record Upload(String url,Instant expiresAt,Map<String,String> headers){}
    record ObjectInfo(long size,String checksum,String mimeType){}
    Upload upload(String key,String mimeType);
    void put(String key,byte[] bytes,String mimeType);
    ObjectInfo inspect(String key,long maxBytes);
}

