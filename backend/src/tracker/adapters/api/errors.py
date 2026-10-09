"""problem+json error responses (RFC 9457) with a stable `code` and per-field `errors`."""

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from tracker.application.errors import AppError
from tracker.application.ports import JiraAuthError, JiraError

PROBLEM = "application/problem+json"


def problem(status: int, code: str, detail: str, errors: list[dict] | None = None) -> JSONResponse:
    body = {"type": "about:blank", "title": code.replace("_", " ").capitalize(), "status": status, "code": code, "detail": detail,
            "errors": errors or []}
    return JSONResponse(body, status_code=status, media_type=PROBLEM)


def install(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def app_error(_: Request, e: AppError):
        return problem(e.status, e.code, e.detail, [{"field": e.field, "message": e.detail}] if e.field else [])

    @app.exception_handler(RequestValidationError)
    async def validation(_: Request, e: RequestValidationError):
        errors = [{"field": ".".join(str(p) for p in err["loc"][1:]) or None, "message": err["msg"]} for err in e.errors()]
        return problem(422, "invalid", errors[0]["message"] if errors else "Invalid request", errors)

    @app.exception_handler(JiraAuthError)
    async def jira_auth(_: Request, e: JiraAuthError):
        return problem(502, "jira_auth", str(e))

    @app.exception_handler(JiraError)
    async def jira(_: Request, e: JiraError):
        return problem(502, "jira_unavailable", str(e))
