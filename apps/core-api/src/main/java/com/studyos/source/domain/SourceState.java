package com.studyos.source.domain;
import java.util.*;
public enum SourceState{
    CREATED,UPLOADING,QUEUED,PARSING,NORMALIZED,CHUNKING,EMBEDDING,ENRICHING,READY,FAILED,DELETING,DELETED;
    public boolean canAdvanceTo(SourceState next){
        if(this==DELETED||this==DELETING||this==READY)return false;
        if(next==FAILED)return this!=FAILED;
        if(this==FAILED)return next==QUEUED;
        return next.ordinal()>ordinal()&&next.ordinal()<=READY.ordinal();
    }
    public int progress(){return switch(this){case READY->100;case ENRICHING->90;case EMBEDDING->70;case CHUNKING->50;case NORMALIZED->40;case PARSING->20;case QUEUED->10;default->0;};}
}

