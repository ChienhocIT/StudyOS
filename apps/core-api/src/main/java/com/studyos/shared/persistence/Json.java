package com.studyos.shared.persistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
public final class Json {
    private static final ObjectMapper MAPPER=JsonMapper.builder().findAndAddModules().build();
    private Json(){}
    public static String write(Object value){try{return MAPPER.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Cannot serialize value",e);}}
    public static Object read(String value){try{return MAPPER.readValue(value,Object.class);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON",e);}}
    public static Map<String,Object> object(String value){try{return MAPPER.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalArgumentException("Invalid JSON object",e);}}
}

