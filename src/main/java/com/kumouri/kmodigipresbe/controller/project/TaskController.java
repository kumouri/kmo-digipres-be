package com.kumouri.kmodigipresbe.controller.project;

import com.kumouri.kmodigipresbe.model.project.Task;
import com.kumouri.kmodigipresbe.model.project.Task.TaskStatus;
import com.kumouri.kmodigipresbe.service.project.TaskService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST API for {@link Task} sub-resources of a {@link com.kumouri.kmodigipresbe.model.project.Project}.
 * Task status changes drive the kanban board in the FE (Phase C C.10 / C-D11).
 */
@RestController
@RequestMapping("/tasks")
@ConditionalOnProperty(prefix = "kmosf.modules.projects", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TaskController {

    private final TaskService service;

    @GetMapping("/by-project/{projectId}")
    public Flux<Task> listByProject(@PathVariable UUID projectId) {
        return service.findByProject(projectId);
    }

    @GetMapping("/{id}")
    public Mono<Task> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/by-project/{projectId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Task> create(@PathVariable UUID projectId, @RequestBody Task body) {
        return service.create(projectId, body);
    }

    @PutMapping("/{id}")
    public Mono<Task> update(@PathVariable UUID id, @RequestBody Task body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    /** Kanban move — updates task status (TASK_STATUS_CHANGED event emitted). */
    @PostMapping("/{id}/status")
    public Mono<Task> changeStatus(@PathVariable UUID id, @RequestParam TaskStatus target) {
        return service.changeStatus(id, target);
    }
}
