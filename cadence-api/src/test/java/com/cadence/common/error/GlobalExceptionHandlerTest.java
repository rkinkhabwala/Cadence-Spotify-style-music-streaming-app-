package com.cadence.common.error;

import com.cadence.common.pagination.CursorRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProblemController.class)
@Import(GlobalExceptionHandlerTest.ProblemController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    record SignUp(@Email String email, @Size(min = 10) String password) {
    }

    @RestController
    static class ProblemController {

        @GetMapping("/test/missing")
        void missing() {
            throw new NotFoundException("Track", "42");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException("email-taken", "Email already registered");
        }

        @GetMapping("/test/stale")
        void stale() {
            throw new PreconditionFailedException("Version 3 does not match 4");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("db password is hunter2");
        }

        @PostMapping("/test/signup")
        void signUp(@Valid @RequestBody SignUp body) {
        }

        @GetMapping("/test/page")
        CursorRequest page(@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
            return CursorRequest.of(limit, cursor);
        }
    }

    @Test
    void notFound() throws Exception {
        mvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://cadence.dev/problems/not-found"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("not-found"))
                .andExpect(jsonPath("$.detail").value("Track 42 was not found"))
                .andExpect(jsonPath("$.instance").value("/test/missing"));
    }

    @Test
    void conflictAndPreconditionFailed() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email-taken"));
        mvc.perform(get("/test/stale"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("version-mismatch"));
    }

    @Test
    void validationErrorsListEveryField() throws Exception {
        mvc.perform(post("/test/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')].message").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].message").exists());
    }

    @Test
    void malformedJson() throws Exception {
        mvc.perform(post("/test/signup").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    void frameworkErrorsGetACode() throws Exception {
        mvc.perform(post("/test/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("method-not-allowed"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void invalidCursorIsABadRequest() throws Exception {
        mvc.perform(get("/test/page").param("cursor", "%%%not-base64"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-cursor"));
    }
}
