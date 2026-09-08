package com.civictrack.category.api;

import com.civictrack.category.CategoryRepository;
import com.civictrack.category.dto.CategoryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * The category list, which the report composer's picker and the cluster
 * inspector's circles both read.
 *
 * <p>Inactive categories are excluded. A deactivated category still has
 * historical issues pointing at it -- those keep rendering, because the issue
 * carries its own {@code categoryCode} and the public issue query joins the
 * row regardless of {@code active} -- but it must not appear in a picker as
 * something new can be filed under.
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository categories;

    @GetMapping
    @Transactional(readOnly = true)
    public List<CategoryDto> list() {
        return categories.findAll().stream()
                .filter(com.civictrack.category.Category::isActive)
                .sorted(Comparator.comparing(com.civictrack.category.Category::getDisplayName))
                .map(CategoryDto::from)
                .toList();
    }
}
