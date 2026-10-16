"""真实 SDK 子进程验证，只查询同进程回环模拟服务器。"""

import asyncio
import json
import os
import sys
import unittest
from pathlib import Path

import test_daily_mcp as fixtures
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


class StdioTests(unittest.TestCase):
    setUpClass = classmethod(fixtures.DailyTests.setUpClass.__func__)
    tearDownClass = classmethod(fixtures.DailyTests.tearDownClass.__func__)
    setUp = fixtures.DailyTests.setUp

    def test_stdio_tools_and_calls(self):
        async def verify():
            script = Path(__file__).resolve().parents[1] / 'daily_mcp.py'
            env = os.environ | self.env | {'PYTHONDONTWRITEBYTECODE': '1'}
            params = StdioServerParameters(command=sys.executable, args=[str(script)], env=env)
            async with stdio_client(params) as (read, write):  # noqa: SIM117 - 会话依赖管道的生命周期
                async with ClientSession(read, write) as session:
                    await session.initialize()
                    tools = (await session.list_tools()).tools
                    self.assertEqual({t.name for t in tools}, {'read_daily_plan', 'read_daily_review'})
                    for tool in tools:
                        self.assertTrue(tool.annotations.readOnlyHint)
                        self.assertFalse(tool.annotations.destructiveHint)
                        self.assertEqual(set(tool.inputSchema['properties']), {'day'})
                    result = await session.call_tool('read_daily_plan', {'day': '2026-10-10'})
                    self.assertFalse(result.isError)
                    body = json.loads(result.content[0].text)
                    self.assertEqual(body['content']['items'][0]['title'], '模拟任务')
                    invalid = await session.call_tool('read_daily_review', {'day': '../settings'})
                    self.assertIn('error', json.loads(invalid.content[0].text))
                    fixtures.Handler.status = 401
                    denied = await session.call_tool('read_daily_review', {'day': '2026-10-10'})
                    self.assertIn('error', json.loads(denied.content[0].text))
                    self.assertEqual(len(fixtures.Handler.calls), 2)
        asyncio.run(verify())


if __name__ == '__main__':
    unittest.main()
