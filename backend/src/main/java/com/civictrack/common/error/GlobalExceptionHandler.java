package com.civictrack.common.error;

import com.civictrack.issue.IssueNotFoundException;
import com.civictrack.issue.policy.ForbiddenTransitionException;
import com.civictrack.issue.policy.IllegalTransitionException;
import com.civictrack.issue.policy.TransitionGuardException;
import com.civictrack.user.auth.EmailAlreadyRegisteredException;
import com.civictrack.user.auth.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * RFC 7807 problem responses, built with Spring 6's own {@link ProblemDetail}.
 * No third-party library: the framework has had this since Spring 6.
 *
 * <p>Each problem carries a stable {@code type} URI so a client can branch on
 * the failure without string-matching a human-readable message, and the extra
 * properties give the client what it needs to act. A low-accuracy rejection,
 * for instance, returns the threshold, so the UI can say how far off the fix
 * was and offer manual pin placement rather than a generic error toast.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BASE = "https://civictrack.example/problems/";

    @ExceptionHandler(LowAccuracyException.class)
    ProblemDetail onLowAccuracy(LowAccuracyException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create(BASE + "gps-accuracy-too-low"));
        pd.setTitle("GPS accuracy too low");
        pd.setProperty("accuracyM", ex.getAccuracyM());
        pd.setProperty("maxAccuracyM", ex.getMaxAccuracyM());
        pd.setProperty("remedy", "Ask the user to place the pin manually on the map");
        return pd;
    }

    @ExceptionHandler(UnknownCategoryException.class)
    ProblemDetail onUnknownCategory(UnknownCategoryException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create(BASE + "unknown-category"));
        pd.setTitle("Unknown category");
        pd.setProperty("categoryCode", ex.getCode());
        return pd;
    }

    @ExceptionHandler(OutsideServiceAreaException.class)
    ProblemDetail onOutsideServiceArea(OutsideServiceAreaException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create(BASE + "outside-service-area"));
        pd.setTitle("Outside service area");
        return pd;
    }


    // ------------------------------------------------------------------
    // phase 3: the state machine
    //
    // Three different refusals, three different statuses, because they are
    // three different facts and a client can act on each differently.
    // ------------------------------------------------------------------

    /**
     * The move does not exist in the transition table at all. 409, because the
     * request is well-formed and the caller is entitled to make it -- it just
     * conflicts with the state the issue is actually in, and the same request
     * might succeed later.
     */
    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail onIllegalTransition(IllegalTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(BASE + "illegal-transition"));
        pd.setTitle("Illegal status transition");
        pd.setProperty("from", ex.getFrom().name());
        pd.setProperty("to", ex.getTo().name());
        return pd;
    }

    /**
     * The move exists but this role may not make it. 403, and the message says
     * so plainly -- a staff member who tries to resolve their own ticket should
     * be told that staff cannot, not that the move does not exist.
     */
    @ExceptionHandler(ForbiddenTransitionException.class)
    ProblemDetail onForbiddenTransition(ForbiddenTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setType(URI.create(BASE + "forbidden-transition"));
        pd.setTitle("Transition not permitted for this role");
        pd.setProperty("role", ex.getRole().name());
        pd.setProperty("from", ex.getFrom().name());
        pd.setProperty("to", ex.getTo().name());
        return pd;
    }

    /**
     * A precondition failed. 409 with the guard's name, so a client can react
     * to PROOF_PHOTO_PRESENT specifically -- by opening the camera -- rather
     * than by matching on prose.
     */
    @ExceptionHandler(TransitionGuardException.class)
    ProblemDetail onGuardFailure(TransitionGuardException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(BASE + "transition-guard-failed"));
        pd.setTitle("Transition precondition not met");
        pd.setProperty("guard", ex.getGuard());
        return pd;
    }

    @ExceptionHandler(IssueNotFoundException.class)
    ProblemDetail onIssueNotFound(IssueNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create(BASE + "issue-not-found"));
        pd.setTitle("Issue not found");
        return pd;
    }

    /**
     * 401 rather than 403: the caller failed to prove who they are, which is a
     * different thing from being known and not permitted. One message covers
     * unknown account, wrong password, disabled account and an account seeded
     * without a password, because distinguishing them is a free
     * account-enumeration oracle.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail onInvalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setType(URI.create(BASE + "invalid-credentials"));
        pd.setTitle("Authentication failed");
        return pd;
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail onDuplicateRegistration(EmailAlreadyRegisteredException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(BASE + "account-exists"));
        pd.setTitle("Account already exists");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create(BASE + "validation-failed"));
        pd.setTitle("Request validation failed");
        return pd;
    }
}
