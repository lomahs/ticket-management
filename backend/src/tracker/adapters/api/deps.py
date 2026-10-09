from typing import Annotated

from fastapi import Depends, Request

from tracker.application.context import Deps


def get_deps(request: Request) -> Deps:
    return request.app.state.deps


D = Annotated[Deps, Depends(get_deps)]
