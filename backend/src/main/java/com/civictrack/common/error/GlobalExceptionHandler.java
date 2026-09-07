package com.civictrack.common.error;

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
