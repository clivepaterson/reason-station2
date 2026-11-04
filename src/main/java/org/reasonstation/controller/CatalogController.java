package org.reasonstation.controller;

import org.reasonstation.repository.CatalogRepository;
import org.reasonstation.repository.CatalogRepository.SortDirection;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private static final int PAGE_SIZE = 25;
    private static final Set<String> ARTIST_SORTS =
            Set.of("artistAlias", "dateCreated");
    private static final Set<String> TRACK_SORTS =
            Set.of("artistAlias", "trackName", "musicStyle", "filename", "dateUploaded");
    private static final Set<String> PATCH_SORTS =
            Set.of("artistAlias", "patchName", "filename", "ext", "dateUploaded");
    private static final Pattern CATALOG_FILTER_PATTERN = Pattern.compile("[A-Za-z0-9.]+");

    private final CatalogRepository catalog;

    public CatalogController(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/artists")
    public Object artists(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "sort", defaultValue = "dateCreated") String sort,
            @RequestParam(value = "direction", defaultValue = "desc") String direction,
            @RequestParam(value = "filter", defaultValue = "") String filter) {
        validate(page, sort, ARTIST_SORTS);
        String normalizedFilter = normalizeFilter(filter);
        return catalog.findArtists(
                page, PAGE_SIZE, sort, parseDirection(direction), normalizedFilter);
    }

    @GetMapping("/tracks")
    public Object tracks(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "sort", defaultValue = "dateUploaded") String sort,
            @RequestParam(value = "direction", defaultValue = "desc") String direction,
            @RequestParam(value = "filter", defaultValue = "") String filter) {
        validate(page, sort, TRACK_SORTS);
        String normalizedFilter = normalizeFilter(filter);
        return catalog.findTracks(
                page, PAGE_SIZE, sort, parseDirection(direction), normalizedFilter);
    }

    @GetMapping("/patches")
    public Object patches(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "sort", defaultValue = "dateUploaded") String sort,
            @RequestParam(value = "direction", defaultValue = "desc") String direction,
            @RequestParam(value = "filter", defaultValue = "") String filter) {
        validate(page, sort, PATCH_SORTS);
        String normalizedFilter = normalizeFilter(filter);
        return catalog.findPatches(
                page, PAGE_SIZE, sort, parseDirection(direction), normalizedFilter);
    }

    private String normalizeFilter(String filter) {
        if (filter == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Catalog filter is required.");
        }
        String normalizedFilter = filter.trim();
        if (normalizedFilter.length() > 100
                || (!normalizedFilter.isEmpty()
                && !CATALOG_FILTER_PATTERN.matcher(normalizedFilter).matches())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Catalog filters must be 100 characters or fewer and may contain only letters, numbers, and periods.");
        }
        return normalizedFilter;
    }

    private void validate(int page, String sort, Set<String> allowedSorts) {
        if (page < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Page numbers must be 1 or greater.");
        }
        if (sort == null || !allowedSorts.contains(sort)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unsupported sort column.");
        }
    }

    private SortDirection parseDirection(String direction) {
        if (direction == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Sort direction must be asc or desc.");
        }
        try {
            return SortDirection.valueOf(direction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Sort direction must be asc or desc.");
        }
    }
}
