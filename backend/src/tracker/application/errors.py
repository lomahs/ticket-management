"""Errors use cases raise; the API maps each to an HTTP status and a problem+json body."""


class AppError(Exception):
    status = 400
    code = "invalid"

    def __init__(self, detail: str, field: str | None = None):
        super().__init__(detail)
        self.detail, self.field = detail, field


class Invalid(AppError):
    status, code = 422, "invalid"


class NotFound(AppError):
    status, code = 404, "not_found"


class Conflict(AppError):
    status, code = 409, "conflict"


class CapabilityDisabled(AppError):
    status, code = 403, "capability_disabled"


class Unavailable(AppError):
    status, code = 502, "jira_unavailable"
