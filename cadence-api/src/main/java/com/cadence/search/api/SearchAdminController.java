package com.cadence.search.api;

import com.cadence.common.web.ApiPaths;
import com.cadence.search.application.SearchIndexAdmin;
import com.cadence.search.application.SearchIndexAdmin.ReindexResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.V1 + "/admin/search")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin search")
class SearchAdminController {

    private final SearchIndexAdmin admin;

    SearchAdminController(SearchIndexAdmin admin) {
        this.admin = admin;
    }

    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Drop and rebuild the search indices from a full catalog and playlist replay",
            description = "Idempotent: the end state is the same however often it runs. Indexing completes asynchronously.")
    ReindexResult reindex() {
        return admin.reindex();
    }
}
