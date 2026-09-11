package com.studyos.shared.web;
import java.util.List;
public record PageResponse<T>(List<T> items, PageMeta page) {
    public record PageMeta(String nextCursor,boolean hasMore){}
    public PageResponse(List<T> items,String nextCursor,boolean hasMore){this(items,new PageMeta(nextCursor,hasMore));}
    public static <T> PageResponse<T> of(List<T> items){return new PageResponse<>(items,null,false);}
}

