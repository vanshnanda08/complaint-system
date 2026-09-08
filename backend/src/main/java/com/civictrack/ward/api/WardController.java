package com.civictrack.ward.api;

import com.civictrack.ward.WardRepository;
import com.civictrack.ward.dto.WardDto;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ward list.
 *
 * <p>The boundary geometry is served through a {@code ST_AsGeoJSON} projection
 * rather than by mapping the column onto the {@link com.civictrack.ward.Ward}
 * entity. The entity's own comment says why the column is unmapped -- nothing
 * in the application needs a MultiPolygon in memory -- and that reason has not
 * changed just because a client now wants to draw one. Mapping it would pull
 * the polygon into every ward load in the application, including the ones that
 * only wanted a name, and would hand Jackson a JTS geometry graph to serialise:
 * the same mistake {@code IssueDto} avoids by never exposing the centroid.
 */
@RestController
@RequestMapping("/api/v1/wards")
@RequiredArgsConstructor
public class WardController {

    private final WardRepository wards;

    @GetMapping
    @Transactional(readOnly = true)
    public List<WardDto> list(@RequestParam(defaultValue = "false") boolean includeBoundary) {
        if (includeBoundary) {
            return wards.findAllWithBoundary().stream()
                    .map(r -> new WardDto(r.getId(), r.getWardNumber(), r.getName(),
                                          r.getBoundary()))
                    .toList();
        }
        return wards.findAll().stream()
                .sorted(java.util.Comparator.comparingInt(com.civictrack.ward.Ward::getWardNumber))
                .map(w -> new WardDto(w.getId(), w.getWardNumber(), w.getName(), null))
                .toList();
    }
}
