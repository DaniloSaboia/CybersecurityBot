package com.sentinel.kms.graph;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.sentinel.kms.graph.GraphDtos.*;

@RestController
@RequestMapping("/api/graph")
public class GraphController {
    private final GraphService service;
    public GraphController(GraphService service){ this.service=service; }
    @PostMapping("/entities") @ResponseStatus(HttpStatus.CREATED)
    public Entity createEntity(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody EntityInput input){
        return service.createEntity(subject(jwt),input);
    }
    @PostMapping("/edges") @ResponseStatus(HttpStatus.CREATED)
    public Edge createEdge(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody EdgeInput input){
        return service.createEdge(subject(jwt),input);
    }
    @GetMapping("/entities/{id}")
    public ResponseEntity<Entity> entity(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){
        return service.readEntity(subject(jwt),id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());
    }
    @GetMapping("/edges/{id}")
    public ResponseEntity<Edge> edge(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){
        return service.readEdge(subject(jwt),id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());
    }
    private static String subject(Jwt jwt){
        if(jwt==null||jwt.getSubject()==null||jwt.getSubject().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jwt.getSubject();
    }
}
