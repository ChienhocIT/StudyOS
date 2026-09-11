import time

import jwt

from .config import Settings
from .contracts import InternalContext
from .errors import PipelineError


def validate_internal_token(
    token: str, context: InternalContext, permission: str, settings: Settings
) -> None:
    try:
        claims = jwt.decode(
            token,
            settings.internal_jwt_secret.get_secret_value(),
            algorithms=["HS256"],
            audience=settings.internal_jwt_audience,
            issuer=settings.internal_jwt_issuer,
            options={"require": ["exp", "iat", "sub", "context", "aud", "iss"]},
        )
        if claims["exp"] - claims["iat"] > 300 or claims["iat"] > time.time() + 5:
            raise ValueError("Invalid token lifetime")
        signed = InternalContext.model_validate(claims["context"])
        if (
            signed != context
            or claims["sub"] != str(context.user_id)
            or permission not in signed.permissions
        ):
            raise ValueError("Context or permission mismatch")
    except (jwt.PyJWTError, ValueError, TypeError, KeyError) as exc:
        raise PipelineError(
            "INTERNAL_AUTH_INVALID", "Invalid signed tenant context."
        ) from exc


def sign_internal_token(context: InternalContext, settings: Settings) -> str:
    now = int(time.time())
    return jwt.encode(
        {
            "iss": settings.internal_jwt_issuer,
            "aud": settings.internal_jwt_audience,
            "sub": str(context.user_id),
            "iat": now,
            "exp": now + 120,
            "context": context.model_dump(
                mode="json", by_alias=True, exclude_none=True
            ),
        },
        settings.internal_jwt_secret.get_secret_value(),
        algorithm="HS256",
    )
