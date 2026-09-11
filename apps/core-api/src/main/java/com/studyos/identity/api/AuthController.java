package com.studyos.identity.api;
import com.studyos.identity.application.AuthService;import com.studyos.shared.security.Actor;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.http.HttpStatus;import org.springframework.web.bind.annotation.*;import java.util.Map;
@RestController @RequestMapping("/api/v1")
public class AuthController {
    private final AuthService service;public AuthController(AuthService service){this.service=service;}
    public record Register(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(min=8,max=72) String password,@NotBlank @Size(max=120) String displayName){}
    public record Login(@NotBlank @Email String email,@NotBlank @Size(max=72) String password){}
    public record Refresh(@NotBlank @Size(max=256) String refreshToken){}
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> register(@Valid @RequestBody Register r){return service.register(r.email(),r.password(),r.displayName());}
    @PostMapping("/auth/login") public Map<String,Object> login(@Valid @RequestBody Login r){return service.login(r.email(),r.password());}
    @PostMapping("/auth/refresh") public Map<String,Object> refresh(@Valid @RequestBody Refresh r){return service.refresh(r.refreshToken());}
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT) public void logout(@Valid @RequestBody Refresh r){service.logout(r.refreshToken());}
    @GetMapping("/me") public Map<String,Object> me(){return service.me(Actor.currentUserId());}
}

