"""远程只读资源服务：每次 OAuth 内省，身份不取自客户端参数。"""

import os
import re
import time
from dataclasses import dataclass
from datetime import date, datetime
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

import httpx
from daily_mcp import project
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.provider import AccessToken
from mcp.server.auth.settings import AuthSettings
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from pydantic import AnyHttpUrl


def configured_url(value, public=False, root=False):
    parsed = urlsplit(value)
    if (parsed.scheme not in (('https',) if public else ('http', 'https'))
            or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or (root and parsed.path not in ('', '/'))):
        raise ValueError('接入地址配置无效')
    _ = parsed.port
    return value.rstrip('/')


@dataclass(frozen=True)
class RemoteConfig:
    issuer: str
    resource: str
    introspection: str
    client_id: str
    client_secret: str
    backend: str
    internal_secret: str

    @classmethod
    def load(cls, environment=None):
        env = os.environ if environment is None else environment
        if env.get('DAILY_REMOTE_ENABLED') != 'true':
            raise ValueError('远程每日 MCP 尚未显式启用')
        issuer = configured_url(env.get('DAILY_OAUTH_ISSUER', ''), public=True)
        resource = configured_url(env.get('DAILY_OAUTH_RESOURCE', ''), public=True)
        introspection = configured_url(env.get('DAILY_OAUTH_INTROSPECTION', ''))
        backend = configured_url(env.get('DAILY_REMOTE_BACKEND', ''), root=True)
        values = [env.get(key, '') for key in ('DAILY_OAUTH_CLIENT_ID', 'DAILY_OAUTH_CLIENT_SECRET', 'INTERNAL_API_SECRET')]
        if any(not value or not value.strip() for value in values):
            raise ValueError('OAuth 内省及内部凭据须在本地配置')
        return cls(issuer, resource, introspection, *values[:2], backend, values[2])


async def response_json(client, method, url, **kwargs):
    """限制响应大小，拒绝跳转与非成功响应，不向调用方回显错误正文。"""
    async with client.stream(method, url, **kwargs) as response:
        response.raise_for_status()
        if response.status_code != 200:
            raise ValueError('响应不可用')
        chunks = bytearray()
        async for chunk in response.aiter_bytes():
            chunks.extend(chunk)
            if len(chunks) > 1048576:
                raise ValueError('响应过大')
        import json
        result = json.loads(chunks)
        if not isinstance(result, dict):
            raise TypeError('响应格式无效')
        return result


class IntrospectionVerifier:
    def __init__(self, config, transport=None):
        self.config = config
        self.transport = transport

    async def verify_token(self, token):
        if not isinstance(token, str) or not token or len(token) > 8192:
            return None
        cfg = self.config
        try:
            async with httpx.AsyncClient(transport=self.transport, timeout=5, follow_redirects=False, trust_env=False) as client:
                body = await response_json(client, 'POST', cfg.introspection,
                    auth=(cfg.client_id, cfg.client_secret), data={'token': token, 'token_type_hint': 'access_token'})
            subject, expiry = body.get('sub'), body.get('exp')
            audience = body.get('aud')
            scope = body.get('scope')
            if (body.get('active') is not True or body.get('iss') != cfg.issuer
                    or not isinstance(subject, str) or not re.fullmatch(r'[1-9][0-9]{0,18}', subject)
                    or int(subject) > 9223372036854775807
                    or type(expiry) is not int or expiry <= time.time()
                    or not isinstance(scope, str) or 'daily:read' not in scope.split()
                    or not isinstance(body.get('client_id'), str) or not body['client_id']
                    or not (audience == cfg.resource or (isinstance(audience, list) and cfg.resource in audience))):
                return None
            return AccessToken(token=token, subject=subject, client_id=body['client_id'],
                               scopes=scope.split(), expires_at=expiry, resource=cfg.resource)
        except (httpx.HTTPError, ValueError, TypeError, OSError):
            return None


class ScopedReader:
    def __init__(self, config, transport=None):
        self.config = config
        self.transport = transport

    async def read(self, kind, day=''):
        token = get_access_token()
        if (token is None or not token.subject or not re.fullmatch(r'[1-9][0-9]{0,18}', token.subject)
                or token.resource != self.config.resource or 'daily:read' not in token.scopes
                or token.expires_at is None or token.expires_at <= time.time()):
            return {'error': '授权无效，请重新连接'}
        if kind not in ('plans', 'reviews'):
            return {'error': '仅允许读取计划或复盘'}
        if day == '':
            day = datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()
        try:
            if not isinstance(day, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', day):
                raise ValueError()
            date.fromisoformat(day)
        except (ValueError, TypeError):
            return {'error': '日期须为有效的 YYYY-MM-DD'}
        try:
            async with httpx.AsyncClient(transport=self.transport, timeout=5, follow_redirects=False, trust_env=False) as client:
                result = await response_json(client, 'GET',
                    f'{self.config.backend}/api/internal/daily-read/{token.subject}/{kind}/{day}',
                    headers={'X-Internal-Secret': self.config.internal_secret})
            if result.get('code') != 0 or not isinstance(result.get('data'), dict):
                raise ValueError()
            return project(result['data'])
        except (httpx.HTTPError, ValueError, TypeError, OSError):
            return {'error': '每日记录暂不可用，请在应用中重试'}


def create_remote_server(config, transport=None):
    resource = urlsplit(config.resource)
    server = FastMCP('family-app-daily-remote', stateless_http=True, json_response=True,
        host=os.environ.get('DAILY_REMOTE_HOST', '127.0.0.1'), port=9011,
        streamable_http_path=resource.path or '/mcp',
        token_verifier=IntrospectionVerifier(config, transport),
        auth=AuthSettings(issuer_url=AnyHttpUrl(config.issuer), resource_server_url=AnyHttpUrl(config.resource),
                          required_scopes=['daily:read'], validate_token_resource=True),
        transport_security=TransportSecuritySettings(allowed_hosts=[resource.netloc],
            allowed_origins=[f'{resource.scheme}://{resource.netloc}']))
    reader = ScopedReader(config, transport)
    annotations = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)
    meta = {'securitySchemes': [{'type': 'oauth2', 'scopes': ['daily:read']}]}

    @server.tool(annotations=annotations, meta=meta)
    async def read_daily_plan(day: str = '') -> dict:
        """读取本人已生成的计划，不创建、确认或修改任务。"""
        return await reader.read('plans', day)

    @server.tool(annotations=annotations, meta=meta)
    async def read_daily_review(day: str = '') -> dict:
        """读取本人已生成的复盘，状态代表截止时状态，不读取私人反馈。"""
        return await reader.read('reviews', day)

    return server


if __name__ == '__main__':
    try:
        config = RemoteConfig.load()
    except ValueError as error:
        raise SystemExit(str(error)) from None
    create_remote_server(config).run(transport='streamable-http')
