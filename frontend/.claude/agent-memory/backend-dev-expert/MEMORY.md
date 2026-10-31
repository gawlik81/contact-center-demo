# Memory Index

- [GlobalExceptionHandler swallows ResponseStatusException as 500](project_global_exception_handler_response_status_bug.md) — verified bug: raw ResponseStatusException throws get caught by the catch-all Exception.class handler and return 500, not the intended status. Prefer dedicated domain exceptions (ConflictException, ResourceNotFoundException, ...).
- [Backend code review log location](reference_cr_backend_log.md) — CR-BACKEND.md, one dated section per review, appended at the end.
