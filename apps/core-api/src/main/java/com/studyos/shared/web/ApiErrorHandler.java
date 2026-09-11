package com.studyos.shared.web;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiErrorHandler.class);
    public static Map<String,Object> body(String code,String message) {return Map.of("code",code,"message",message,"details",Map.of(),"traceId",UUID.randomUUID().toString(),"timestamp",Instant.now().toString());}
    @ExceptionHandler(ApiException.class) ResponseEntity<?> business(ApiException e){return ResponseEntity.status(e.status()).body(body(e.code(),e.getMessage()));}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,ConstraintViolationException.class,IllegalArgumentException.class})
    ResponseEntity<?> validation(Exception e){return ResponseEntity.badRequest().body(body("VALIDATION_ERROR","Request fields are invalid."));}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e){log.debug("Constraint conflict",e);return ResponseEntity.status(409).body(body("CONFLICT","The change conflicts with existing data."));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e){log.error("Unhandled API error",e);return ResponseEntity.internalServerError().body(body("INTERNAL_ERROR","The request could not be completed."));}
}

