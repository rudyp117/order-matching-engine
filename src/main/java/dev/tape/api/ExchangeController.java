package dev.tape.api;

import dev.tape.application.ExchangeService;
import dev.tape.domain.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api")
public final class ExchangeController {
    private final ExchangeService service;
    public ExchangeController(ExchangeService service) { this.service = service; }
    @PostMapping("/orders")
    public ResponseEntity<CommandResult> submit(@Valid @RequestBody OrderRequest request) {
        CommandResult result = service.submit(request.side(), request.priceTicks(), request.quantity());
        return ResponseEntity.created(URI.create("/api/orders/" + result.order().id())).body(result);
    }
    @DeleteMapping("/orders/{id}")
    public CommandResult cancel(@PathVariable("id") UUID id) { return service.cancel(id); }
    @GetMapping("/orders/{id}")
    public Order order(@PathVariable("id") UUID id) { return service.order(id); }
    @GetMapping("/book")
    public Book book(@RequestParam(name = "depth", defaultValue = "10") int depth) { return service.book(depth); }
    @GetMapping("/trades")
    public List<Trade> trades(@RequestParam(name = "limit", defaultValue = "50") int limit) { return service.trades(limit); }
    @GetMapping("/metrics")
    public Metrics metrics() { return service.metrics(); }
}
