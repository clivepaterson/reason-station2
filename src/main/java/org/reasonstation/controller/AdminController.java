package org.reasonstation.controller;

import org.reasonstation.entity.User;
import org.reasonstation.repository.CatalogRepository.PagedResult;
import org.reasonstation.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
public class AdminController {
    private static final int PAGE_SIZE = 100;
    private final UserRepository users;

    public AdminController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<PagedResult<AdminUser>> users(
            @RequestParam(value = "page", defaultValue = "1") int page) {
        if (page < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Page numbers must be 1 or greater.");
        }
        var result = users.findAll(PageRequest.of(page - 1, PAGE_SIZE,
                Sort.by(Sort.Order.desc("dateCreated"), Sort.Order.asc("userid"))));
        var items = result.getContent().stream().map(AdminUser::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PagedResult<>(items, page, PAGE_SIZE,
                        result.getTotalElements(), result.getTotalPages()));
    }

    public record AdminUser(Long userid, String alias, String email, boolean emailVerified,
                            boolean admin, Instant dateCreated) {
        private static AdminUser from(User user) {
            return new AdminUser(user.getUserid(), user.getAlias(), user.getEmail(),
                    user.isEmailVerified(), user.isAdmin(), user.getDateCreated());
        }
    }
}
