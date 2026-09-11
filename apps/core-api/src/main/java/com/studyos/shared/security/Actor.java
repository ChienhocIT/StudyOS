package com.studyos.shared.security;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import com.studyos.shared.web.ApiException;
public record Actor(UUID userId) {
    public static UUID currentUserId(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof Actor actor))throw new ApiException(401,"UNAUTHENTICATED","Sign in to continue.");
        return actor.userId();
    }
}

